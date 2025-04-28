/*
 * Copyright 2024 Datastrato Pvt Ltd.
 * This software is licensed under the Apache License version 2.
 */
package com.datastrato.gravitino.server.web.rest;

import static javax.ws.rs.core.Response.Status.CONFLICT;
import static org.apache.gravitino.Configs.TREE_LOCK_CLEAN_INTERVAL;
import static org.apache.gravitino.Configs.TREE_LOCK_MAX_NODE_IN_MEMORY;
import static org.apache.gravitino.Configs.TREE_LOCK_MIN_NODE_IN_MEMORY;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.reset;
import static org.mockito.Mockito.when;

import com.datastrato.gravitino.catalog.DatastratoFilesetDispatcher;
import com.datastrato.gravitino.catalog.DatastratoModelDispatcher;
import com.datastrato.gravitino.catalog.DatastratoSchemaDispatcher;
import com.datastrato.gravitino.catalog.DatastratoTableDispatcher;
import com.datastrato.gravitino.catalog.DatastratoTopicDispatcher;
import com.datastrato.gravitino.dto.requests.CatalogWithTagsCreateRequest;
import com.datastrato.gravitino.dto.requests.FilesetWithTagsCreateRequest;
import com.datastrato.gravitino.dto.requests.ModelWithTagsCreateRequest;
import com.datastrato.gravitino.dto.requests.SchemaWithTagsCreateRequest;
import com.datastrato.gravitino.dto.requests.TableWithTagsCreateRequest;
import com.datastrato.gravitino.dto.requests.TopicWithTagsCreateRequest;
import com.datastrato.gravitino.dto.responses.CatalogWithTagsResponse;
import com.datastrato.gravitino.dto.responses.FilesetWithTagsResponse;
import com.datastrato.gravitino.dto.responses.ModelWithTagsResponse;
import com.datastrato.gravitino.dto.responses.SchemaWithTagsResponse;
import com.datastrato.gravitino.dto.responses.TableWithTagsResponse;
import com.datastrato.gravitino.dto.responses.TopicWithTagsResponse;
import com.google.common.collect.ImmutableMap;
import java.io.IOException;
import java.time.Instant;
import java.util.Arrays;
import java.util.Collections;
import java.util.Map;
import javax.servlet.http.HttpServletRequest;
import javax.ws.rs.client.Entity;
import javax.ws.rs.core.Application;
import javax.ws.rs.core.MediaType;
import javax.ws.rs.core.Response;
import org.apache.commons.lang3.reflect.FieldUtils;
import org.apache.gravitino.Audit;
import org.apache.gravitino.Catalog;
import org.apache.gravitino.Config;
import org.apache.gravitino.GravitinoEnv;
import org.apache.gravitino.Namespace;
import org.apache.gravitino.Schema;
import org.apache.gravitino.catalog.CatalogDispatcher;
import org.apache.gravitino.catalog.CatalogManager;
import org.apache.gravitino.catalog.FilesetDispatcher;
import org.apache.gravitino.catalog.ModelDispatcher;
import org.apache.gravitino.catalog.SchemaDispatcher;
import org.apache.gravitino.catalog.TableDispatcher;
import org.apache.gravitino.catalog.TopicDispatcher;
import org.apache.gravitino.dto.CatalogDTO;
import org.apache.gravitino.dto.rel.ColumnDTO;
import org.apache.gravitino.dto.responses.ErrorConstants;
import org.apache.gravitino.dto.responses.ErrorResponse;
import org.apache.gravitino.dto.util.DTOConverters;
import org.apache.gravitino.exceptions.CatalogAlreadyExistsException;
import org.apache.gravitino.exceptions.FilesetAlreadyExistsException;
import org.apache.gravitino.exceptions.ModelAlreadyExistsException;
import org.apache.gravitino.exceptions.SchemaAlreadyExistsException;
import org.apache.gravitino.exceptions.TableAlreadyExistsException;
import org.apache.gravitino.exceptions.TagAlreadyAssociatedException;
import org.apache.gravitino.exceptions.TopicAlreadyExistsException;
import org.apache.gravitino.file.Fileset;
import org.apache.gravitino.lock.LockManager;
import org.apache.gravitino.messaging.Topic;
import org.apache.gravitino.meta.AuditInfo;
import org.apache.gravitino.meta.CatalogEntity;
import org.apache.gravitino.model.Model;
import org.apache.gravitino.rel.Column;
import org.apache.gravitino.rel.Table;
import org.apache.gravitino.rel.types.Type;
import org.apache.gravitino.rel.types.Types;
import org.apache.gravitino.rest.RESTUtils;
import org.apache.gravitino.tag.TagDispatcher;
import org.glassfish.hk2.utilities.binding.AbstractBinder;
import org.glassfish.jersey.server.ResourceConfig;
import org.glassfish.jersey.test.JerseyTest;
import org.glassfish.jersey.test.TestProperties;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

public class TestCreationWithTagsOperations extends JerseyTest {
  private static class MockServletRequestFactory extends ServletRequestFactoryBase {
    @Override
    public HttpServletRequest get() {
      HttpServletRequest request = mock(HttpServletRequest.class);
      when(request.getRemoteUser()).thenReturn(null);
      return request;
    }
  }

  private final CatalogDispatcher catalogDispatcher = mock(CatalogManager.class);
  private final DatastratoSchemaDispatcher schemaDispatcher =
      mock(DatastratoSchemaDispatcher.class);
  private final DatastratoTableDispatcher tableDispatcher = mock(DatastratoTableDispatcher.class);
  private final DatastratoFilesetDispatcher filesetDispatcher =
      mock(DatastratoFilesetDispatcher.class);
  private final DatastratoTopicDispatcher topicDispatcher = mock(DatastratoTopicDispatcher.class);
  private final DatastratoModelDispatcher modelDispatcher = mock(DatastratoModelDispatcher.class);
  private final TagDispatcher tagDispatcher = mock(TagDispatcher.class);

