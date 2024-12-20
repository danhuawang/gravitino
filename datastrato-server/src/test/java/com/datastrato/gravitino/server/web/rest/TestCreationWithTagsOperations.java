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
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.reset;
import static org.mockito.Mockito.when;

import com.datastrato.gravitino.dto.requests.CatalogWithTagsCreateRequest;
import com.datastrato.gravitino.dto.responses.CatalogWithTagsResponse;
import com.google.common.collect.ImmutableMap;
import java.io.IOException;
import java.time.Instant;
import java.util.Collections;
import javax.servlet.http.HttpServletRequest;
import javax.ws.rs.client.Entity;
import javax.ws.rs.core.Application;
import javax.ws.rs.core.MediaType;
import javax.ws.rs.core.Response;
import org.apache.commons.lang3.reflect.FieldUtils;
import org.apache.gravitino.Catalog;
import org.apache.gravitino.Config;
import org.apache.gravitino.GravitinoEnv;
import org.apache.gravitino.Namespace;
import org.apache.gravitino.catalog.CatalogDispatcher;
import org.apache.gravitino.catalog.CatalogManager;
import org.apache.gravitino.dto.CatalogDTO;
import org.apache.gravitino.dto.responses.ErrorConstants;
import org.apache.gravitino.dto.responses.ErrorResponse;
import org.apache.gravitino.exceptions.CatalogAlreadyExistsException;
import org.apache.gravitino.exceptions.TagAlreadyAssociatedException;
import org.apache.gravitino.lock.LockManager;
import org.apache.gravitino.meta.AuditInfo;
import org.apache.gravitino.meta.CatalogEntity;
import org.apache.gravitino.rest.RESTUtils;
import org.apache.gravitino.tag.TagManager;
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
  private final TagManager tagManager = mock(TagManager.class);

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
            bind(tagManager).to(TagManager.class).ranked(2);
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
    when(tagManager.associateTagsForMetadataObject(any(), any(), any(), any())).thenReturn(tags);

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
        .when(tagManager)
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
