/*
 * Copyright 2024 Datastrato Pvt Ltd.
 * This software is licensed under the Apache License version 2.
 */
package com.datastrato.gravitino.search.operations;

import static org.apache.gravitino.Configs.TREE_LOCK_CLEAN_INTERVAL;
import static org.apache.gravitino.Configs.TREE_LOCK_MAX_NODE_IN_MEMORY;
import static org.apache.gravitino.Configs.TREE_LOCK_MIN_NODE_IN_MEMORY;
import static org.apache.gravitino.MetadataObject.Type.METALAKE;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.datastrato.gravitino.search.rest.SearchOperations;
import com.datastrato.gravitino.search.rest.SynMetadataRequest;
import com.datastrato.gravitino.search.service.SearchService;
import com.datastrato.gravitino.search.service.SyncTask;
import com.google.common.collect.ImmutableMap;
import java.io.IOException;
import javax.servlet.http.HttpServletRequest;
import javax.ws.rs.client.Entity;
import javax.ws.rs.core.Application;
import javax.ws.rs.core.MediaType;
import javax.ws.rs.core.Response;
import javax.ws.rs.core.Response.Status;
import org.apache.commons.lang3.reflect.FieldUtils;
import org.apache.gravitino.Config;
import org.apache.gravitino.GravitinoEnv;
import org.apache.gravitino.MetadataObjects;
import org.apache.gravitino.dto.responses.ErrorConstants;
import org.apache.gravitino.dto.responses.ErrorResponse;
import org.apache.gravitino.lock.LockManager;
import org.apache.gravitino.rest.RESTUtils;
import org.glassfish.hk2.utilities.binding.AbstractBinder;
import org.glassfish.jersey.server.ResourceConfig;
import org.glassfish.jersey.test.JerseyTest;
import org.glassfish.jersey.test.TestProperties;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

public class TestSearchOperations extends JerseyTest {

  private static SearchService searchService;

  private static class MockServletRequestFactory extends ServletRequestFactoryBase {

    @Override
    public HttpServletRequest get() {
      HttpServletRequest request = mock(HttpServletRequest.class);
      when(request.getRemoteUser()).thenReturn(null);
      return request;
    }
  }

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
    resourceConfig.register(SearchOperations.class);
    resourceConfig.register(
        new AbstractBinder() {
          @Override
          protected void configure() {
            bindFactory(TestSearchOperations.MockServletRequestFactory.class)
                .to(HttpServletRequest.class);
          }
        });

