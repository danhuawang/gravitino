/*
 * Copyright 2024 Datastrato Pvt Ltd.
 * This software is licensed under the Apache License version 2.
 */
package com.datastrato.gravitino.server.web.rest;

import static org.apache.gravitino.Configs.TREE_LOCK_CLEAN_INTERVAL;
import static org.apache.gravitino.Configs.TREE_LOCK_MAX_NODE_IN_MEMORY;
import static org.apache.gravitino.Configs.TREE_LOCK_MIN_NODE_IN_MEMORY;
import static org.apache.gravitino.file.Fileset.LOCATION_NAME_UNKNOWN;
import static org.apache.gravitino.file.Fileset.Type.MANAGED;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.datastrato.gravitino.catalog.DatastratoFilesetDispatcher;
import com.datastrato.gravitino.catalog.DatastratoModelDispatcher;
import com.datastrato.gravitino.catalog.DatastratoSchemaOperationDispatcher;
import com.datastrato.gravitino.catalog.DatastratoTableDispatcher;
import com.datastrato.gravitino.catalog.DatastratoTopicDispatcher;
import com.datastrato.gravitino.catalog.DatastratoViewDispatcher;
import com.datastrato.gravitino.dto.responses.FilesetListResponse;
import com.datastrato.gravitino.dto.responses.ModelListResponse;
import com.datastrato.gravitino.dto.responses.SchemaListResponse;
import com.datastrato.gravitino.dto.responses.TableListResponse;
import com.datastrato.gravitino.dto.responses.TopicListResponse;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import java.io.IOException;
import java.time.Instant;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.stream.Collectors;
import javax.servlet.http.HttpServletRequest;
import javax.ws.rs.core.Application;
import javax.ws.rs.core.MediaType;
import javax.ws.rs.core.Response;
import org.apache.commons.lang3.reflect.FieldUtils;
import org.apache.gravitino.Catalog;
import org.apache.gravitino.Config;
import org.apache.gravitino.GravitinoEnv;
import org.apache.gravitino.NameIdentifier;
import org.apache.gravitino.Namespace;
import org.apache.gravitino.catalog.CatalogDispatcher;
import org.apache.gravitino.catalog.CatalogManager;
import org.apache.gravitino.catalog.FilesetDispatcher;
import org.apache.gravitino.catalog.FunctionDispatcher;
import org.apache.gravitino.catalog.ModelDispatcher;
import org.apache.gravitino.catalog.SchemaDispatcher;
import org.apache.gravitino.catalog.TableDispatcher;
import org.apache.gravitino.catalog.TopicDispatcher;
import org.apache.gravitino.catalog.ViewDispatcher;
import org.apache.gravitino.dto.CatalogDTO;
import org.apache.gravitino.dto.SchemaDTO;
import org.apache.gravitino.dto.file.FilesetDTO;
import org.apache.gravitino.dto.function.FunctionDTO;
import org.apache.gravitino.dto.messaging.TopicDTO;
import org.apache.gravitino.dto.model.ModelDTO;
import org.apache.gravitino.dto.rel.TableDTO;
import org.apache.gravitino.dto.rel.ViewDTO;
import org.apache.gravitino.dto.responses.CatalogListResponse;
import org.apache.gravitino.dto.responses.ErrorConstants;
import org.apache.gravitino.dto.responses.ErrorResponse;
import org.apache.gravitino.exceptions.NoSuchSchemaException;
import org.apache.gravitino.function.Function;
import org.apache.gravitino.function.FunctionType;
import org.apache.gravitino.lock.LockManager;
import org.apache.gravitino.meta.AuditInfo;
import org.apache.gravitino.meta.CatalogEntity;
import org.apache.gravitino.meta.FilesetEntity;
import org.apache.gravitino.meta.FunctionEntity;
import org.apache.gravitino.meta.ModelEntity;
import org.apache.gravitino.meta.SchemaEntity;
import org.apache.gravitino.meta.TableEntity;
import org.apache.gravitino.meta.TopicEntity;
import org.apache.gravitino.meta.ViewEntity;
import org.apache.gravitino.rel.Column;
import org.apache.gravitino.rel.Representation;
import org.apache.gravitino.rel.SQLRepresentation;
import org.apache.gravitino.rel.types.Types;
import org.apache.gravitino.rest.RESTUtils;
import org.glassfish.hk2.utilities.binding.AbstractBinder;
import org.glassfish.jersey.server.ResourceConfig;
import org.glassfish.jersey.test.JerseyTest;
import org.glassfish.jersey.test.TestProperties;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