  @BeforeAll
  public static void setup() throws IllegalAccessException {
    Config config = mock(Config.class);
    Mockito.doReturn(100000L).when(config).get(TREE_LOCK_MAX_NODE_IN_MEMORY);
    Mockito.doReturn(1000L).when(config).get(TREE_LOCK_MIN_NODE_IN_MEMORY);
    Mockito.doReturn(36000L).when(config).get(TREE_LOCK_CLEAN_INTERVAL);
    FieldUtils.writeField(GravitinoEnv.getInstance(), "lockManager", new LockManager(config), true);
  }

  @Override
  protected Application configure() {
    try {
      forceSet(
          TestProperties.CONTAINER_PORT, String.valueOf(RESTUtils.findAvailablePort(2000, 3000)));
    } catch (IOException e) {
      throw new RuntimeException(e);
    }

    ResourceConfig resourceConfig = new ResourceConfig();
    resourceConfig.register(CreationWithTagsOperations.class);
    resourceConfig.register(
        new AbstractBinder() {
          @Override
          protected void configure() {
            bind(catalogDispatcher).to(CatalogDispatcher.class).ranked(2);
            bind(schemaDispatcher).to(SchemaDispatcher.class).ranked(2);
            bind(tableDispatcher).to(TableDispatcher.class).ranked(2);
            bind(filesetDispatcher).to(FilesetDispatcher.class).ranked(2);
            bind(topicDispatcher).to(TopicDispatcher.class).ranked(2);
            bind(modelDispatcher).to(ModelDispatcher.class).ranked(2);
            bind(tagDispatcher).to(TagDispatcher.class).ranked(2);
            bindFactory(TestCreationWithTagsOperations.MockServletRequestFactory.class)
                .to(HttpServletRequest.class);
          }
        });

    return resourceConfig;
  }

  @Test
  public void testCreateCatalogWithTag() {
    // test create catalog without tags
    CatalogWithTagsCreateRequest req =
        new CatalogWithTagsCreateRequest(
            "catalog1",
            Catalog.Type.RELATIONAL,
            "test",
            "comment",
            ImmutableMap.of("key", "value"),
            null);
    TestCatalog catalog = buildCatalog("metalake", "catalog1");

    when(catalogDispatcher.createCatalog(any(), any(), any(), any(), any())).thenReturn(catalog);

    Response resp =
        target("/web/with-tags/metalakes/metalake1/catalogs")
            .request(MediaType.APPLICATION_JSON_TYPE)
            .accept("application/vnd.gravitino.v1+json")
            .post(Entity.entity(req, MediaType.APPLICATION_JSON_TYPE));

    Assertions.assertEquals(Response.Status.OK.getStatusCode(), resp.getStatus());
    Assertions.assertEquals(MediaType.APPLICATION_JSON_TYPE, resp.getMediaType());

    CatalogWithTagsResponse catalogWithTagsResponse =
        resp.readEntity(CatalogWithTagsResponse.class);
    Assertions.assertEquals(0, catalogWithTagsResponse.getCode());

    CatalogDTO catalogDTO = catalogWithTagsResponse.getCatalog();
    Assertions.assertEquals("catalog1", catalogDTO.name());
    Assertions.assertEquals(0, catalogWithTagsResponse.getTags().length);

    // test create catalog with tags
    String[] tags = new String[] {"tag1", "tag2"};
    req =
        new CatalogWithTagsCreateRequest(
            "catalog2",
            Catalog.Type.RELATIONAL,
            "test",
            "comment",
            ImmutableMap.of("key", "value"),
            tags);

    catalog = buildCatalog("metalake", "catalog2");

    when(catalogDispatcher.createCatalog(any(), any(), any(), any(), any())).thenReturn(catalog);
    when(tagDispatcher.associateTagsForMetadataObject(any(), any(), any(), any())).thenReturn(tags);

    resp =
        target("/web/with-tags/metalakes/metalake1/catalogs")
            .request(MediaType.APPLICATION_JSON_TYPE)
            .accept("application/vnd.gravitino.v1+json")
            .post(Entity.entity(req, MediaType.APPLICATION_JSON_TYPE));

    Assertions.assertEquals(Response.Status.OK.getStatusCode(), resp.getStatus());
    Assertions.assertEquals(MediaType.APPLICATION_JSON_TYPE, resp.getMediaType());

    catalogWithTagsResponse = resp.readEntity(CatalogWithTagsResponse.class);
    Assertions.assertEquals(0, catalogWithTagsResponse.getCode());

    catalogDTO = catalogWithTagsResponse.getCatalog();
    Assertions.assertEquals("catalog2", catalogDTO.name());
    Assertions.assertEquals(2, catalogWithTagsResponse.getTags().length);
    Assertions.assertArrayEquals(tags, catalogWithTagsResponse.getTags());

    // test create catalog error
    doThrow(new CatalogAlreadyExistsException("mock error"))
        .when(catalogDispatcher)
        .createCatalog(any(), any(), any(), any(), any());
    Response errorResp =
        target("/web/with-tags/metalakes/metalake1/catalogs")
            .request(MediaType.APPLICATION_JSON_TYPE)
            .accept("application/vnd.gravitino.v1+json")
            .post(Entity.entity(req, MediaType.APPLICATION_JSON_TYPE));

    Assertions.assertEquals(CONFLICT.getStatusCode(), errorResp.getStatus());

    ErrorResponse errorResponse = errorResp.readEntity(ErrorResponse.class);
    Assertions.assertEquals(ErrorConstants.ALREADY_EXISTS_CODE, errorResponse.getCode());
    Assertions.assertEquals(
        CatalogAlreadyExistsException.class.getSimpleName(), errorResponse.getType());

    // test tag association error
    catalog = buildCatalog("metalake", "catalog3");
    reset(catalogDispatcher);
    when(catalogDispatcher.createCatalog(any(), any(), any(), any(), any())).thenReturn(catalog);
    doThrow(new TagAlreadyAssociatedException("mock error"))
        .when(tagDispatcher)
        .associateTagsForMetadataObject(any(), any(), any(), any());
    errorResp =
        target("/web/with-tags/metalakes/metalake1/catalogs")
            .request(MediaType.APPLICATION_JSON_TYPE)
            .accept("application/vnd.gravitino.v1+json")
            .post(Entity.entity(req, MediaType.APPLICATION_JSON_TYPE));

    Assertions.assertEquals(CONFLICT.getStatusCode(), errorResp.getStatus());
    errorResponse = errorResp.readEntity(ErrorResponse.class);
    Assertions.assertEquals(ErrorConstants.ALREADY_EXISTS_CODE, errorResponse.getCode());
    Assertions.assertEquals(
        TagAlreadyAssociatedException.class.getSimpleName(), errorResponse.getType());
  }

