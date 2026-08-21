/*
 * Copyright 2026 Datastrato Pvt Ltd.
 * This software is licensed under the Apache License version 2.
 */
package com.datastrato.gravitino.server.web.rest;

import static javax.ws.rs.client.Entity.entity;
import static org.apache.gravitino.Configs.TREE_LOCK_CLEAN_INTERVAL;
import static org.apache.gravitino.Configs.TREE_LOCK_MAX_NODE_IN_MEMORY;
import static org.apache.gravitino.Configs.TREE_LOCK_MIN_NODE_IN_MEMORY;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.reset;
import static org.mockito.Mockito.when;

import com.datastrato.gravitino.ExtendedDatastratoGravitinoEnv;
import com.datastrato.gravitino.authorization.DatastratoAccessControlDispatcher;
import com.datastrato.gravitino.dto.authorization.IdentitySource;
import com.datastrato.gravitino.dto.requests.LocalUserAddRequest;
import com.datastrato.gravitino.dto.requests.UserEnabledBatchUpdateRequest;
import com.datastrato.gravitino.dto.responses.ExtendedGroupListResponse;
import com.datastrato.gravitino.dto.responses.ExtendedUserListResponse;
import com.datastrato.gravitino.dto.responses.ExtendedUserResponse;
import com.google.common.collect.Lists;
import java.io.IOException;
import java.time.Instant;
import java.util.Collections;
import java.util.List;
import javax.servlet.http.HttpServletRequest;
import javax.ws.rs.core.Application;
import javax.ws.rs.core.MediaType;
import javax.ws.rs.core.Response;
import org.apache.commons.lang3.reflect.FieldUtils;
import org.apache.gravitino.Config;
import org.apache.gravitino.EntityStore;
import org.apache.gravitino.GravitinoEnv;
import org.apache.gravitino.Namespace;
import org.apache.gravitino.authorization.Group;
import org.apache.gravitino.authorization.User;
import org.apache.gravitino.connector.PropertiesMetadata;
import org.apache.gravitino.dto.responses.ErrorConstants;
import org.apache.gravitino.dto.responses.ErrorResponse;
import org.apache.gravitino.dto.responses.NameListResponse;
import org.apache.gravitino.exceptions.NoSuchEntityException;
import org.apache.gravitino.exceptions.NoSuchMetalakeException;
import org.apache.gravitino.exceptions.NoSuchUserException;
import org.apache.gravitino.exceptions.NotFoundException;
import org.apache.gravitino.exceptions.UserAlreadyExistsException;
import org.apache.gravitino.lock.LockManager;
import org.apache.gravitino.meta.AuditInfo;
import org.apache.gravitino.meta.BaseMetalake;
import org.apache.gravitino.meta.GroupEntity;
import org.apache.gravitino.meta.UserEntity;
import org.apache.gravitino.rest.RESTUtils;
import org.apache.gravitino.server.authorization.annotations.AuthorizationExpression;
import org.glassfish.hk2.utilities.binding.AbstractBinder;
import org.glassfish.jersey.server.ResourceConfig;
import org.glassfish.jersey.test.JerseyTest;
import org.glassfish.jersey.test.TestProperties;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

public class TestExtendedUserOperations extends JerseyTest {