public class TestEntityOperations extends JerseyTest {
  private static class MockServletRequestFactory extends ServletRequestFactoryBase {
    @Override
    public HttpServletRequest get() {
      HttpServletRequest request = mock(HttpServletRequest.class);
      when(request.getRemoteUser()).thenReturn(null);
      return request;
    }
  }

  private final CatalogDispatcher catalogDispatcher = mock(CatalogManager.class);
  private final DatastratoSchemaOperationDispatcher schemaDispatcher =
      mock(DatastratoSchemaOperationDispatcher.class);
  private final DatastratoTableDispatcher tableDispatcher = mock(DatastratoTableDispatcher.class);
  private final DatastratoFilesetDispatcher filesetDispatcher =
      mock(DatastratoFilesetDispatcher.class);
  private final DatastratoTopicDispatcher topicDispatcher = mock(DatastratoTopicDispatcher.class);
  private final DatastratoModelDispatcher modelDispatcher = mock(DatastratoModelDispatcher.class);
  private final FunctionDispatcher functionDispatcher = mock(FunctionDispatcher.class);
  private final DatastratoViewDispatcher viewDispatcher = mock(DatastratoViewDispatcher.class);

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
    resourceConfig.register(EntityOperations.class);
    resourceConfig.register(
        new AbstractBinder() {
          @Override
          protected void configure() {
            bind(catalogDispatcher).to(CatalogDispatcher.class).ranked(2);
            bind(schemaDispatcher).to(SchemaDispatcher.class).ranked(2);
            bind(tableDispatcher).to(TableDispatcher.class).ranked(2);
            bind(topicDispatcher).to(TopicDispatcher.class).ranked(2);
            bind(filesetDispatcher).to(FilesetDispatcher.class).ranked(2);
            bind(modelDispatcher).to(ModelDispatcher.class).ranked(2);
            bind(functionDispatcher).to(FunctionDispatcher.class).ranked(2);
            bind(viewDispatcher).to(ViewDispatcher.class).ranked(2);
            bindFactory(TestEntityOperations.MockServletRequestFactory.class)
                .to(HttpServletRequest.class);
          }
        });