  @Test
  public void testCreateSchemaWithTag() {
    // test create schema without tags
    SchemaWithTagsCreateRequest req =
        new SchemaWithTagsCreateRequest(
            "schema1", "comment", ImmutableMap.of("key", "value"), null);
    Schema schema = buildSchema("schema1", "comment", ImmutableMap.of("key", "value"));

    when(schemaDispatcher.createSchema(any(), any(), any())).thenReturn(schema);

    Response resp =
        target("/web/with-tags/metalakes/metalake1/catalogs/catalog1/schemas")
            .request(MediaType.APPLICATION_JSON_TYPE)
            .accept("application/vnd.gravitino.v1+json")
            .post(Entity.entity(req, MediaType.APPLICATION_JSON_TYPE));

    Assertions.assertEquals(Response.Status.OK.getStatusCode(), resp.getStatus());
    Assertions.assertEquals(MediaType.APPLICATION_JSON_TYPE, resp.getMediaType());

    SchemaWithTagsResponse schemaWithTagsResponse = resp.readEntity(SchemaWithTagsResponse.class);
    Assertions.assertEquals(0, schemaWithTagsResponse.getCode());

    Schema schemaDTO = schemaWithTagsResponse.getSchema();
    Assertions.assertEquals("schema1", schemaDTO.name());
    Assertions.assertEquals(0, schemaWithTagsResponse.getTags().length);

    // test create schema with tags
    String[] tags = new String[] {"tag1", "tag2"};
    req =
        new SchemaWithTagsCreateRequest(
            "schema2", "comment", ImmutableMap.of("key", "value"), tags);

    schema = buildSchema("schema2", "comment", ImmutableMap.of("key", "value"));
    when(schemaDispatcher.createSchema(any(), any(), any())).thenReturn(schema);
    when(tagDispatcher.associateTagsForMetadataObject(any(), any(), any(), any())).thenReturn(tags);

    resp =
        target("/web/with-tags/metalakes/metalake1/catalogs/catalog1/schemas")
            .request(MediaType.APPLICATION_JSON_TYPE)
            .accept("application/vnd.gravitino.v1+json")
            .post(Entity.entity(req, MediaType.APPLICATION_JSON_TYPE));

    Assertions.assertEquals(Response.Status.OK.getStatusCode(), resp.getStatus());
    Assertions.assertEquals(MediaType.APPLICATION_JSON_TYPE, resp.getMediaType());

    schemaWithTagsResponse = resp.readEntity(SchemaWithTagsResponse.class);
    Assertions.assertEquals(0, schemaWithTagsResponse.getCode());

    schemaDTO = schemaWithTagsResponse.getSchema();
    Assertions.assertEquals("schema2", schemaDTO.name());
    Assertions.assertEquals(2, schemaWithTagsResponse.getTags().length);
    Assertions.assertArrayEquals(tags, schemaWithTagsResponse.getTags());

    // test create schema error
    doThrow(new SchemaAlreadyExistsException("mock error"))
        .when(schemaDispatcher)
        .createSchema(any(), any(), any());
    Response errorResp =
        target("/web/with-tags/metalakes/metalake1/catalogs/catalog1/schemas")
            .request(MediaType.APPLICATION_JSON_TYPE)
            .accept("application/vnd.gravitino.v1+json")
            .post(Entity.entity(req, MediaType.APPLICATION_JSON_TYPE));

    Assertions.assertEquals(CONFLICT.getStatusCode(), errorResp.getStatus());

    ErrorResponse errorResponse = errorResp.readEntity(ErrorResponse.class);
    Assertions.assertEquals(ErrorConstants.ALREADY_EXISTS_CODE, errorResponse.getCode());
    Assertions.assertEquals(
        SchemaAlreadyExistsException.class.getSimpleName(), errorResponse.getType());

    // test tag association error
    schema = buildSchema("schema3", "comment", ImmutableMap.of("key", "value"));
    reset(schemaDispatcher);
    when(schemaDispatcher.createSchema(any(), any(), any())).thenReturn(schema);
    doThrow(new TagAlreadyAssociatedException("mock error"))
        .when(tagDispatcher)
        .associateTagsForMetadataObject(any(), any(), any(), any());
    errorResp =
        target("/web/with-tags/metalakes/metalake1/catalogs/catalog1/schemas")
            .request(MediaType.APPLICATION_JSON_TYPE)
            .accept("application/vnd.gravitino.v1+json")
            .post(Entity.entity(req, MediaType.APPLICATION_JSON_TYPE));

    Assertions.assertEquals(CONFLICT.getStatusCode(), errorResp.getStatus());
    errorResponse = errorResp.readEntity(ErrorResponse.class);
    Assertions.assertEquals(ErrorConstants.ALREADY_EXISTS_CODE, errorResponse.getCode());
    Assertions.assertEquals(
        TagAlreadyAssociatedException.class.getSimpleName(), errorResponse.getType());
  }

