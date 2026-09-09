/*
 * Copyright 2026 Datastrato Inc.
 */
package com.datastrato.gravitino.search.operations;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.datastrato.gravitino.ExtendedDatastratoGravitinoEnv;
import com.datastrato.gravitino.search.dto.TaskStatusDTO;
import com.datastrato.gravitino.search.rest.SearchOperations;
import com.datastrato.gravitino.search.rest.SynMetadataRequest;
import com.datastrato.gravitino.search.service.SearchService;
import com.datastrato.gravitino.search.service.SyncTask;
import java.util.Collections;
import javax.servlet.http.HttpServletRequest;
import javax.ws.rs.client.Entity;
import javax.ws.rs.client.WebTarget;
import javax.ws.rs.core.Application;
import javax.ws.rs.core.MediaType;
import javax.ws.rs.core.Response;
import org.apache.commons.lang3.reflect.FieldUtils;
import org.apache.gravitino.Config;
import org.apache.gravitino.Configs;
import org.apache.gravitino.GravitinoEnv;
import org.apache.gravitino.MetadataObject;
import org.apache.gravitino.MetadataObjects;
import org.apache.gravitino.UserPrincipal;
import org.apache.gravitino.auth.AuthConstants;
import org.apache.gravitino.authorization.GravitinoAuthorizer;
import org.apache.gravitino.dto.responses.ErrorResponse;
import org.apache.gravitino.listener.EventBus;
import org.apache.gravitino.listener.api.event.server.AuthorizationDenialFailureEvent;
import org.apache.gravitino.utils.PrincipalUtils;
import org.glassfish.hk2.utilities.binding.AbstractBinder;
import org.glassfish.jersey.server.ResourceConfig;
import org.glassfish.jersey.test.JerseyTest;
import org.glassfish.jersey.test.TestProperties;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/** HTTP regression tests for search query and maintenance authorization. */
public class TestSearchAuthorization extends JerseyTest {
  private Config config;
  private GravitinoAuthorizer authorizer;
  private SearchService searchService;
  private EventBus eventBus;
  private Config previousConfig;
  private GravitinoAuthorizer previousAuthorizer;
  private Object previousSearchService;
  private EventBus previousEventBus;

  /** Starts the HTTP resource with an authenticated caller. */
  @Override
  protected Application configure() {
    forceSet(TestProperties.CONTAINER_PORT, "0");
    HttpServletRequest request = mock(HttpServletRequest.class);
    when(request.getAttribute(AuthConstants.AUTHENTICATED_PRINCIPAL_ATTRIBUTE_NAME))
        .thenReturn(new UserPrincipal("search-user"));
    return new ResourceConfig(SearchOperations.class)
        .register(
            new AbstractBinder() {
              @Override
              protected void configure() {
                bind(request).to(HttpServletRequest.class);
              }
            });
  }

  /** Installs isolated server dependencies and starts Jersey. */
  @BeforeEach
  @Override
  public void setUp() throws Exception {
    GravitinoEnv env = GravitinoEnv.getInstance();
    previousConfig = env.config();
    previousAuthorizer = env.gravitinoAuthorizer();
    previousEventBus = (EventBus) FieldUtils.readField(env, "eventBus", true);
    previousSearchService =
        FieldUtils.readField(ExtendedDatastratoGravitinoEnv.getInstance(), "searchService", true);
    config = mock(Config.class);
    authorizer = mock(GravitinoAuthorizer.class);
    searchService = mock(SearchService.class);
    eventBus = mock(EventBus.class);
    when(config.get(Configs.ENABLE_AUTHORIZATION)).thenReturn(true);
    FieldUtils.writeField(env, "config", config, true);
    env.setGravitinoAuthorizer(authorizer);
    FieldUtils.writeField(env, "eventBus", eventBus, true);
    FieldUtils.writeField(
        ExtendedDatastratoGravitinoEnv.getInstance(), "searchService", searchService, true);
    super.setUp();
  }

  /** Stops Jersey and restores shared server state. */
  @AfterEach
  @Override
  public void tearDown() throws Exception {
    try {
      super.tearDown();
    } finally {
      GravitinoEnv env = GravitinoEnv.getInstance();
      FieldUtils.writeField(env, "config", previousConfig, true);
      env.setGravitinoAuthorizer(previousAuthorizer);
      FieldUtils.writeField(env, "eventBus", previousEventBus, true);
      FieldUtils.writeField(
          ExtendedDatastratoGravitinoEnv.getInstance(),
          "searchService",
          previousSearchService,
          true);
    }
  }

  @Test
  void testMemberCanQueryWithAuthenticatedIdentity() {
    when(authorizer.isMetalakeUser(eq("lake"), any()))
        .thenAnswer(
            invocation -> {
              assertEquals("search-user", PrincipalUtils.getCurrentUserName());
              return true;
            });
    when(searchService.queryVisible("lake", "table", 2, 7)).thenReturn(Collections.emptyList());
    try (Response response =
        target("search/query")
            .queryParam("metalake", "lake")
            .queryParam("keyword", "table")
            .queryParam("pageNumber", 2)
            .queryParam("pageSize", 7)
            .request()
            .get()) {
      assertEquals(200, response.getStatus());
    }
    verify(searchService).queryVisible("lake", "table", 2, 7);
    verifyNoInteractions(eventBus);
  }