    return resourceConfig;
  }

  @Test
  public void testListEntitiesException() {
    // test namespace error
    Response resp =
        target("/web/entities")
            .request(MediaType.APPLICATION_JSON_TYPE)
            .accept("application/vnd.gravitino.v1+json")
            .get();

    Assertions.assertEquals(Response.Status.BAD_REQUEST.getStatusCode(), resp.getStatus());
    Assertions.assertEquals(MediaType.APPLICATION_JSON_TYPE, resp.getMediaType());

    ErrorResponse errorResp = resp.readEntity(ErrorResponse.class);
    Assertions.assertEquals(ErrorConstants.ILLEGAL_ARGUMENTS_CODE, errorResp.getCode());
    Assertions.assertEquals(IllegalArgumentException.class.getSimpleName(), errorResp.getType());
    Assertions.assertEquals("Query param namespace cannot be empty", errorResp.getMessage());

    // test namespace does not exist
    doThrow(new NoSuchSchemaException("Schema metalake.catalog.schema does not exist"))
        .when(tableDispatcher)
        .listTables(any());
    resp =
        target("/web/entities")
            .queryParam("namespace", "metalake.catalog.schema")
            .queryParam("catalogType", "relational")
            .request(MediaType.APPLICATION_JSON_TYPE)
            .accept("application/vnd.gravitino.v1+json")
            .get();
    Assertions.assertEquals(Response.Status.NOT_FOUND.getStatusCode(), resp.getStatus());
    Assertions.assertEquals(MediaType.APPLICATION_JSON_TYPE, resp.getMediaType());

    errorResp = resp.readEntity(ErrorResponse.class);
    Assertions.assertEquals(ErrorConstants.NOT_FOUND_CODE, errorResp.getCode());
    Assertions.assertEquals(NoSuchSchemaException.class.getSimpleName(), errorResp.getType());
    Assertions.assertTrue(
        errorResp.getMessage().contains("Schema metalake.catalog.schema does not exist"),
        errorResp.getMessage());

    // test catalogType error
    resp =
        target("/web/entities")
            .queryParam("namespace", "testMetalake.relCatalog")
            .request(MediaType.APPLICATION_JSON_TYPE)
            .accept("application/vnd.gravitino.v1+json")
            .get();
    Assertions.assertEquals(Response.Status.BAD_REQUEST.getStatusCode(), resp.getStatus());
    Assertions.assertEquals(MediaType.APPLICATION_JSON_TYPE, resp.getMediaType());

    errorResp = resp.readEntity(ErrorResponse.class);
    Assertions.assertEquals(ErrorConstants.ILLEGAL_ARGUMENTS_CODE, errorResp.getCode());
    Assertions.assertEquals(IllegalArgumentException.class.getSimpleName(), errorResp.getType());
    Assertions.assertEquals("Query param catalogType cannot be empty", errorResp.getMessage());

    // test result limit error
    resp =
        target("/web/entities")
            .queryParam("namespace", "testMetalake")
            .queryParam("catalogType", "relational")
            .queryParam("resultLimit", "0")
            .request(MediaType.APPLICATION_JSON_TYPE)
            .accept("application/vnd.gravitino.v1+json")
            .get();
    Assertions.assertEquals(Response.Status.BAD_REQUEST.getStatusCode(), resp.getStatus());
    Assertions.assertEquals(MediaType.APPLICATION_JSON_TYPE, resp.getMediaType());

    errorResp = resp.readEntity(ErrorResponse.class);
    Assertions.assertEquals(ErrorConstants.ILLEGAL_ARGUMENTS_CODE, errorResp.getCode());
    Assertions.assertEquals(IllegalArgumentException.class.getSimpleName(), errorResp.getType());
    Assertions.assertEquals("Result limit should be greater than 0", errorResp.getMessage());
  }

  @Test
  public void testListEntities() {
    // test list catalogs
    TestCatalog catalog1 = buildCatalog("testMetalake", "relCatalog1");
    TestCatalog catalog2 = buildCatalog("testMetalake", "relCatalog2");
    Catalog[] mockedCatalogs = {catalog1, catalog2};
    when(catalogDispatcher.listCatalogsInfo(Namespace.of("testMetalake")))
        .thenReturn(mockedCatalogs);

    Response resp =
        target("/web/entities")
            .queryParam("namespace", "testMetalake")
            .queryParam("catalogType", "relational")
            .request(MediaType.APPLICATION_JSON_TYPE)
            .accept("application/vnd.gravitino.v1+json")
            .get();
    Assertions.assertEquals(Response.Status.OK.getStatusCode(), resp.getStatus());
    Assertions.assertEquals(MediaType.APPLICATION_JSON_TYPE, resp.getMediaType());

    CatalogListResponse catalogResponse = resp.readEntity(CatalogListResponse.class);
    Assertions.assertEquals(0, catalogResponse.getCode());

    CatalogDTO[] catalogDTOs = catalogResponse.getCatalogs();
    Assertions.assertEquals(2, catalogDTOs.length);
    assertCatalogs(catalogDTOs);

    // test list all catalogs
    when(catalogDispatcher.listCatalogsInfo(Namespace.of("testMetalake")))
        .thenReturn(mockedCatalogs);

    resp =
        target("/web/entities")
            .queryParam("namespace", "testMetalake")
            .request(MediaType.APPLICATION_JSON_TYPE)
            .accept("application/vnd.gravitino.v1+json")
            .get();
    Assertions.assertEquals(Response.Status.OK.getStatusCode(), resp.getStatus());
    Assertions.assertEquals(MediaType.APPLICATION_JSON_TYPE, resp.getMediaType());
    catalogResponse = resp.readEntity(CatalogListResponse.class);
    Assertions.assertEquals(0, catalogResponse.getCode());

    catalogDTOs = catalogResponse.getCatalogs();
    Assertions.assertEquals(2, catalogDTOs.length);
    assertCatalogs(catalogDTOs);

    // test list schemas
    Namespace namespace = Namespace.of("testMetalake", "relCatalog");
    NameIdentifier schemaIdent = NameIdentifier.of(namespace, "relSchema");
    NameIdentifier[] schemaIdents = {schemaIdent};
    when(schemaDispatcher.listSchemas(namespace)).thenReturn(schemaIdents);
    when(schemaDispatcher.listEntities(namespace)).thenReturn(buildSchemaEntity(schemaIdents));

    resp =
        target("/web/entities")
            .queryParam("namespace", "testMetalake.relCatalog")
            .queryParam("catalogType", "relational")
            .request(MediaType.APPLICATION_JSON_TYPE)
            .accept("application/vnd.gravitino.v1+json")
            .get();
    Assertions.assertEquals(Response.Status.OK.getStatusCode(), resp.getStatus());
    Assertions.assertEquals(MediaType.APPLICATION_JSON_TYPE, resp.getMediaType());

    SchemaListResponse schemaResp = resp.readEntity(SchemaListResponse.class);
    Assertions.assertEquals(0, schemaResp.getCode());
    Assertions.assertEquals(1, schemaResp.getSchemas().length);
    assertSchemas(schemaResp.getSchemas());

    // test list tables
    namespace = Namespace.of("testMetalake", "relCatalog", "relSchema");
    NameIdentifier tableIdent = NameIdentifier.of(namespace, "relTable");
    NameIdentifier[] tableIdents = {tableIdent};
    when(tableDispatcher.listTables(namespace)).thenReturn(tableIdents);
    when(tableDispatcher.listEntities(namespace)).thenReturn(buildTableEntity(tableIdents));
    when(functionDispatcher.listFunctionInfos(namespace)).thenReturn(buildFunctionInfos(namespace));
    mockViews(namespace);

    resp =
        target("/web/entities")
            .queryParam("namespace", "testMetalake.relCatalog.relSchema")
            .queryParam("catalogType", "relational")
            .request(MediaType.APPLICATION_JSON_TYPE)
            .accept("application/vnd.gravitino.v1+json")
            .get();
    Assertions.assertEquals(Response.Status.OK.getStatusCode(), resp.getStatus());
    Assertions.assertEquals(MediaType.APPLICATION_JSON_TYPE, resp.getMediaType());

    TableListResponse tableResp = resp.readEntity(TableListResponse.class);
    Assertions.assertEquals(0, tableResp.getCode());
    Assertions.assertEquals(1, tableResp.getTables().length);
    assertTables(tableResp.getTables());
    Assertions.assertEquals(1, tableResp.getFunctions().length);
    assertFunctions(tableResp.getFunctions());
    Assertions.assertEquals(1, tableResp.getViews().length);
    assertViews(tableResp.getViews());

    // test list tables with schema not found in store
    doThrow(new NoSuchSchemaException("Schema testMetalake.relCatalog.relSchema does not exist"))
        .when(tableDispatcher)
        .listEntities(any());
    resp =
        target("/web/entities")
            .queryParam("namespace", "testMetalake.relCatalog.relSchema")
            .queryParam("catalogType", "relational")
            .request(MediaType.APPLICATION_JSON_TYPE)
            .accept("application/vnd.gravitino.v1+json")
            .get();
    Assertions.assertEquals(Response.Status.OK.getStatusCode(), resp.getStatus());
    Assertions.assertEquals(MediaType.APPLICATION_JSON_TYPE, resp.getMediaType());

    tableResp = resp.readEntity(TableListResponse.class);
    Assertions.assertEquals(0, tableResp.getCode());
    Assertions.assertEquals(1, tableResp.getTables().length);
    TableDTO tableDTO = tableResp.getTables()[0];
    Assertions.assertEquals("relTable", tableDTO.name());
    Assertions.assertNull(tableDTO.comment());
    Assertions.assertNull(tableDTO.properties());
    Assertions.assertNull(tableDTO.auditInfo().creator());
    Assertions.assertEquals(1, tableResp.getViews().length);
    assertViews(tableResp.getViews());

    // test list tables with schema not found in store for views.
    doThrow(new NoSuchSchemaException("Schema testMetalake.relCatalog.relSchema does not exist"))
        .when(viewDispatcher)
        .listEntities(namespace);

    resp =
        target("/web/entities")
            .queryParam("namespace", "testMetalake.relCatalog.relSchema")
            .queryParam("catalogType", "relational")
            .request(MediaType.APPLICATION_JSON_TYPE)
            .accept("application/vnd.gravitino.v1+json")
            .get();
    Assertions.assertEquals(Response.Status.OK.getStatusCode(), resp.getStatus());

    tableResp = resp.readEntity(TableListResponse.class);
    Assertions.assertEquals(1, tableResp.getViews().length);
    ViewDTO fallbackView = tableResp.getViews()[0];
    Assertions.assertEquals("testView", fallbackView.name());
    Assertions.assertNull(fallbackView.comment());
    Assertions.assertNull(fallbackView.auditInfo().creator());
    Assertions.assertEquals(1, fallbackView.representations().length);
    Assertions.assertInstanceOf(SQLRepresentation.class, fallbackView.representations()[0]);
    SQLRepresentation representation = (SQLRepresentation) fallbackView.representations()[0];
    Assertions.assertEquals("unavailable", representation.dialect());
    Assertions.assertEquals("UNAVAILABLE", representation.sql());

    // test list topics
    namespace = Namespace.of("testMetalake", "messagingCatalog", "messagingSchema");
    NameIdentifier topicIdent = NameIdentifier.of(namespace, "messagingTopic");
    NameIdentifier[] topicIdents = {topicIdent};
    when(topicDispatcher.listTopics(namespace)).thenReturn(topicIdents);
    when(topicDispatcher.listEntities(namespace)).thenReturn(buildTopicEntity(topicIdents));
    when(functionDispatcher.listFunctionInfos(namespace)).thenReturn(buildFunctionInfos(namespace));

    resp =
        target("/web/entities")
            .queryParam("namespace", "testMetalake.messagingCatalog.messagingSchema")
            .queryParam("catalogType", "messaging")
            .request(MediaType.APPLICATION_JSON_TYPE)
            .accept("application/vnd.gravitino.v1+json")
            .get();
    Assertions.assertEquals(Response.Status.OK.getStatusCode(), resp.getStatus());
    Assertions.assertEquals(MediaType.APPLICATION_JSON_TYPE, resp.getMediaType());

    TopicListResponse topicResp = resp.readEntity(TopicListResponse.class);
    Assertions.assertEquals(0, topicResp.getCode());
    Assertions.assertEquals(1, topicResp.getTopics().length);
    assertTopics(topicResp.getTopics());
    Assertions.assertEquals(1, topicResp.getFunctions().length);
    assertFunctions(topicResp.getFunctions());

    // test list topics with schema not found in store
    doThrow(
            new NoSuchSchemaException(
                "Schema testMetalake.messagingCatalog.messagingSchema does not exist"))
        .when(topicDispatcher)
        .listEntities(any());
    resp =
        target("/web/entities")
            .queryParam("namespace", "testMetalake.messagingCatalog.messagingSchema")
            .queryParam("catalogType", "messaging")
            .request(MediaType.APPLICATION_JSON_TYPE)
            .accept("application/vnd.gravitino.v1+json")
            .get();
    Assertions.assertEquals(Response.Status.OK.getStatusCode(), resp.getStatus());
    Assertions.assertEquals(MediaType.APPLICATION_JSON_TYPE, resp.getMediaType());

    topicResp = resp.readEntity(TopicListResponse.class);
    Assertions.assertEquals(0, topicResp.getCode());
    Assertions.assertEquals(1, topicResp.getTopics().length);
    TopicDTO topicDTO = topicResp.getTopics()[0];
    Assertions.assertEquals("messagingTopic", topicDTO.name());
    Assertions.assertNull(topicDTO.comment());
    Assertions.assertNull(topicDTO.properties());
    Assertions.assertNull(topicDTO.auditInfo().creator());

    // test list filesets
    namespace = Namespace.of("testMetalake", "filesetCatalog", "filesetSchema");
    NameIdentifier filesetIdent = NameIdentifier.of(namespace, "fileset");
    NameIdentifier[] filesetIdents = {filesetIdent};
    when(filesetDispatcher.listEntities(namespace)).thenReturn(buildFilesetEntity(filesetIdents));
    when(functionDispatcher.listFunctionInfos(namespace)).thenReturn(buildFunctionInfos(namespace));

    resp =
        target("/web/entities")
            .queryParam("namespace", "testMetalake.filesetCatalog.filesetSchema")
            .queryParam("catalogType", "fileset")
            .request(MediaType.APPLICATION_JSON_TYPE)
            .accept("application/vnd.gravitino.v1+json")
            .get();
    Assertions.assertEquals(Response.Status.OK.getStatusCode(), resp.getStatus());
    Assertions.assertEquals(MediaType.APPLICATION_JSON_TYPE, resp.getMediaType());

    FilesetListResponse filesetResp = resp.readEntity(FilesetListResponse.class);
    Assertions.assertEquals(0, filesetResp.getCode());
    Assertions.assertEquals(1, filesetResp.getFilesets().length);
    assertFilesets(filesetResp.getFilesets());
    Assertions.assertEquals(1, filesetResp.getFunctions().length);
    assertFunctions(filesetResp.getFunctions());

    // test list models
    Namespace modelNamespace = Namespace.of("testMetalake", "modelCatalog", "modelSchema");
    NameIdentifier modelIdent = NameIdentifier.of(modelNamespace, "model");
    NameIdentifier[] modelIdents = {modelIdent};
    when(modelDispatcher.listEntities(modelNamespace)).thenReturn(buildModelEntity(modelIdents));
    when(functionDispatcher.listFunctionInfos(modelNamespace))
        .thenReturn(buildFunctionInfos(modelNamespace));

    resp =
        target("/web/entities")
            .queryParam("namespace", "testMetalake.modelCatalog.modelSchema")
            .queryParam("catalogType", "model")
            .request(MediaType.APPLICATION_JSON_TYPE)
            .accept("application/vnd.gravitino.v1+json")
            .get();
    Assertions.assertEquals(Response.Status.OK.getStatusCode(), resp.getStatus());
    Assertions.assertEquals(MediaType.APPLICATION_JSON_TYPE, resp.getMediaType());

    ModelListResponse modelResp = resp.readEntity(ModelListResponse.class);
    Assertions.assertEquals(0, modelResp.getCode());
    Assertions.assertEquals(1, modelResp.getModels().length);
    assertModels(modelResp.getModels());
    Assertions.assertEquals(1, modelResp.getFunctions().length);
    assertFunctions(modelResp.getFunctions());
  }

  private void assertViews(ViewDTO[] views) {
    ViewDTO viewDTO = views[0];
    Assertions.assertEquals("testView", viewDTO.name());
    Assertions.assertNull(viewDTO.comment());
    Assertions.assertTrue(viewDTO.properties().isEmpty());
    Assertions.assertEquals("creator", viewDTO.auditInfo().creator());
    Assertions.assertEquals(1, viewDTO.representations().length);
    Assertions.assertInstanceOf(SQLRepresentation.class, viewDTO.representations()[0]);
    SQLRepresentation representation = (SQLRepresentation) viewDTO.representations()[0];
    Assertions.assertEquals("unavailable", representation.dialect());
    Assertions.assertEquals("UNAVAILABLE", representation.sql());
  }

  private void assertModels(ModelDTO[] models) {
    ModelDTO modelDTO = models[0];
    Assertions.assertEquals("model", modelDTO.name());
    Assertions.assertEquals("comment", modelDTO.comment());
    Assertions.assertEquals(1, modelDTO.latestVersion());
    Assertions.assertEquals("creator", modelDTO.auditInfo().creator());
    Assertions.assertEquals("value", modelDTO.properties().get("key"));
  }

  private void assertFunctions(FunctionDTO[] functions) {
    FunctionDTO functionDTO = functions[0];
    Assertions.assertEquals("testFunction", functionDTO.name());
    Assertions.assertEquals(FunctionType.SCALAR, functionDTO.functionType());
    Assertions.assertTrue(functionDTO.deterministic());
    Assertions.assertEquals("test function comment", functionDTO.comment());
    Assertions.assertEquals("creator", functionDTO.auditInfo().creator());
  }

  private void assertFilesets(FilesetDTO[] filesets) {
    FilesetDTO filesetDTO = filesets[0];
    Assertions.assertEquals("fileset", filesetDTO.name());
    Assertions.assertEquals("comment", filesetDTO.comment());
    Assertions.assertEquals("location", filesetDTO.storageLocation());
    Assertions.assertEquals(MANAGED, filesetDTO.type());
    Assertions.assertEquals("value", filesetDTO.properties().get("key"));
    Assertions.assertEquals("creator", filesetDTO.auditInfo().creator());
  }

  private void assertTopics(TopicDTO[] topics) {
    TopicDTO topicDTO = topics[0];
    Assertions.assertEquals("messagingTopic", topicDTO.name());
    Assertions.assertEquals("comment", topicDTO.comment());
    Assertions.assertNull(topicDTO.properties());
    Assertions.assertEquals("creator", topicDTO.auditInfo().creator());
  }

  private void assertTables(TableDTO[] tableDTOs) {
    TableDTO tableDTO = tableDTOs[0];
    Assertions.assertEquals("relTable", tableDTO.name());
    Assertions.assertNull(tableDTO.comment());
    Assertions.assertNull(tableDTO.properties());
    Assertions.assertEquals("creator", tableDTO.auditInfo().creator());
  }

  private void assertSchemas(SchemaDTO[] schemaDTOs) {
    SchemaDTO schemaDTO = schemaDTOs[0];
    Assertions.assertEquals("relSchema", schemaDTO.name());
    Assertions.assertNull(schemaDTO.comment());
    Assertions.assertNull(schemaDTO.properties());
    Assertions.assertEquals("creator", schemaDTO.auditInfo().creator());
  }

  private void assertCatalogs(CatalogDTO[] catalogDTOs) {
    CatalogDTO catalogDTO1 = catalogDTOs[0];
    Assertions.assertEquals("relCatalog1", catalogDTO1.name());
    Assertions.assertEquals(Catalog.Type.RELATIONAL, catalogDTO1.type());
    Assertions.assertEquals("comment", catalogDTO1.comment());
    Assertions.assertEquals(
        ImmutableMap.of("key", "value", "in-use", "true"), catalogDTO1.properties());
    Assertions.assertEquals("creator", catalogDTO1.auditInfo().creator());

    CatalogDTO catalogDTO2 = catalogDTOs[1];
    Assertions.assertEquals("relCatalog2", catalogDTO2.name());
    Assertions.assertEquals(Catalog.Type.RELATIONAL, catalogDTO2.type());
    Assertions.assertEquals("comment", catalogDTO2.comment());
    Assertions.assertEquals(
        ImmutableMap.of("key", "value", "in-use", "true"), catalogDTO2.properties());
    Assertions.assertEquals("creator", catalogDTO2.auditInfo().creator());
  }

  private Function[] buildFunctionInfos(Namespace namespace) {
    return new Function[] {
      FunctionEntity.builder()
          .withId(1L)
          .withName("testFunction")
          .withNamespace(namespace)
          .withComment("test function comment")
          .withFunctionType(FunctionType.SCALAR)
          .withDeterministic(true)
          .withDefinitions(new org.apache.gravitino.function.FunctionDefinition[0])
          .withAuditInfo(
              AuditInfo.builder().withCreator("creator").withCreateTime(Instant.now()).build())
          .build()
    };
  }

  private void mockViews(Namespace namespace) {
    NameIdentifier[] viewIdents = {NameIdentifier.of(namespace, "testView")};
    when(viewDispatcher.listViews(namespace)).thenReturn(viewIdents);
    when(viewDispatcher.listEntities(namespace)).thenReturn(buildViewEntity(viewIdents));
  }

  private List<ViewEntity> buildViewEntity(NameIdentifier[] viewIdents) {
    return Arrays.stream(viewIdents)
        .map(
            ident ->
                ViewEntity.builder()
                    .withId(1L)
                    .withName(ident.name())
                    .withNamespace(ident.namespace())
                    .withComment("test view comment")
                    .withColumns(new Column[] {Column.of("view_col", Types.IntegerType.get())})
                    .withRepresentations(
                        new Representation[] {
                          SQLRepresentation.builder()
                              .withDialect("spark")
                              .withSql("SELECT 1")
                              .build()
                        })
                    .withProperties(ImmutableMap.of("key", "value"))
                    .withAuditInfo(
                        AuditInfo.builder()
                            .withCreator("creator")
                            .withCreateTime(Instant.now())
                            .build())
                    .build())
        .collect(Collectors.toList());
  }

  private List<ModelEntity> buildModelEntity(NameIdentifier[] modelIdents) {
    return Arrays.stream(modelIdents)
        .map(
            ident ->
                ModelEntity.builder()
                    .withId(1L)
                    .withName(ident.name())
                    .withNamespace(ident.namespace())
                    .withComment("comment")
                    .withLatestVersion(1)
                    .withProperties(ImmutableMap.of("key", "value"))
                    .withAuditInfo(
                        AuditInfo.builder()
                            .withCreator("creator")
                            .withCreateTime(Instant.now())
                            .build())
                    .build())
        .collect(Collectors.toList());
  }

  private List<FilesetEntity> buildFilesetEntity(NameIdentifier[] filesetIdents) {
    return Arrays.stream(filesetIdents)
        .map(
            ident ->
                FilesetEntity.builder()
                    .withId(1L)
                    .withName(ident.name())
                    .withNamespace(ident.namespace())
                    .withComment("comment")
                    .withFilesetType(MANAGED)
                    .withStorageLocations(ImmutableMap.of(LOCATION_NAME_UNKNOWN, "location"))
                    .withProperties(ImmutableMap.of("key", "value"))
                    .withAuditInfo(
                        AuditInfo.builder()
                            .withCreator("creator")
                            .withCreateTime(Instant.now())
                            .build())
                    .build())
        .collect(Collectors.toList());
  }

  private List<TopicEntity> buildTopicEntity(NameIdentifier[] topicIdents) {
    return Arrays.stream(topicIdents)
        .map(
            ident ->
                TopicEntity.builder()
                    .withId(1L)
                    .withName(ident.name())
                    .withNamespace(ident.namespace())
                    .withComment("comment")
                    .withAuditInfo(
                        AuditInfo.builder()
                            .withCreator("creator")
                            .withCreateTime(Instant.now())
                            .build())
                    .build())
        .collect(Collectors.toList());
  }

  private List<TableEntity> buildTableEntity(NameIdentifier[] tableIdents) {
    return Arrays.stream(tableIdents)
        .map(
            ident ->
                TableEntity.builder()
                    .withId(1L)
                    .withName(ident.name())
                    .withNamespace(ident.namespace())
                    .withAuditInfo(
                        AuditInfo.builder()
                            .withCreator("creator")
                            .withCreateTime(Instant.now())
                            .build())
                    .withColumns(
                        ImmutableList.of(
                            org.apache.gravitino.meta.ColumnEntity.builder()
                                .withName("col1")
                                .withId(1L)
                                .withPosition(1)
                                .withDataType(Types.IntegerType.get())
                                .withNullable(true)
                                .withAuditInfo(
                                    AuditInfo.builder()
                                        .withCreator("creator")
                                        .withCreateTime(Instant.now())
                                        .build())
                                .withComment("comment")
                                .build()))
                    .build())
        .collect(Collectors.toList());
  }

  private List<SchemaEntity> buildSchemaEntity(NameIdentifier[] schemaIdents) {
    return Arrays.stream(schemaIdents)
        .map(
            ident ->
                SchemaEntity.builder()
                    .withId(1L)
                    .withName(ident.name())
                    .withComment("comment")
                    .withNamespace(ident.namespace())
                    .withProperties(ImmutableMap.of("key", "value"))
                    .withAuditInfo(
                        AuditInfo.builder()
                            .withCreator("creator")
                            .withCreateTime(Instant.now())
                            .build())
                    .build())
        .collect(Collectors.toList());
  }

  private TestCatalog buildCatalog(String metalake, String catalogName) {
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