  @Test
  public void testCreateTableWithTag() {
    // test create table without tags
    Column[] columns =
        new Column[] {
          mockColumn("col1", Types.StringType.get()), mockColumn("col2", Types.ByteType.get())
        };
    Table table = mockTable("table1", columns);
    TableWithTagsCreateRequest req =
        new TableWithTagsCreateRequest(
            "table1",
            null,
            Arrays.stream(columns).map(DTOConverters::toDTO).toArray(ColumnDTO[]::new),
            null,
            null,
            null,
            null,
            null,
            null,
            null);
    when(tableDispatcher.createTable(any(), any(), any(), any(), any(), any(), any(), any()))
        .thenReturn(table);

    Response resp =
        target("/web/with-tags/metalakes/metalake1/catalogs/catalog1/schemas/schema1/tables")
            .request(MediaType.APPLICATION_JSON_TYPE)
            .accept("application/vnd.gravitino.v1+json")
            .post(Entity.entity(req, MediaType.APPLICATION_JSON_TYPE));

    Assertions.assertEquals(Response.Status.OK.getStatusCode(), resp.getStatus());
    Assertions.assertEquals(MediaType.APPLICATION_JSON_TYPE, resp.getMediaType());

    TableWithTagsResponse tableWithTagsResponse = resp.readEntity(TableWithTagsResponse.class);
    Assertions.assertEquals(0, tableWithTagsResponse.getCode());

    Table tableDTO = tableWithTagsResponse.getTable();
    Assertions.assertEquals("table1", tableDTO.name());
    Assertions.assertEquals(0, tableWithTagsResponse.getTags().length);
    Assertions.assertEquals(0, tableWithTagsResponse.getColumnTags().size());

    // test create table with tags
    String[] tags = new String[] {"tag1", "tag2"};
    String[] col1Tags = new String[] {"tag1"};
    String[] col2Tags = new String[] {"tag2"};
    Map<String, String[]> columnTags = ImmutableMap.of("col1", col1Tags, "col2", col2Tags);
    Table table2 = mockTable("table2", columns);
    req =
        new TableWithTagsCreateRequest(
            "table2",
            null,
            Arrays.stream(columns).map(DTOConverters::toDTO).toArray(ColumnDTO[]::new),
            null,
            null,
            null,
            null,
            null,
            tags,
            columnTags);

    when(tableDispatcher.createTable(any(), any(), any(), any(), any(), any(), any(), any()))
        .thenReturn(table2);
    when(tagDispatcher.associateTagsForMetadataObject(any(), any(), eq(tags), any()))
        .thenReturn(tags);
    when(tagDispatcher.associateTagsForMetadataObject(any(), any(), eq(col1Tags), any()))
        .thenReturn(col1Tags);
    when(tagDispatcher.associateTagsForMetadataObject(any(), any(), eq(col2Tags), any()))
        .thenReturn(col2Tags);

    resp =
        target("/web/with-tags/metalakes/metalake1/catalogs/catalog1/schemas/schema1/tables")
            .request(MediaType.APPLICATION_JSON_TYPE)
            .accept("application/vnd.gravitino.v1+json")
            .post(Entity.entity(req, MediaType.APPLICATION_JSON_TYPE));

    Assertions.assertEquals(Response.Status.OK.getStatusCode(), resp.getStatus());
    Assertions.assertEquals(MediaType.APPLICATION_JSON_TYPE, resp.getMediaType());

    tableWithTagsResponse = resp.readEntity(TableWithTagsResponse.class);
    Assertions.assertEquals(0, tableWithTagsResponse.getCode());

    tableDTO = tableWithTagsResponse.getTable();
    Assertions.assertEquals("table2", tableDTO.name());
    Assertions.assertArrayEquals(tags, tableWithTagsResponse.getTags());
    Assertions.assertArrayEquals(col1Tags, tableWithTagsResponse.getColumnTags().get("col1"));
    Assertions.assertArrayEquals(col2Tags, tableWithTagsResponse.getColumnTags().get("col2"));

    // test create table error
    doThrow(new TableAlreadyExistsException("mock error"))
        .when(tableDispatcher)
        .createTable(any(), any(), any(), any(), any(), any(), any(), any());
    Response errorResp =
        target("/web/with-tags/metalakes/metalake1/catalogs/catalog1/schemas/schema1/tables")
            .request(MediaType.APPLICATION_JSON_TYPE)
            .accept("application/vnd.gravitino.v1+json")
            .post(Entity.entity(req, MediaType.APPLICATION_JSON_TYPE));

    Assertions.assertEquals(CONFLICT.getStatusCode(), errorResp.getStatus());

    ErrorResponse errorResponse = errorResp.readEntity(ErrorResponse.class);
    Assertions.assertEquals(ErrorConstants.ALREADY_EXISTS_CODE, errorResponse.getCode());
    Assertions.assertEquals(
        TableAlreadyExistsException.class.getSimpleName(), errorResponse.getType());

    // test tag association error
    table2 = mockTable("table3", columns);
    reset(tableDispatcher);
    when(tableDispatcher.createTable(any(), any(), any(), any(), any(), any(), any(), any()))
        .thenReturn(table2);
    doThrow(new TagAlreadyAssociatedException("mock error"))
        .when(tagDispatcher)
        .associateTagsForMetadataObject(any(), any(), any(), any());
    errorResp =
        target("/web/with-tags/metalakes/metalake1/catalogs/catalog1/schemas/schema1/tables")
            .request(MediaType.APPLICATION_JSON_TYPE)
            .accept("application/vnd.gravitino.v1+json")
            .post(Entity.entity(req, MediaType.APPLICATION_JSON_TYPE));

    Assertions.assertEquals(CONFLICT.getStatusCode(), errorResp.getStatus());
    errorResponse = errorResp.readEntity(ErrorResponse.class);
    Assertions.assertEquals(ErrorConstants.ALREADY_EXISTS_CODE, errorResponse.getCode());
    Assertions.assertEquals(
        TagAlreadyAssociatedException.class.getSimpleName(), errorResponse.getType());
  }