    return resourceConfig;
  }

  @BeforeAll
  public static void init() throws IllegalAccessException {
    Config config = mock(Config.class);
    when(config.getAllConfig())
        .thenReturn(ImmutableMap.of("gravitino.datastrato.search.storage.impl", "memory"));
    FieldUtils.writeField(GravitinoEnv.getInstance(), "config", config, true);

    SearchService service = SearchService.getSearchService();
    searchService = service;

    SearchService spyService = Mockito.spy(service);
    doReturn(
            new SyncTask("test", "test", MetadataObjects.parse("test", METALAKE), true, spyService))
        .when(spyService)
        .synchronizeMetadata(Mockito.anyString(), Mockito.any(), Mockito.anyBoolean());

    FieldUtils.writeStaticField(SearchService.class, "searchService", spyService, true);
  }

  @AfterAll
  public static void clean() {
    try {
      FieldUtils.writeField(GravitinoEnv.getInstance(), "config", null, true);
      FieldUtils.writeStaticField(SearchService.class, "searchService", searchService, true);
    } catch (IllegalAccessException e) {
      throw new RuntimeException(e);
    }
  }

  @Test
  void testSearchOperations() {
    Response resp =
        target("/search/sync/metalake1/objects")
            .request(MediaType.APPLICATION_JSON_TYPE)
            .accept("application/vnd.gravitino.v1+json")
            .post(
                Entity.entity(
                    SynMetadataRequest.builder().build(), MediaType.APPLICATION_JSON_TYPE));

    Assertions.assertEquals(Status.OK.getStatusCode(), resp.getStatus());
    Assertions.assertEquals(MediaType.APPLICATION_JSON_TYPE, resp.getMediaType());

    resp =
        target("/search/sync/metalake1/objects")
            .request(MediaType.APPLICATION_JSON_TYPE)
            .accept("application/vnd.gravitino.v1+json")
            .post(
                Entity.entity(
                    SynMetadataRequest.builder()
                        .metadataType("CATALOG")
                        .metadataFullName("catalog1")
                        .build(),
                    MediaType.APPLICATION_JSON_TYPE));
    Assertions.assertEquals(Status.OK.getStatusCode(), resp.getStatus());
    Assertions.assertEquals(MediaType.APPLICATION_JSON_TYPE, resp.getMediaType());

    resp =
        target("/search/sync/metalake1/objects")
            .request(MediaType.APPLICATION_JSON_TYPE)
            .accept("application/vnd.gravitino.v1+json")
            .post(
                Entity.entity(
                    SynMetadataRequest.builder()
                        .metadataType("table")
                        .metadataFullName("catalog1.schema1.table1")
                        .build(),
                    MediaType.APPLICATION_JSON_TYPE));

    Assertions.assertEquals(Status.OK.getStatusCode(), resp.getStatus());
    Assertions.assertEquals(MediaType.APPLICATION_JSON_TYPE, resp.getMediaType());

    // The following are wrong examples
    resp =
        target("/search/sync/metalake1/objects")
            .request(MediaType.APPLICATION_JSON_TYPE)
            .accept("application/vnd.gravitino.v1+json")
            .post(
                Entity.entity(
                    SynMetadataRequest.builder().metadataType("table").build(),
                    MediaType.APPLICATION_JSON_TYPE));

    Assertions.assertEquals(Response.Status.BAD_REQUEST.getStatusCode(), resp.getStatus());
    Assertions.assertEquals(MediaType.APPLICATION_JSON_TYPE, resp.getMediaType());

    ErrorResponse errorResp = resp.readEntity(ErrorResponse.class);
    Assertions.assertEquals(ErrorConstants.ILLEGAL_ARGUMENTS_CODE, errorResp.getCode());
    Assertions.assertEquals(
        "Metadata full name is required when metadata type is provided", errorResp.getMessage());

    resp =
        target("/search/sync/metalake1/objects")
            .request(MediaType.APPLICATION_JSON_TYPE)
            .accept("application/vnd.gravitino.v1+json")
            .post(
                Entity.entity(
                    SynMetadataRequest.builder()
                        .metadataFullName("catalog1.schema1.table1")
                        .build(),
                    MediaType.APPLICATION_JSON_TYPE));

    Assertions.assertEquals(Response.Status.BAD_REQUEST.getStatusCode(), resp.getStatus());
    Assertions.assertEquals(MediaType.APPLICATION_JSON_TYPE, resp.getMediaType());
    errorResp = resp.readEntity(ErrorResponse.class);
    Assertions.assertEquals(ErrorConstants.ILLEGAL_ARGUMENTS_CODE, errorResp.getCode());
    Assertions.assertEquals(
        "Metadata type is required when metadata full name is provided", errorResp.getMessage());

    resp =
        target("/search/sync/metalake1/objects")
            .request(MediaType.APPLICATION_JSON_TYPE)
            .accept("application/vnd.gravitino.v1+json")
            .post(
                Entity.entity(
                    SynMetadataRequest.builder()
                        .metadataType("schema")
                        .metadataFullName("catalog1.schema1.table1")
                        .build(),
                    MediaType.APPLICATION_JSON_TYPE));

    Assertions.assertEquals(Response.Status.BAD_REQUEST.getStatusCode(), resp.getStatus());
    Assertions.assertEquals(MediaType.APPLICATION_JSON_TYPE, resp.getMediaType());
    errorResp = resp.readEntity(ErrorResponse.class);
    Assertions.assertEquals(ErrorConstants.ILLEGAL_ARGUMENTS_CODE, errorResp.getCode());
    Assertions.assertEquals(
        "If the length of names is 3, it must be FILESET, TABLE, TOPIC or MODEL",
        errorResp.getMessage());
  }
}
