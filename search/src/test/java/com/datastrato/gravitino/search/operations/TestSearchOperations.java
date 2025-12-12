/*
 * Copyright 2024 Datastrato Pvt Ltd.
 * This software is licensed under the Apache License version 2.
 */
package com.datastrato.gravitino.search.operations;

import static org.apache.gravitino.Configs.TREE_LOCK_CLEAN_INTERVAL;
import static org.apache.gravitino.Configs.TREE_LOCK_MAX_NODE_IN_MEMORY;
import static org.apache.gravitino.Configs.TREE_LOCK_MIN_NODE_IN_MEMORY;
import static org.apache.gravitino.MetadataObject.Type.METALAKE;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.datastrato.gravitino.ExtendedDatastratoGravitinoEnv;
import com.datastrato.gravitino.search.dto.TaskStatusDTO;
import com.datastrato.gravitino.search.rest.SearchOperations;
import com.datastrato.gravitino.search.rest.SynMetadataRequest;
import com.datastrato.gravitino.search.rest.TaskStatusResponse;
import com.datastrato.gravitino.search.service.SearchService;
import com.datastrato.gravitino.search.service.SyncTask;
import com.datastrato.gravitino.search.service.SyncTaskOptions;
import com.datastrato.gravitino.search.service.TaskStatus;
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

    SearchService service = new SearchService(config);
    searchService = service;

    SearchService spyService = Mockito.spy(service);
    doAnswer(
            invocation ->
                new SyncTask(
                    "test",
                    MetadataObjects.parse("test", METALAKE),
                    true,
                    spyService,
                    SyncTaskOptions.DEFAULT,
                    null))
        .when(spyService)
        .synchronizeMetadata(Mockito.anyString(), Mockito.any(), Mockito.anyBoolean());

    FieldUtils.writeField(
        ExtendedDatastratoGravitinoEnv.getInstance(), "searchService", spyService, true);
  }

  @AfterAll
  public static void clean() {
    try {
      FieldUtils.writeField(GravitinoEnv.getInstance(), "config", null, true);
    } catch (IllegalAccessException e) {
      throw new RuntimeException(e);
    }
  }

  @Test
  void testSearchOperations() {
    Response resp =
        target("/search/sync/metalakes/metalake1/objects")
            .request(MediaType.APPLICATION_JSON_TYPE)
            .accept("application/vnd.gravitino.v1+json")
            .post(
                Entity.entity(
                    SynMetadataRequest.builder().build(), MediaType.APPLICATION_JSON_TYPE));

    Assertions.assertEquals(Status.OK.getStatusCode(), resp.getStatus());
    Assertions.assertEquals(MediaType.APPLICATION_JSON_TYPE, resp.getMediaType());

    resp =
        target("/search/sync/metalakes/metalake1/objects")
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
        target("/search/sync/metalakes/metalake1/objects")
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
        target("/search/sync/metalakes/metalake1/objects")
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
        target("/search/sync/metalakes/metalake1/objects")
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
        target("/search/sync/metalakes/metalake1/objects")
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
        "If the type is SCHEMA, the length of names must be 2", errorResp.getMessage());
  }

  @Test
  void testGetTaskStatus() {
    long taskCreateTime = System.currentTimeMillis();
    String taskId = "test-task-id";
    TaskStatus taskStatus =
        TaskStatus.builder()
            .withTaskId(taskId)
            .withMetadataObject("")
            .withMetalake("test-metalake")
            .withCascade(true)
            .withTaskStatus(TaskStatus.TaskStatusEnum.RUNNING.name())
            .withMessage("Task is running")
            .withTaskCreateTime(taskCreateTime)
            .withTaskUpdateTime(taskCreateTime)
            .build();

    searchService.getTaskStatusStorage().save(taskStatus, false);

    Response resp =
        target(String.format("/search/task/%s/status", taskId))
            .request(MediaType.APPLICATION_JSON_TYPE)
            .accept("application/vnd.gravitino.v1+json")
            .get();

    Assertions.assertEquals(Status.OK.getStatusCode(), resp.getStatus());
    Assertions.assertEquals(MediaType.APPLICATION_JSON_TYPE, resp.getMediaType());

    TaskStatusResponse response = resp.readEntity(TaskStatusResponse.class);
    TaskStatusDTO dto = response.getTaskStatusDTO();
    Assertions.assertNotNull(dto);
    Assertions.assertTrue(equals(taskStatus, dto));

    // Update the task status
    taskStatus =
        taskStatus
            .toBuilder()
            .withTaskStatus(TaskStatus.TaskStatusEnum.COMPLETED.name())
            .withMessage("Task completed successfully")
            .withTaskUpdateTime(System.currentTimeMillis())
            .build();
    searchService.getTaskStatusStorage().update(taskStatus);

    resp =
        target(String.format("/search/task/%s/status/", taskId))
            .request(MediaType.APPLICATION_JSON_TYPE)
            .accept("application/vnd.gravitino.v1+json")
            .get();
    Assertions.assertEquals(Status.OK.getStatusCode(), resp.getStatus());
    Assertions.assertEquals(MediaType.APPLICATION_JSON_TYPE, resp.getMediaType());
    response = resp.readEntity(TaskStatusResponse.class);
    dto = response.getTaskStatusDTO();
    Assertions.assertNotNull(dto);
    Assertions.assertTrue(equals(taskStatus, dto));
  }

  private boolean equals(TaskStatus taskStatus, TaskStatusDTO taskStatusDTO) {
    if (taskStatus == null && taskStatusDTO == null) {
      return true;
    }

    if (taskStatus == null || taskStatusDTO == null) {
      return false;
    }

    return taskStatus.getTaskId().equals(taskStatusDTO.getTaskId())
        && taskStatus.getTaskStatus().equals(taskStatusDTO.getTaskStatus())
        && taskStatus.getMessage().equals(taskStatusDTO.getMessage())
        && taskStatus.getMetalake().equals(taskStatusDTO.getMetalake())
        && taskStatus.getMetadataObject().equals(taskStatusDTO.getMetadataObject())
        && taskStatus.isCascade() == taskStatusDTO.isCascade()
        && taskStatus.getTaskCreateTime().equals(taskStatusDTO.getTaskCreateTime())
        && taskStatus.getTaskUpdateTime().equals(taskStatusDTO.getTaskUpdateTime());
  }
}