  @Test
  public void testCreateFilesetWithTag() {
    // test create fileset without tags
    Fileset fileset =
        mockFileset(
            "fileset1",
            Fileset.Type.MANAGED,
            "mock comment",
            "mock location",
            ImmutableMap.of("k1", "v1"));
    when(filesetDispatcher.createMultipleLocationFileset(any(), any(), any(), any(), any()))
        .thenReturn(fileset);

    FilesetWithTagsCreateRequest req =
        new FilesetWithTagsCreateRequest("fileset1", null, null, "/tmp/1/", null, null);

    Response resp =
        target("/web/with-tags/metalakes/metalake1/catalogs/catalog1/schemas/schema1/filesets")
            .request(MediaType.APPLICATION_JSON_TYPE)
            .accept("application/vnd.gravitino.v1+json")
            .post(Entity.entity(req, MediaType.APPLICATION_JSON_TYPE));

    Assertions.assertEquals(Response.Status.OK.getStatusCode(), resp.getStatus());
    Assertions.assertEquals(MediaType.APPLICATION_JSON_TYPE, resp.getMediaType());

    FilesetWithTagsResponse filesetWithTagsResponse =
        resp.readEntity(FilesetWithTagsResponse.class);
    Assertions.assertEquals(0, filesetWithTagsResponse.getCode());

    Fileset filesetDTO = filesetWithTagsResponse.getFileset();
    Assertions.assertEquals("fileset1", filesetDTO.name());
    Assertions.assertEquals(0, filesetWithTagsResponse.getTags().length);

    // test create multiple locations fileset without tags
    Map<String, String> storageLocations =
        ImmutableMap.of("location1", "/tmp/1/", "location2", "/tmp/2/");
    fileset =
        mockMultipleLocationsFileset(
            "fileset1_1",
            Fileset.Type.MANAGED,
            "mock comment",
            storageLocations,
            ImmutableMap.of("k1", "v1"));
    when(filesetDispatcher.createMultipleLocationFileset(any(), any(), any(), any(), any()))
        .thenReturn(fileset);

    req =
        new FilesetWithTagsCreateRequest(
            "fileset1_1", "mock comment", Fileset.Type.MANAGED, null, storageLocations, null);

    resp =
        target("/web/with-tags/metalakes/metalake1/catalogs/catalog1/schemas/schema1/filesets")
            .request(MediaType.APPLICATION_JSON_TYPE)
            .accept("application/vnd.gravitino.v1+json")
            .post(Entity.entity(req, MediaType.APPLICATION_JSON_TYPE));

    Assertions.assertEquals(Response.Status.OK.getStatusCode(), resp.getStatus());
    Assertions.assertEquals(MediaType.APPLICATION_JSON_TYPE, resp.getMediaType());

    filesetWithTagsResponse = resp.readEntity(FilesetWithTagsResponse.class);
    Assertions.assertEquals(0, filesetWithTagsResponse.getCode());

    filesetDTO = filesetWithTagsResponse.getFileset();
    Assertions.assertEquals("fileset1_1", filesetDTO.name());
    Assertions.assertEquals(0, filesetWithTagsResponse.getTags().length);
    Assertions.assertEquals(storageLocations, filesetDTO.storageLocations());

    // test create fileset with tags
    String[] tags = new String[] {"tag1", "tag2"};
    req = new FilesetWithTagsCreateRequest("fileset2", null, null, null, null, tags);

    fileset =
        mockFileset(
            "fileset2",
            Fileset.Type.MANAGED,
            "mock com   ment",
            "mock location",
            ImmutableMap.of("k1", "v1"));
    when(filesetDispatcher.createMultipleLocationFileset(any(), any(), any(), any(), any()))
        .thenReturn(fileset);
    when(tagDispatcher.associateTagsForMetadataObject(any(), any(), eq(tags), any()))
        .thenReturn(tags);

    resp =
        target("/web/with-tags/metalakes/metalake1/catalogs/catalog1/schemas/schema1/filesets")
            .request(MediaType.APPLICATION_JSON_TYPE)
            .accept("application/vnd.gravitino.v1+json")
            .post(Entity.entity(req, MediaType.APPLICATION_JSON_TYPE));

    Assertions.assertEquals(Response.Status.OK.getStatusCode(), resp.getStatus());
    Assertions.assertEquals(MediaType.APPLICATION_JSON_TYPE, resp.getMediaType());

    filesetWithTagsResponse = resp.readEntity(FilesetWithTagsResponse.class);
    Assertions.assertEquals(0, filesetWithTagsResponse.getCode());

    filesetDTO = filesetWithTagsResponse.getFileset();
    Assertions.assertEquals("fileset2", filesetDTO.name());
    Assertions.assertArrayEquals(tags, filesetWithTagsResponse.getTags());

    // test create multiple locations fileset with tags
    fileset =
        mockMultipleLocationsFileset(
            "fileset2_1",
            Fileset.Type.MANAGED,
            "mock com   ment",
            storageLocations,
            ImmutableMap.of("k1", "v1"));
    when(filesetDispatcher.createMultipleLocationFileset(any(), any(), any(), any(), any()))
        .thenReturn(fileset);
    when(tagDispatcher.associateTagsForMetadataObject(any(), any(), eq(tags), any()))
        .thenReturn(tags);

    resp =
        target("/web/with-tags/metalakes/metalake1/catalogs/catalog1/schemas/schema1/filesets")
            .request(MediaType.APPLICATION_JSON_TYPE)
            .accept("application/vnd.gravitino.v1+json")
            .post(Entity.entity(req, MediaType.APPLICATION_JSON_TYPE));

    Assertions.assertEquals(Response.Status.OK.getStatusCode(), resp.getStatus());
    Assertions.assertEquals(MediaType.APPLICATION_JSON_TYPE, resp.getMediaType());

    filesetWithTagsResponse = resp.readEntity(FilesetWithTagsResponse.class);
    Assertions.assertEquals(0, filesetWithTagsResponse.getCode());

    filesetDTO = filesetWithTagsResponse.getFileset();
    Assertions.assertEquals("fileset2_1", filesetDTO.name());
    Assertions.assertArrayEquals(tags, filesetWithTagsResponse.getTags());
    Assertions.assertEquals(storageLocations, filesetDTO.storageLocations());

    // test create fileset error
    doThrow(new FilesetAlreadyExistsException("mock error"))
        .when(filesetDispatcher)
        .createMultipleLocationFileset(any(), any(), any(), any(), any());
    Response errorResp =
        target("/web/with-tags/metalakes/metalake1/catalogs/catalog1/schemas/schema1/filesets")
            .request(MediaType.APPLICATION_JSON_TYPE)
            .accept("application/vnd.gravitino.v1+json")
            .post(Entity.entity(req, MediaType.APPLICATION_JSON_TYPE));

    Assertions.assertEquals(CONFLICT.getStatusCode(), errorResp.getStatus());

    ErrorResponse errorResponse = errorResp.readEntity(ErrorResponse.class);
    Assertions.assertEquals(ErrorConstants.ALREADY_EXISTS_CODE, errorResponse.getCode());
    Assertions.assertEquals(
        FilesetAlreadyExistsException.class.getSimpleName(), errorResponse.getType());

    // test tag association error
    fileset =
        mockFileset(
            "fileset3",
            Fileset.Type.MANAGED,
            "mock com   ment",
            "mock location",
            ImmutableMap.of("k1", "v1"));
    reset(filesetDispatcher);
    when(filesetDispatcher.createMultipleLocationFileset(any(), any(), any(), any(), any()))
        .thenReturn(fileset);
    doThrow(new TagAlreadyAssociatedException("mock error"))
        .when(tagDispatcher)
        .associateTagsForMetadataObject(any(), any(), any(), any());
    errorResp =
        target("/web/with-tags/metalakes/metalake1/catalogs/catalog1/schemas/schema1/filesets")
            .request(MediaType.APPLICATION_JSON_TYPE)
            .accept("application/vnd.gravitino.v1+json")
            .post(Entity.entity(req, MediaType.APPLICATION_JSON_TYPE));

    Assertions.assertEquals(CONFLICT.getStatusCode(), errorResp.getStatus());
    errorResponse = errorResp.readEntity(ErrorResponse.class);
    Assertions.assertEquals(ErrorConstants.ALREADY_EXISTS_CODE, errorResponse.getCode());
    Assertions.assertEquals(
        TagAlreadyAssociatedException.class.getSimpleName(), errorResponse.getType());
  }