  @Test
  void testNonMemberCannotQueryAnotherMetalake() {
    when(authorizer.isMetalakeUser(eq("lake"), any())).thenReturn(true);
    try (Response response = query("other-lake")) {
      assertEquals(403, response.getStatus());
      assertTrue(response.readEntity(ErrorResponse.class).getMessage().contains("other-lake"));
    }
    verifyNoInteractions(searchService);
    verify(eventBus).dispatchEvent(any(AuthorizationDenialFailureEvent.class));
  }

  @Test
  void testAuditFailureDoesNotAllowQuery() {
    doThrow(new IllegalStateException("Audit unavailable"))
        .when(eventBus)
        .dispatchEvent(any(AuthorizationDenialFailureEvent.class));
    try (Response response = query("lake")) {
      assertEquals(403, response.getStatus());
    }
    verifyNoInteractions(searchService);
  }

  @Test
  void testServiceAdminStillRequiresMembership() {
    when(authorizer.isServiceAdmin()).thenReturn(true);
    try (Response response = query("lake")) {
      assertEquals(403, response.getStatus());
    }
    verifyNoInteractions(searchService);
  }

  @Test
  void testAuthorizationDisabledSkipsMembership() {
    when(config.get(Configs.ENABLE_AUTHORIZATION)).thenReturn(false);
    try (Response response = query("lake")) {
      assertEquals(200, response.getStatus());
    }
    verify(searchService).query("lake", null, 0, 10);
    verifyNoInteractions(authorizer, eventBus);
  }

  @Test
  void testInvalidMetalakeRemainsBadRequest() {
    for (String metalake : new String[] {null, "", " "}) {
      try (Response response = query(metalake)) {
        assertEquals(400, response.getStatus());
      }
    }
    verifyNoInteractions(authorizer, searchService, eventBus);
  }

  @Test
  void testAuthorizationFailureDoesNotBecomeEmptySuccess() {
    when(authorizer.isMetalakeUser(eq("lake"), any()))
        .thenThrow(new IllegalStateException("Authorization storage unavailable"));
    try (Response response = query("lake")) {
      assertEquals(500, response.getStatus());
    }
    verifyNoInteractions(searchService, eventBus);
  }

  @Test
  void testMissingAuthorizerFailsClosed() {
    GravitinoEnv.getInstance().setGravitinoAuthorizer(null);
    try (Response response = query("lake")) {
      assertEquals(500, response.getStatus());
    }
    verifyNoInteractions(searchService);
  }

  @Test
  void testFilteredQueryFailureDoesNotReturnPartialResults() {
    when(authorizer.isMetalakeUser(eq("lake"), any())).thenReturn(true);
    when(searchService.queryVisible("lake", null, 0, 10))
        .thenThrow(new IllegalStateException("Search backend unavailable"));
    try (Response response = query("lake")) {
      assertEquals(500, response.getStatus());
    }
  }

  @Test
  void testServiceAdminCanMaintainWithoutMembership() throws Exception {
    when(authorizer.isServiceAdmin())
        .thenAnswer(
            call -> {
              assertEquals("search-user", PrincipalUtils.getCurrentUserName());
              return true;
            });
    mockMaintenanceTasks();
    assertMaintenanceStatus(200);
    verify(authorizer, never()).isMetalakeUser(anyString(), any());
    verify(authorizer, never()).isOwner(any(), anyString(), any(), any());
    verify(searchService).rebuildMetadata("lake");
    verify(searchService).synchronizeMetadata(eq("lake"), any(), eq(true));
  }

  @Test
  void testMetalakeOwnerCanMaintain() throws Exception {
    when(authorizer.isOwner(any(), eq("lake"), any(), any()))
        .thenAnswer(
            call -> {
              MetadataObject object = call.getArgument(2);
              assertEquals(MetadataObject.Type.METALAKE, object.type());
              assertEquals("lake", object.name());
              return true;
            });
    mockMaintenanceTasks();
    assertMaintenanceStatus(200);
    verify(searchService).rebuildMetadata("lake");
    verify(searchService).synchronizeMetadata(eq("lake"), any(), eq(true));
  }

  @Test
  void testOrdinaryMemberCannotMaintain() {
    when(authorizer.isMetalakeUser(eq("lake"), any())).thenReturn(true);
    assertMaintenanceStatus(403);
    verifyNoInteractions(searchService);
  }

  @Test
  void testOwnerOfAnotherMetalakeCannotMaintain() {
    when(authorizer.isOwner(any(), eq("other"), any(), any())).thenReturn(true);
    assertMaintenanceStatus(403);
    verifyNoInteractions(searchService);
  }