  private static final DatastratoAccessControlDispatcher accessControlDispatcher =
      mock(DatastratoAccessControlDispatcher.class);
  private static final EntityStore entityStore = mock(EntityStore.class);

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
    FieldUtils.writeField(GravitinoEnv.getInstance(), "entityStore", entityStore, true);
    FieldUtils.writeField(
        ExtendedDatastratoGravitinoEnv.getInstance(),
        "accessControlDispatcher",
        accessControlDispatcher,
        true);
  }

  @BeforeEach
  public void resetMocks() throws IOException {
    reset(accessControlDispatcher, entityStore);
    mockInUseMetalake();
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
    resourceConfig.register(ExtendedUserOperations.class);
    resourceConfig.register(
        new AbstractBinder() {
          @Override
          protected void configure() {
            bindFactory(MockServletRequestFactory.class).to(HttpServletRequest.class);
          }
        });
    return resourceConfig;
  }

  @Test
  public void testListUsers() {
    String metalake = "metalake";
    when(accessControlDispatcher.listUsers(metalake))
        .thenReturn(
            new User[] {buildUser("lee.p", null, true), buildUser("dana.k", "azure-oid", false)});

    Response response =
        target("/web/security/metalakes/" + metalake + "/users")
            .request(MediaType.APPLICATION_JSON_TYPE)
            .accept("application/vnd.gravitino.v1+json")
            .get();

    Assertions.assertEquals(Response.Status.OK.getStatusCode(), response.getStatus());
    ExtendedUserListResponse body = response.readEntity(ExtendedUserListResponse.class);
    Assertions.assertEquals(0, body.getCode());
    Assertions.assertEquals(2, body.getUsers().length);
    Assertions.assertEquals("lee.p", body.getUsers()[0].name());
    Assertions.assertNull(body.getUsers()[0].externalId());
    Assertions.assertEquals(IdentitySource.LOCAL, body.getUsers()[0].origin());
    Assertions.assertEquals("dana.k", body.getUsers()[1].name());
    Assertions.assertEquals("azure-oid", body.getUsers()[1].externalId());
    Assertions.assertEquals(IdentitySource.PROVISIONED, body.getUsers()[1].origin());
    Assertions.assertFalse(body.getUsers()[1].enabled());
  }

  @Test
  public void testBatchUpdateUserEnabled() {
    String metalake = "metalake";
    List<String> users = Lists.newArrayList("alice", "bob");
    when(accessControlDispatcher.batchUpdateUserEnabled(eq(metalake), eq(users), eq(false)))
        .thenReturn(users);

    Response response =
        target("/web/security/metalakes/" + metalake + "/users/enabled")
            .request(MediaType.APPLICATION_JSON_TYPE)
            .accept("application/vnd.gravitino.v1+json")
            .put(
                entity(
                    new UserEnabledBatchUpdateRequest(users.toArray(new String[0]), false),
                    MediaType.APPLICATION_JSON_TYPE));

    Assertions.assertEquals(Response.Status.OK.getStatusCode(), response.getStatus());
    NameListResponse nameListResponse = response.readEntity(NameListResponse.class);
    Assertions.assertEquals(0, nameListResponse.getCode());
    Assertions.assertArrayEquals(users.toArray(new String[0]), nameListResponse.getNames());
  }

  @Test
  public void testBatchUpdateUserEnabledIllegal() {
    String metalake = "metalake";
    when(accessControlDispatcher.batchUpdateUserEnabled(any(), any(), anyBoolean()))
        .thenThrow(
            new IllegalArgumentException(
                "Cannot batch update enabled for users under metalake metalake: every user must"
                    + " exist and must not have an externalId"));

    Response response =
        target("/web/security/metalakes/" + metalake + "/users/enabled")
            .request(MediaType.APPLICATION_JSON_TYPE)
            .accept("application/vnd.gravitino.v1+json")
            .put(
                entity(
                    new UserEnabledBatchUpdateRequest(new String[] {"alice"}, false),
                    MediaType.APPLICATION_JSON_TYPE));

    Assertions.assertEquals(Response.Status.BAD_REQUEST.getStatusCode(), response.getStatus());
    ErrorResponse errorResponse = response.readEntity(ErrorResponse.class);
    Assertions.assertEquals(ErrorConstants.ILLEGAL_ARGUMENTS_CODE, errorResponse.getCode());
  }

  @Test
  public void testBatchUpdateUserEnabledNoSuchMetalake() throws IOException {
    String metalake = "missing";
    when(entityStore.get(any(), any(), any()))
        .thenThrow(new NoSuchEntityException("metalake does not exist"));

    Response response =
        target("/web/security/metalakes/" + metalake + "/users/enabled")
            .request(MediaType.APPLICATION_JSON_TYPE)
            .accept("application/vnd.gravitino.v1+json")
            .put(
                entity(
                    new UserEnabledBatchUpdateRequest(new String[] {"alice"}, false),
                    MediaType.APPLICATION_JSON_TYPE));

    Assertions.assertEquals(Response.Status.NOT_FOUND.getStatusCode(), response.getStatus());
    ErrorResponse errorResponse = response.readEntity(ErrorResponse.class);
    Assertions.assertEquals(ErrorConstants.NOT_FOUND_CODE, errorResponse.getCode());
    Assertions.assertEquals(NoSuchMetalakeException.class.getSimpleName(), errorResponse.getType());
  }

  @Test
  public void testAuthorizationAnnotations() throws NoSuchMethodException {
    assertManageUsers("listUsers", String.class);
    assertManageUsers("getUser", String.class, String.class);
    assertManageUsers("listGroupsForUser", String.class, String.class);
    assertManageUsers("addUser", String.class, LocalUserAddRequest.class);
    assertManageUsers("batchUpdateUserEnabled", String.class, UserEnabledBatchUpdateRequest.class);
  }

  @Test
  public void testGetUser() {
    when(accessControlDispatcher.getUser("metalake", "lee.p"))
        .thenReturn(buildUser("lee.p", null, true));
    Response response = get("/web/security/metalakes/metalake/users/lee.p");
    Assertions.assertEquals(Response.Status.OK.getStatusCode(), response.getStatus());
    ExtendedUserResponse body = response.readEntity(ExtendedUserResponse.class);
    Assertions.assertEquals("lee.p", body.getUser().name());
    Assertions.assertEquals(IdentitySource.LOCAL, body.getUser().origin());
  }

  @Test
  public void testGetUserMissing() {
    when(accessControlDispatcher.getUser(any(), any()))
        .thenThrow(new NoSuchUserException("missing"));
    Assertions.assertEquals(
        Response.Status.NOT_FOUND.getStatusCode(),
        get("/web/security/metalakes/metalake/users/missing").getStatus());
  }

  @Test
  public void testListUserGroups() {
    when(accessControlDispatcher.listGroupsForUser("metalake", "alice"))
        .thenReturn(new Group[] {buildGroup("contractors", null)});
    ExtendedGroupListResponse body =
        get("/web/security/metalakes/metalake/users/alice/groups")
            .readEntity(ExtendedGroupListResponse.class);
    Assertions.assertEquals(1, body.getGroups().length);
    Assertions.assertEquals("contractors", body.getGroups()[0].name());
    Assertions.assertEquals(IdentitySource.LOCAL, body.getGroups()[0].origin());
  }

  @Test
  public void testAddLocalUser() {
    String metalake = "metalake";
    User added = buildUser("jordan.reyes", null, true);
    when(accessControlDispatcher.addLocalUser(
            eq(metalake), eq("jordan.reyes"), eq(List.of("Analyst")), eq(true)))
        .thenReturn(added);

    Response response =
        target("/web/security/metalakes/" + metalake + "/users")
            .request(MediaType.APPLICATION_JSON_TYPE)
            .accept("application/vnd.gravitino.v1+json")
            .post(
                entity(
                    new LocalUserAddRequest("jordan.reyes", List.of("Analyst"), true),
                    MediaType.APPLICATION_JSON_TYPE));

    Assertions.assertEquals(Response.Status.OK.getStatusCode(), response.getStatus());
    ExtendedUserResponse body = response.readEntity(ExtendedUserResponse.class);
    Assertions.assertEquals(0, body.getCode());
    Assertions.assertEquals("jordan.reyes", body.getUser().name());
    Assertions.assertEquals(IdentitySource.LOCAL, body.getUser().origin());
    Assertions.assertTrue(body.getUser().enabled());
  }

  @Test
  public void testAddLocalUserNotFound() {
    String metalake = "metalake";
    when(accessControlDispatcher.addLocalUser(any(), any(), any(), any()))
        .thenThrow(new NotFoundException("IdP user missing"));

    Response response =
        target("/web/security/metalakes/" + metalake + "/users")
            .request(MediaType.APPLICATION_JSON_TYPE)
            .accept("application/vnd.gravitino.v1+json")
            .post(
                entity(
                    new LocalUserAddRequest("missing", null, true),
                    MediaType.APPLICATION_JSON_TYPE));

    Assertions.assertEquals(Response.Status.NOT_FOUND.getStatusCode(), response.getStatus());
    ErrorResponse errorResponse = response.readEntity(ErrorResponse.class);
    Assertions.assertEquals(ErrorConstants.NOT_FOUND_CODE, errorResponse.getCode());
  }

  @Test
  public void testAddLocalUserConflict() {
    String metalake = "metalake";
    when(accessControlDispatcher.addLocalUser(any(), any(), any(), any()))
        .thenThrow(new UserAlreadyExistsException("User already exists"));

    Response response =
        target("/web/security/metalakes/" + metalake + "/users")
            .request(MediaType.APPLICATION_JSON_TYPE)
            .accept("application/vnd.gravitino.v1+json")
            .post(
                entity(
                    new LocalUserAddRequest("alice", null, true), MediaType.APPLICATION_JSON_TYPE));

    Assertions.assertEquals(Response.Status.CONFLICT.getStatusCode(), response.getStatus());
    ErrorResponse errorResponse = response.readEntity(ErrorResponse.class);
    Assertions.assertEquals(ErrorConstants.ALREADY_EXISTS_CODE, errorResponse.getCode());
  }

  @Test
  public void testAddLocalUserBadRequest() {
    String metalake = "metalake";
    Response response =
        target("/web/security/metalakes/" + metalake + "/users")
            .request(MediaType.APPLICATION_JSON_TYPE)
            .accept("application/vnd.gravitino.v1+json")
            .post(
                entity(new LocalUserAddRequest(" ", null, true), MediaType.APPLICATION_JSON_TYPE));

    Assertions.assertEquals(Response.Status.BAD_REQUEST.getStatusCode(), response.getStatus());
  }

  private Response get(String path) {
    return target(path)
        .request(MediaType.APPLICATION_JSON_TYPE)
        .accept("application/vnd.gravitino.v1+json")
        .get();
  }

  private static void assertManageUsers(String method, Class<?>... params)
      throws NoSuchMethodException {
    Assertions.assertEquals(
        "METALAKE::OWNER || METALAKE::MANAGE_USERS",
        ExtendedUserOperations.class
            .getMethod(method, params)
            .getAnnotation(AuthorizationExpression.class)
            .expression());
  }

  private void mockInUseMetalake() throws IOException {
    BaseMetalake metalake = mock(BaseMetalake.class);
    PropertiesMetadata propertiesMetadata = mock(PropertiesMetadata.class);
    when(propertiesMetadata.getOrDefault(any(), any())).thenReturn(true);
    when(metalake.propertiesMetadata()).thenReturn(propertiesMetadata);
    when(entityStore.get(any(), any(), any())).thenReturn(metalake);
  }

  private static User buildUser(String name, String externalId, boolean enabled) {
    return UserEntity.builder()
        .withId(1L)
        .withName(name)
        .withNamespace(Namespace.of("metalake", "system", "user"))
        .withExternalId(externalId)
        .withEnabled(enabled)
        .withRoleNames(Collections.emptyList())
        .withAuditInfo(
            AuditInfo.builder().withCreator("test").withCreateTime(Instant.now()).build())
        .build();
  }

  private static Group buildGroup(String name, String externalId) {
    return GroupEntity.builder()
        .withId(1L)
        .withName(name)
        .withNamespace(Namespace.of("metalake", "system", "group"))
        .withExternalId(externalId)
        .withRoleNames(Collections.emptyList())
        .withAuditInfo(
            AuditInfo.builder().withCreator("test").withCreateTime(Instant.now()).build())
        .build();
  }
}