  @Test
  public void testCreateTopicWithTag() {
    // test create topic without tags
    Topic topic = mockTopic("topic1", "comment", null);
    when(topicDispatcher.createTopic(any(), any(), any(), any())).thenReturn(topic);

    TopicWithTagsCreateRequest req =
        new TopicWithTagsCreateRequest("topic1", "comment", null, null);
    Response resp =
        target("/web/with-tags/metalakes/metalake1/catalogs/catalog1/schemas/schema1/topics")
            .request(MediaType.APPLICATION_JSON_TYPE)
            .accept("application/vnd.gravitino.v1+json")
            .post(Entity.entity(req, MediaType.APPLICATION_JSON_TYPE));

    Assertions.assertEquals(Response.Status.OK.getStatusCode(), resp.getStatus());
    Assertions.assertEquals(MediaType.APPLICATION_JSON_TYPE, resp.getMediaType());

    TopicWithTagsResponse topicWithTagsResponse = resp.readEntity(TopicWithTagsResponse.class);
    Assertions.assertEquals(0, topicWithTagsResponse.getCode());

    Topic topicDTO = topicWithTagsResponse.getTopic();
    Assertions.assertEquals("topic1", topicDTO.name());
    Assertions.assertEquals(0, topicWithTagsResponse.getTags().length);

    // test create topic with tags
    String[] tags = new String[] {"tag1", "tag2"};
    req = new TopicWithTagsCreateRequest("topic2", "comment", null, tags);

    topic = mockTopic("topic2", "comment", null);
    when(topicDispatcher.createTopic(any(), any(), any(), any())).thenReturn(topic);
    when(tagDispatcher.associateTagsForMetadataObject(any(), any(), eq(tags), any()))
        .thenReturn(tags);

    resp =
        target("/web/with-tags/metalakes/metalake1/catalogs/catalog1/schemas/schema1/topics")
            .request(MediaType.APPLICATION_JSON_TYPE)
            .accept("application/vnd.gravitino.v1+json")
            .post(Entity.entity(req, MediaType.APPLICATION_JSON_TYPE));

    Assertions.assertEquals(Response.Status.OK.getStatusCode(), resp.getStatus());
    Assertions.assertEquals(MediaType.APPLICATION_JSON_TYPE, resp.getMediaType());

    topicWithTagsResponse = resp.readEntity(TopicWithTagsResponse.class);
    Assertions.assertEquals(0, topicWithTagsResponse.getCode());

    topicDTO = topicWithTagsResponse.getTopic();
    Assertions.assertEquals("topic2", topicDTO.name());
    Assertions.assertArrayEquals(tags, topicWithTagsResponse.getTags());

    // test create topic error
    doThrow(new TopicAlreadyExistsException("mock error"))
        .when(topicDispatcher)
        .createTopic(any(), any(), any(), any());
    Response errorResp =
        target("/web/with-tags/metalakes/metalake1/catalogs/catalog1/schemas/schema1/topics")
            .request(MediaType.APPLICATION_JSON_TYPE)
            .accept("application/vnd.gravitino.v1+json")
            .post(Entity.entity(req, MediaType.APPLICATION_JSON_TYPE));

    Assertions.assertEquals(CONFLICT.getStatusCode(), errorResp.getStatus());

    ErrorResponse errorResponse = errorResp.readEntity(ErrorResponse.class);
    Assertions.assertEquals(ErrorConstants.ALREADY_EXISTS_CODE, errorResponse.getCode());
    Assertions.assertEquals(
        TopicAlreadyExistsException.class.getSimpleName(), errorResponse.getType());

    // test tag association error
    topic = mockTopic("topic3", "comment", null);
    reset(topicDispatcher);
    when(topicDispatcher.createTopic(any(), any(), any(), any())).thenReturn(topic);
    doThrow(new TagAlreadyAssociatedException("mock error"))
        .when(tagDispatcher)
        .associateTagsForMetadataObject(any(), any(), any(), any());
    errorResp =
        target("/web/with-tags/metalakes/metalake1/catalogs/catalog1/schemas/schema1/topics")
            .request(MediaType.APPLICATION_JSON_TYPE)
            .accept("application/vnd.gravitino.v1+json")
            .post(Entity.entity(req, MediaType.APPLICATION_JSON_TYPE));

    Assertions.assertEquals(CONFLICT.getStatusCode(), errorResp.getStatus());
    errorResponse = errorResp.readEntity(ErrorResponse.class);
    Assertions.assertEquals(ErrorConstants.ALREADY_EXISTS_CODE, errorResponse.getCode());
    Assertions.assertEquals(
        TagAlreadyAssociatedException.class.getSimpleName(), errorResponse.getType());
  }