  @Test
  void testMaintenanceAuthorizationFailureDoesNotSubmitTask() {
    when(authorizer.isServiceAdmin()).thenThrow(new IllegalStateException("unavailable"));
    assertMaintenanceStatus(500);
    verifyNoInteractions(searchService);
  }

  @Test
  void testMaintenanceSkipsAuthorizationWhenDisabled() throws Exception {
    when(config.get(Configs.ENABLE_AUTHORIZATION)).thenReturn(false);
    mockMaintenanceTasks();
    assertMaintenanceStatus(200);
    verifyNoInteractions(authorizer, eventBus);
  }

  @Test
  void testSpecificObjectSyncStillRequiresMetalakeOwner() {
    when(authorizer.isOwner(any(), eq("lake"), any(), any()))
        .thenAnswer(
            call -> ((MetadataObject) call.getArgument(2)).type() == MetadataObject.Type.TABLE);
    try (Response response =
        target("search/sync/metalakes/lake/objects")
            .request()
            .post(
                Entity.entity(
                    SynMetadataRequest.builder()
                        .metadataType("TABLE")
                        .metadataFullName("catalog.schema.table")
                        .cascade(false)
                        .build(),
                    MediaType.APPLICATION_JSON_TYPE))) {
      assertEquals(403, response.getStatus());
    }
    verifyNoInteractions(searchService);
  }

  @Test
  void testAuthorizedObjectSyncPreservesObjectAndCascade() throws Exception {
    when(authorizer.isServiceAdmin()).thenReturn(true);
    mockMaintenanceTasks();
    try (Response response =
        target("search/sync/metalakes/lake/objects")
            .request()
            .post(
                Entity.entity(
                    SynMetadataRequest.builder()
                        .metadataType("TABLE")
                        .metadataFullName("catalog.schema.table")
                        .cascade(false)
                        .build(),
                    MediaType.APPLICATION_JSON_TYPE))) {
      assertEquals(200, response.getStatus());
    }
    verify(searchService)
        .synchronizeMetadata(
            eq("lake"),
            eq(MetadataObjects.parse("catalog.schema.table", MetadataObject.Type.TABLE)),
            eq(false));
  }

  @Test
  void testInvalidPaginationIsRejectedBeforeAuthorization() {
    for (WebTarget endpoint :
        new WebTarget[] {
          target("search/query").queryParam("metalake", "lake").queryParam("pageNumber", -1),
          target("search/query").queryParam("metalake", "lake").queryParam("pageSize", 0)
        }) {
      try (Response response = endpoint.request().get()) {
        assertEquals(400, response.getStatus());
      }
    }
    verifyNoInteractions(authorizer, searchService);
  }

  @Test
  void testUnfilteredBackendFailureReturnsServerError() {
    when(config.get(Configs.ENABLE_AUTHORIZATION)).thenReturn(false);
    when(searchService.query("lake", null, 0, 10))
        .thenThrow(new IllegalStateException("unavailable"));
    try (Response response = query("lake")) {
      assertEquals(500, response.getStatus());
    }
  }

  @Test
  void testTaskStatusRequiresTheMaintenancePermissionOfItsMetalake() {
    when(searchService.getTaskStatus("task")).thenReturn(taskStatus());
    when(authorizer.isMetalakeUser(eq("lake"), any())).thenReturn(true);

    try (Response response = target("search/task/task/status").request().get()) {
      assertEquals(403, response.getStatus());
    }
  }

  @Test
  void testMetalakeOwnerCanReadTaskStatus() {
    when(searchService.getTaskStatus("task")).thenReturn(taskStatus());
    when(authorizer.isOwner(any(), eq("lake"), any(), any())).thenReturn(true);

    try (Response response = target("search/task/task/status").request().get()) {
      assertEquals(200, response.getStatus());
    }
  }

  private static TaskStatusDTO taskStatus() {
    return TaskStatusDTO.builder()
        .withTaskId("task")
        .withMetalake("lake")
        .withTaskStatus("FINISHED")
        .withMetadataObject("lake")
        .build();
  }

  private void mockMaintenanceTasks() throws Exception {
    SyncTask task = mock(SyncTask.class);
    when(task.getTaskId()).thenReturn("task");
    when(searchService.rebuildMetadata("lake")).thenReturn(task);
    when(searchService.synchronizeMetadata(eq("lake"), any(), anyBoolean())).thenReturn(task);
  }

  private void assertMaintenanceStatus(int status) {
    try (Response rebuild =
            target("search/rebuild/metalakes/lake").request().post(Entity.json("{}"));
        Response sync =
            target("search/sync/metalakes/lake/objects").request().post(Entity.json("{}"))) {
      assertEquals(status, rebuild.getStatus());
      assertEquals(status, sync.getStatus());
    }
  }

  private Response query(String metalake) {
    WebTarget endpoint = target("search/query");
    if (metalake != null) {
      endpoint = endpoint.queryParam("metalake", metalake);
    }
    return endpoint.request().get();
  }
}