  @Test
  public void testRegisterModelWithTag() {
    // test create model without tags
    Model model = mockModel("model1", "comment", null);
    when(modelDispatcher.registerModel(any(), any(), any())).thenReturn(model);

    ModelWithTagsCreateRequest req =
        new ModelWithTagsCreateRequest("model1", "comment", null, null);

    Response resp =
        target("/web/with-tags/metalakes/metalake1/catalogs/catalog1/schemas/schema1/models")
            .request(MediaType.APPLICATION_JSON_TYPE)
            .accept("application/vnd.gravitino.v1+json")
            .post(Entity.entity(req, MediaType.APPLICATION_JSON_TYPE));

    Assertions.assertEquals(Response.Status.OK.getStatusCode(), resp.getStatus());
    Assertions.assertEquals(MediaType.APPLICATION_JSON_TYPE, resp.getMediaType());

    ModelWithTagsResponse modelWithTagsResponse = resp.readEntity(ModelWithTagsResponse.class);
    Assertions.assertEquals(0, modelWithTagsResponse.getCode());

    Model modelDTO = modelWithTagsResponse.getModel();
    Assertions.assertEquals("model1", modelDTO.name());
    Assertions.assertEquals(0, modelWithTagsResponse.getTags().length);

    // test create model with tags
    String[] tags = new String[] {"tag1", "tag2"};
    req = new ModelWithTagsCreateRequest("model2", "comment", null, tags);

    model = mockModel("model2", "comment", null);
    when(modelDispatcher.registerModel(any(), any(), any())).thenReturn(model);
    when(tagDispatcher.associateTagsForMetadataObject(any(), any(), eq(tags), any()))
        .thenReturn(tags);

    resp =
        target("/web/with-tags/metalakes/metalake1/catalogs/catalog1/schemas/schema1/models")
            .request(MediaType.APPLICATION_JSON_TYPE)
            .accept("application/vnd.gravitino.v1+json")
            .post(Entity.entity(req, MediaType.APPLICATION_JSON_TYPE));

    Assertions.assertEquals(Response.Status.OK.getStatusCode(), resp.getStatus());
    Assertions.assertEquals(MediaType.APPLICATION_JSON_TYPE, resp.getMediaType());

    modelWithTagsResponse = resp.readEntity(ModelWithTagsResponse.class);
    Assertions.assertEquals(0, modelWithTagsResponse.getCode());

    modelDTO = modelWithTagsResponse.getModel();
    Assertions.assertEquals("model2", modelDTO.name());
    Assertions.assertArrayEquals(tags, modelWithTagsResponse.getTags());

    // test create model error
    doThrow(new ModelAlreadyExistsException("mock error"))
        .when(modelDispatcher)
        .registerModel(any(), any(), any());
    Response errorResp =
        target("/web/with-tags/metalakes/metalake1/catalogs/catalog1/schemas/schema1/models")
            .request(MediaType.APPLICATION_JSON_TYPE)
            .accept("application/vnd.gravitino.v1+json")
            .post(Entity.entity(req, MediaType.APPLICATION_JSON_TYPE));

    Assertions.assertEquals(CONFLICT.getStatusCode(), errorResp.getStatus());

    ErrorResponse errorResponse = errorResp.readEntity(ErrorResponse.class);
    Assertions.assertEquals(ErrorConstants.ALREADY_EXISTS_CODE, errorResponse.getCode());
    Assertions.assertEquals(
        ModelAlreadyExistsException.class.getSimpleName(), errorResponse.getType());

    // test tag association error
    model = mockModel("model3", "comment", null);
    reset(modelDispatcher);
    when(modelDispatcher.registerModel(any(), any(), any())).thenReturn(model);
    doThrow(new TagAlreadyAssociatedException("mock error"))
        .when(tagDispatcher)
        .associateTagsForMetadataObject(any(), any(), any(), any());
    errorResp =
        target("/web/with-tags/metalakes/metalake1/catalogs/catalog1/schemas/schema1/models")
            .request(MediaType.APPLICATION_JSON_TYPE)
            .accept("application/vnd.gravitino.v1+json")
            .post(Entity.entity(req, MediaType.APPLICATION_JSON_TYPE));

    Assertions.assertEquals(CONFLICT.getStatusCode(), errorResp.getStatus());
    errorResponse = errorResp.readEntity(ErrorResponse.class);
    Assertions.assertEquals(ErrorConstants.ALREADY_EXISTS_CODE, errorResponse.getCode());
    Assertions.assertEquals(
        TagAlreadyAssociatedException.class.getSimpleName(), errorResponse.getType());
  }

  private Model mockModel(String name, String comment, Map<String, String> properties) {
    Model mockedModel = mock(Model.class);
    when(mockedModel.name()).thenReturn(name);
    when(mockedModel.comment()).thenReturn(comment);
    when(mockedModel.properties()).thenReturn(properties);

    Audit mockAudit = mock(Audit.class);
    when(mockAudit.creator()).thenReturn("gravitino");
    when(mockAudit.createTime()).thenReturn(Instant.now());
    when(mockedModel.auditInfo()).thenReturn(mockAudit);

    return mockedModel;
  }

  private Topic mockTopic(String name, String comment, Map<String, String> properties) {
    Topic mockedTopic = mock(Topic.class);
    when(mockedTopic.name()).thenReturn(name);
    when(mockedTopic.comment()).thenReturn(comment);
    when(mockedTopic.properties()).thenReturn(properties);

    Audit mockAudit = mock(Audit.class);
    when(mockAudit.creator()).thenReturn("gravitino");
    when(mockAudit.createTime()).thenReturn(Instant.now());
    when(mockedTopic.auditInfo()).thenReturn(mockAudit);

    return mockedTopic;
  }

  private Fileset mockFileset(
      String filesetName,
      Fileset.Type type,
      String comment,
      String storageLocation,
      Map<String, String> properties) {
    Fileset mockFileset = mock(Fileset.class);
    when(mockFileset.name()).thenReturn(filesetName);
    when(mockFileset.type()).thenReturn(type);
    when(mockFileset.comment()).thenReturn(comment);
    when(mockFileset.storageLocation()).thenReturn(storageLocation);
    when(mockFileset.properties()).thenReturn(properties);
    when(mockFileset.storageLocations()).thenReturn(ImmutableMap.of("unknown", storageLocation));

    Audit mockAudit = mock(Audit.class);
    when(mockAudit.creator()).thenReturn("gravitino");
    when(mockAudit.createTime()).thenReturn(Instant.now());
    when(mockFileset.auditInfo()).thenReturn(mockAudit);

    return mockFileset;
  }

  private Fileset mockMultipleLocationsFileset(
      String filesetName,
      Fileset.Type type,
      String comment,
      Map<String, String> storageLocations,
      Map<String, String> properties) {
    Fileset mockFileset = mock(Fileset.class);
    when(mockFileset.name()).thenReturn(filesetName);
    when(mockFileset.type()).thenReturn(type);
    when(mockFileset.comment()).thenReturn(comment);
    when(mockFileset.properties()).thenReturn(properties);
    when(mockFileset.storageLocations()).thenReturn(storageLocations);

    Audit mockAudit = mock(Audit.class);
    when(mockAudit.creator()).thenReturn("gravitino");
    when(mockAudit.createTime()).thenReturn(Instant.now());
    when(mockFileset.auditInfo()).thenReturn(mockAudit);

    return mockFileset;
  }

  private Table mockTable(String tableName, Column[] columns) {
    Table table = mock(Table.class);
    when(table.name()).thenReturn(tableName);
    when(table.columns()).thenReturn(columns);
    when(table.comment()).thenReturn(null);
    when(table.properties()).thenReturn(null);
    when(table.partitioning()).thenReturn(null);
    when(table.sortOrder()).thenReturn(null);
    when(table.distribution()).thenReturn(null);
    when(table.index()).thenReturn(null);

    Audit mockAudit = mock(Audit.class);
    when(mockAudit.creator()).thenReturn("gravitino");
    when(mockAudit.createTime()).thenReturn(Instant.now());
    when(table.auditInfo()).thenReturn(mockAudit);

    return table;
  }

  private Column mockColumn(String name, Type type) {
    Column column = mock(Column.class);
    when(column.name()).thenReturn(name);
    when(column.dataType()).thenReturn(type);
    when(column.comment()).thenReturn(null);
    when(column.nullable()).thenReturn(true);
    return column;
  }

  private Schema buildSchema(String name, String comment, Map<String, String> properties) {
    Schema mockSchema = mock(Schema.class);
    when(mockSchema.name()).thenReturn(name);
    when(mockSchema.comment()).thenReturn(comment);
    when(mockSchema.properties()).thenReturn(properties);

    Audit mockAudit = mock(Audit.class);
    when(mockAudit.creator()).thenReturn("gravitino");
    when(mockAudit.createTime()).thenReturn(Instant.now());
    when(mockSchema.auditInfo()).thenReturn(mockAudit);

    return mockSchema;
  }

  private static TestCatalog buildCatalog(String metalake, String catalogName) {
    CatalogEntity entity =
        CatalogEntity.builder()
            .withId(1L)
            .withName(catalogName)
            .withComment("comment")
            .withNamespace(Namespace.of(metalake))
            .withProperties(ImmutableMap.of("key", "value"))
            .withType(Catalog.Type.RELATIONAL)
            .withProvider("test")
            .withAuditInfo(
                AuditInfo.builder().withCreator("creator").withCreateTime(Instant.now()).build())
            .build();

    return new TestCatalog().withCatalogConf(Collections.emptyMap()).withCatalogEntity(entity);
  }
}
