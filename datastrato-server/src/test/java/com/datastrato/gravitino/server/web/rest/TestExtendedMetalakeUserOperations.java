/*
 * Copyright 2026 Datastrato Inc.
 */
package com.datastrato.gravitino.server.web.rest;

import static javax.ws.rs.client.Entity.entity;
import static org.apache.gravitino.Configs.TREE_LOCK_CLEAN_INTERVAL;
import static org.apache.gravitino.Configs.TREE_LOCK_MAX_NODE_IN_MEMORY;
import static org.apache.gravitino.Configs.TREE_LOCK_MIN_NODE_IN_MEMORY;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.reset;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.datastrato.gravitino.ExtendedDatastratoGravitinoEnv;
import com.datastrato.gravitino.authorization.DatastratoAccessControlDispatcher;
import com.datastrato.gravitino.authorization.IdpNameStatus;
import com.datastrato.gravitino.authorization.RoleAssignment;
import com.datastrato.gravitino.authorization.UserWithGroups;
import com.datastrato.gravitino.dto.authorization.ExtendedGroupDTO;
import com.datastrato.gravitino.dto.authorization.ExtendedUserDTO;
import com.datastrato.gravitino.dto.authorization.IdentitySource;
import com.datastrato.gravitino.dto.authorization.RoleAssignmentDetailsDTO;
import com.datastrato.gravitino.dto.requests.LocalUserAddRequest;
import com.datastrato.gravitino.dto.requests.UserEnabledBatchUpdateRequest;
import com.datastrato.gravitino.dto.responses.ExtendedGroupListResponse;
import com.datastrato.gravitino.dto.responses.ExtendedUserListResponse;
import com.datastrato.gravitino.dto.responses.ExtendedUserResponse;
import com.datastrato.gravitino.dto.responses.IdpUserNameListResponse;
import com.datastrato.gravitino.dto.responses.RoleAssignmentDetailsListResponse;
import com.google.common.collect.Lists;
import java.io.IOException;
import java.lang.reflect.Method;
import java.lang.reflect.Parameter;
import java.time.Instant;
import java.util.Collections;
import java.util.List;
import javax.servlet.http.HttpServletRequest;
import javax.ws.rs.core.Application;
import javax.ws.rs.core.MediaType;
import javax.ws.rs.core.Response;
import org.apache.commons.lang3.reflect.FieldUtils;
import org.apache.gravitino.Config;
import org.apache.gravitino.Entity;
import org.apache.gravitino.EntityStore;
import org.apache.gravitino.GravitinoEnv;
import org.apache.gravitino.Namespace;
import org.apache.gravitino.RelationalEntity;
import org.apache.gravitino.SupportsRelationOperations;
import org.apache.gravitino.authorization.Owner;
import org.apache.gravitino.authorization.Privileges;
import org.apache.gravitino.authorization.Role;
import org.apache.gravitino.authorization.SecurableObjects;
import org.apache.gravitino.authorization.User;
import org.apache.gravitino.connector.PropertiesMetadata;
import org.apache.gravitino.dto.responses.ErrorConstants;
import org.apache.gravitino.dto.responses.ErrorResponse;
import org.apache.gravitino.dto.responses.NameListResponse;
import org.apache.gravitino.exceptions.NoSuchEntityException;
import org.apache.gravitino.exceptions.NoSuchMetalakeException;
import org.apache.gravitino.exceptions.NoSuchUserException;
import org.apache.gravitino.lock.LockManager;
import org.apache.gravitino.meta.AuditInfo;
import org.apache.gravitino.meta.BaseMetalake;
import org.apache.gravitino.meta.GroupEntity;
import org.apache.gravitino.meta.RoleEntity;
import org.apache.gravitino.meta.UserEntity;
import org.apache.gravitino.rest.RESTUtils;
import org.apache.gravitino.server.authorization.annotations.AuthorizationExpression;
import org.apache.gravitino.server.authorization.annotations.AuthorizationMetadata;
import org.apache.gravitino.server.authorization.expression.AuthorizationExpressionConstants;
import org.apache.gravitino.utils.NameIdentifierUtil;
import org.glassfish.hk2.utilities.binding.AbstractBinder;
import org.glassfish.jersey.server.ResourceConfig;
import org.glassfish.jersey.test.JerseyTest;
import org.glassfish.jersey.test.TestProperties;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.MockedStatic;
import org.mockito.Mockito;

public class TestExtendedMetalakeUserOperations extends JerseyTest {

  private static final DatastratoAccessControlDispatcher accessControlDispatcher =
      mock(DatastratoAccessControlDispatcher.class);
  private static final EntityStore entityStore = mock(EntityStore.class);
  private static final SupportsRelationOperations relationOperations =
      mock(SupportsRelationOperations.class);
  private static final Instant ASSIGNED_AT = Instant.parse("2026-08-27T01:02:03Z");

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
    reset(accessControlDispatcher, entityStore, relationOperations);
    when(entityStore.relationOperations()).thenReturn(relationOperations);
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
    resourceConfig.register(ExtendedMetalakeUserOperations.class);
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
    when(accessControlDispatcher.listUsersWithGroups(metalake))
        .thenReturn(
            List.of(
                new UserWithGroups(buildUser("lee.p"), List.of(), IdentitySource.LOCAL),
                new UserWithGroups(buildUser("dana.k"), List.of(), IdentitySource.PROVISIONED),
                new UserWithGroups(buildUser("jordan.m"), List.of(), null)));

    Response response =
        target("/internal/security/metalakes/" + metalake + "/users")
            .request(MediaType.APPLICATION_JSON_TYPE)
            .accept("application/vnd.gravitino.v1+json")
            .get();

    Assertions.assertEquals(Response.Status.OK.getStatusCode(), response.getStatus());
    ExtendedUserListResponse body = response.readEntity(ExtendedUserListResponse.class);
    Assertions.assertEquals(0, body.getCode());
    Assertions.assertEquals(3, body.getUsers().length);
    Assertions.assertEquals("lee.p", body.getUsers()[0].name());
    Assertions.assertEquals(IdentitySource.LOCAL, body.getUsers()[0].origin());
    Assertions.assertEquals("dana.k", body.getUsers()[1].name());
    Assertions.assertEquals(IdentitySource.PROVISIONED, body.getUsers()[1].origin());
    Assertions.assertNull(body.getUsers()[2].origin());
  }

  @Test
  public void testListUsersRequiresServiceAdminAndFiltersByPrivilege() throws Exception {
    String metalake = "metalake";
    when(accessControlDispatcher.listUsersWithGroups(metalake))
        .thenReturn(
            List.of(
                new UserWithGroups(buildUser("visible"), List.of(), IdentitySource.LOCAL),
                new UserWithGroups(buildUser("hidden"), List.of(), IdentitySource.LOCAL)));
    Method method = ExtendedMetalakeUserOperations.class.getMethod("listUsers", String.class);
    Assertions.assertEquals(
        "SERVICE_ADMIN", method.getAnnotation(AuthorizationExpression.class).expression());

    ExtendedMetalakeUserOperations operations = new ExtendedMetalakeUserOperations();
    FieldUtils.writeField(operations, "httpRequest", mock(HttpServletRequest.class), true);
    try (MockedStatic<MetadataListingHelper> listing =
        Mockito.mockStatic(MetadataListingHelper.class)) {
      listing
          .when(
              () ->
                  MetadataListingHelper.filterByExpression(
                      Mockito.eq(metalake),
                      Mockito.eq(
                          AuthorizationExpressionConstants.LOAD_USER_AUTHORIZATION_EXPRESSION),
                      Mockito.eq(Entity.EntityType.USER),
                      Mockito.any(ExtendedUserDTO[].class),
                      Mockito.any()))
          .thenAnswer(
              invocation -> {
                ExtendedUserDTO[] candidates = invocation.getArgument(3);
                return new ExtendedUserDTO[] {candidates[0]};
              });

      Response response = operations.listUsers(metalake);

      Assertions.assertEquals(Response.Status.OK.getStatusCode(), response.getStatus());
      ExtendedUserListResponse body = (ExtendedUserListResponse) response.getEntity();
      Assertions.assertEquals(1, body.getUsers().length);
      Assertions.assertEquals("visible", body.getUsers()[0].name());
    }
  }

  @Test
  public void testBatchUpdateUserEnabled() {
    String metalake = "metalake";
    List<String> users = Lists.newArrayList("alice", "bob");
    when(accessControlDispatcher.batchUpdateUserEnabled(eq(metalake), eq(users), eq(false)))
        .thenReturn(users);

    Response response =
        target("/internal/security/metalakes/" + metalake + "/users/enabled")
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
        target("/internal/security/metalakes/" + metalake + "/users/enabled")
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
        target("/internal/security/metalakes/" + metalake + "/users/enabled")
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
  public void testGetUser() {
    ExtendedUserDTO user =
        ExtendedUserDTO.from(buildUser("dana.k"), IdentitySource.PROVISIONED, null);
    when(accessControlDispatcher.getExtendedUser("metalake", "dana.k")).thenReturn(user);
    Response response = get("/internal/security/metalakes/metalake/users/dana.k");
    Assertions.assertEquals(Response.Status.OK.getStatusCode(), response.getStatus());
    ExtendedUserResponse body = response.readEntity(ExtendedUserResponse.class);
    Assertions.assertEquals("dana.k", body.getUser().name());
    Assertions.assertEquals(IdentitySource.PROVISIONED, body.getUser().origin());
  }

  @Test
  public void testGetUserAuthorization() throws NoSuchMethodException {
    Method method =
        ExtendedMetalakeUserOperations.class.getMethod("getUser", String.class, String.class);
    AuthorizationExpression expression = method.getAnnotation(AuthorizationExpression.class);
    Assertions.assertNotNull(expression);
    Assertions.assertEquals(
        "METALAKE::OWNER || METALAKE::MANAGE_USERS || USER::SELF", expression.expression());
  }

  @Test
  public void testListUserGroupsAuthorization() throws NoSuchMethodException {
    Method method =
        ExtendedMetalakeUserOperations.class.getMethod(
            "listGroupsForUser", String.class, String.class);
    AuthorizationExpression expression = method.getAnnotation(AuthorizationExpression.class);
    Assertions.assertNotNull(expression);
    Assertions.assertEquals(
        "(SERVICE_ADMIN && METALAKE::OWNER) || USER::SELF", expression.expression());
  }

  @Test
  public void testListUsersAuthorization() throws NoSuchMethodException {
    Method method = ExtendedMetalakeUserOperations.class.getMethod("listUsers", String.class);
    AuthorizationExpression expression = method.getAnnotation(AuthorizationExpression.class);
    Assertions.assertNotNull(expression);
    Assertions.assertEquals("SERVICE_ADMIN", expression.expression());
  }

  @Test
  public void testAddAndEnabledAuthorization() throws NoSuchMethodException {
    Method addMethod =
        ExtendedMetalakeUserOperations.class.getMethod(
            "addUser", String.class, LocalUserAddRequest.class);
    Assertions.assertEquals(
        "SERVICE_ADMIN && (METALAKE::OWNER || METALAKE::MANAGE_USERS)",
        addMethod.getAnnotation(AuthorizationExpression.class).expression());

    Method enabledMethod =
        ExtendedMetalakeUserOperations.class.getMethod(
            "batchUpdateUserEnabled", String.class, UserEnabledBatchUpdateRequest.class);
    Assertions.assertEquals(
        "SERVICE_ADMIN && (METALAKE::OWNER || METALAKE::MANAGE_USERS)",
        enabledMethod.getAnnotation(AuthorizationExpression.class).expression());
    Assertions.assertEquals(
        Entity.EntityType.METALAKE,
        enabledMethod.getParameters()[0].getAnnotation(AuthorizationMetadata.class).type());
  }

  @Test
  public void testListUserRoleAssignments() throws IOException {
    when(accessControlDispatcher.listRoleAssignmentsByUser("metalake", "user1"))
        .thenReturn(new RoleAssignment[] {buildAssignment("role1"), buildAssignment("role2")});
    when(relationOperations.batchListEntitiesByRelation(
            eq(SupportsRelationOperations.Type.ROLE_USER_REL),
            anyList(),
            eq(Entity.EntityType.ROLE)))
        .thenReturn(
            Lists.newArrayList(
                roleRelation(
                    SupportsRelationOperations.Type.ROLE_USER_REL, "role1", buildUser("user2")),
                roleRelation(
                    SupportsRelationOperations.Type.ROLE_USER_REL, "role1", buildUser("user1"))));
    when(relationOperations.batchListEntitiesByRelation(
            eq(SupportsRelationOperations.Type.ROLE_GROUP_REL),
            anyList(),
            eq(Entity.EntityType.ROLE)))
        .thenReturn(
            Lists.newArrayList(
                roleRelation(
                    SupportsRelationOperations.Type.ROLE_GROUP_REL,
                    "role1",
                    buildGroup("group1"))));
    when(relationOperations.batchListEntitiesByRelation(
            eq(SupportsRelationOperations.Type.OWNER_REL), anyList(), eq(Entity.EntityType.ROLE)))
        .thenReturn(
            Lists.newArrayList(
                roleRelation(
                    SupportsRelationOperations.Type.OWNER_REL, "role1", buildUser("owner1"))));

    Response response = get("/internal/security/metalakes/metalake/users/user1/roles");

    Assertions.assertEquals(Response.Status.OK.getStatusCode(), response.getStatus());
    RoleAssignmentDetailsListResponse result =
        response.readEntity(RoleAssignmentDetailsListResponse.class);
    Assertions.assertEquals(0, result.getCode());
    Assertions.assertEquals(2, result.getRoles().length);
    assertAssignment(result.getRoles()[0]);
    Assertions.assertEquals("role2", result.getRoles()[1].getRole().getName());
    Assertions.assertEquals(0, result.getRoles()[1].getMembers().getUsers().length);
    Assertions.assertEquals(0, result.getRoles()[1].getMembers().getGroups().length);
    Assertions.assertNull(result.getRoles()[1].getOwner());
    verify(accessControlDispatcher).listRoleAssignmentsByUser("metalake", "user1");
    verifyBatchRelationLookup(SupportsRelationOperations.Type.ROLE_USER_REL, 2);
    verifyBatchRelationLookup(SupportsRelationOperations.Type.ROLE_GROUP_REL, 2);
    verifyBatchRelationLookup(SupportsRelationOperations.Type.OWNER_REL, 2);
  }

  @Test
  public void testListUserRoleAssignmentsWithEmptyResult() throws IOException {
    when(accessControlDispatcher.listRoleAssignmentsByUser("metalake", "user1"))
        .thenReturn(new RoleAssignment[0]);

    Response response = get("/internal/security/metalakes/metalake/users/user1/roles");

    Assertions.assertEquals(Response.Status.OK.getStatusCode(), response.getStatus());
    RoleAssignmentDetailsListResponse result =
        response.readEntity(RoleAssignmentDetailsListResponse.class);
    Assertions.assertEquals(0, result.getRoles().length);
    verify(relationOperations, never())
        .batchListEntitiesByRelation(any(), anyList(), eq(Entity.EntityType.ROLE));
  }

  @Test
  public void testListUserRoleAssignmentsWithException() {
    doThrow(new NoSuchUserException("user1 does not exist"))
        .when(accessControlDispatcher)
        .listRoleAssignmentsByUser("metalake", "user1");

    Response response = get("/internal/security/metalakes/metalake/users/user1/roles");

    Assertions.assertEquals(Response.Status.NOT_FOUND.getStatusCode(), response.getStatus());
    ErrorResponse error = response.readEntity(ErrorResponse.class);
    Assertions.assertEquals(ErrorConstants.NOT_FOUND_CODE, error.getCode());
    Assertions.assertEquals(NoSuchUserException.class.getSimpleName(), error.getType());
  }

  @Test
  public void testListUserRoleAssignmentsAuthorization() throws NoSuchMethodException {
    Method method =
        ExtendedMetalakeUserOperations.class.getMethod(
            "listUserRoleAssignments", String.class, String.class);
    AuthorizationExpression expression = method.getAnnotation(AuthorizationExpression.class);
    Assertions.assertNotNull(expression);
    Assertions.assertEquals(
        "METALAKE::OWNER || METALAKE::MANAGE_USERS || USER::SELF", expression.expression());

    Parameter[] parameters = method.getParameters();
    Assertions.assertEquals(
        Entity.EntityType.METALAKE,
        parameters[0].getAnnotation(AuthorizationMetadata.class).type());
    Assertions.assertEquals(
        Entity.EntityType.USER, parameters[1].getAnnotation(AuthorizationMetadata.class).type());
  }

  @Test
  public void testListUserGroups() {
    ExtendedGroupDTO group = ExtendedGroupDTO.from(buildGroup("contractors"), true, 0);
    when(accessControlDispatcher.listExtendedGroupsForUser("metalake", "alice"))
        .thenReturn(new ExtendedGroupDTO[] {group});
    ExtendedGroupListResponse body =
        get("/internal/security/metalakes/metalake/users/alice/groups")
            .readEntity(ExtendedGroupListResponse.class);
    Assertions.assertEquals(1, body.getGroups().length);
    Assertions.assertEquals("contractors", body.getGroups()[0].name());
    Assertions.assertEquals(IdentitySource.LOCAL, body.getGroups()[0].origin());
  }

  @Test
  public void testAddLocalUser() {
    String metalake = "metalake";
    User added = buildUser("jordan.reyes");
    when(accessControlDispatcher.addLocalUser(
            eq(metalake), eq("jordan.reyes"), eq(List.of("Analyst")), eq(true)))
        .thenReturn(added);

    Response response =
        target("/internal/security/metalakes/" + metalake + "/users")
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
  }

  @Test
  public void testListIdpUsers() {
    when(accessControlDispatcher.listIdpUsers("metalake"))
        .thenReturn(
            new IdpNameStatus[] {
              new IdpNameStatus("alice", true), new IdpNameStatus("bob", false)
            });

    Response response = get("/internal/security/metalakes/metalake/users/idp");
    Assertions.assertEquals(Response.Status.OK.getStatusCode(), response.getStatus());
    IdpUserNameListResponse body = response.readEntity(IdpUserNameListResponse.class);
    Assertions.assertEquals("alice", body.getUsers()[0].getName());
    Assertions.assertTrue(body.getUsers()[0].isStatus());
    Assertions.assertEquals("bob", body.getUsers()[1].getName());
    Assertions.assertFalse(body.getUsers()[1].isStatus());
  }

  @Test
  public void testListIdpUsersAuthorization() throws NoSuchMethodException {
    Method method = ExtendedMetalakeUserOperations.class.getMethod("listIdpUsers", String.class);
    AuthorizationExpression expression = method.getAnnotation(AuthorizationExpression.class);
    Assertions.assertNotNull(expression);
    Assertions.assertEquals("SERVICE_ADMIN && METALAKE::OWNER", expression.expression());
  }

  @Test
  public void testAddLocalUserBadRequest() {
    String metalake = "metalake";
    Response response =
        target("/internal/security/metalakes/" + metalake + "/users")
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

  private void mockInUseMetalake() throws IOException {
    BaseMetalake metalake = mock(BaseMetalake.class);
    PropertiesMetadata propertiesMetadata = mock(PropertiesMetadata.class);
    when(propertiesMetadata.getOrDefault(any(), any())).thenReturn(true);
    when(metalake.propertiesMetadata()).thenReturn(propertiesMetadata);
    when(entityStore.get(any(), any(), any())).thenReturn(metalake);
  }

  private static UserEntity buildUser(String name) {
    return UserEntity.builder()
        .withId(1L)
        .withName(name)
        .withNamespace(Namespace.of("metalake", "system", "user"))
        .withRoleNames(Collections.emptyList())
        .withAuditInfo(
            AuditInfo.builder().withCreator("test").withCreateTime(Instant.now()).build())
        .build();
  }

  private static GroupEntity buildGroup(String name) {
    return GroupEntity.builder()
        .withId(1L)
        .withName(name)
        .withNamespace(Namespace.of("metalake", "system", "group"))
        .withRoleNames(Collections.emptyList())
        .withAuditInfo(
            AuditInfo.builder().withCreator("test").withCreateTime(Instant.now()).build())
        .build();
  }

  private static RoleAssignment buildAssignment(String roleName) {
    AuditInfo roleAudit =
        AuditInfo.builder().withCreator("creator").withCreateTime(Instant.EPOCH).build();
    Role role =
        RoleEntity.builder()
            .withId(1L)
            .withName(roleName)
            .withProperties(Collections.emptyMap())
            .withSecurableObjects(
                Lists.newArrayList(
                    SecurableObjects.ofCatalog(
                        "catalog1", Lists.newArrayList(Privileges.UseCatalog.allow()))))
            .withAuditInfo(roleAudit)
            .build();
    AuditInfo assignmentAudit =
        AuditInfo.builder()
            .withCreator("creator")
            .withCreateTime(Instant.EPOCH)
            .withLastModifier("admin")
            .withLastModifiedTime(ASSIGNED_AT)
            .build();
    return new RoleAssignment(role, assignmentAudit);
  }

  private void verifyBatchRelationLookup(
      SupportsRelationOperations.Type relationType, int expectedRoleCount) throws IOException {
    verify(relationOperations)
        .batchListEntitiesByRelation(
            eq(relationType),
            argThat(identifiers -> identifiers.size() == expectedRoleCount),
            eq(Entity.EntityType.ROLE));
  }

  private static RelationalEntity<UserEntity> roleRelation(
      SupportsRelationOperations.Type relationType, String role, UserEntity user) {
    return new RelationalEntity<>(
        relationType, NameIdentifierUtil.ofRole("metalake", role), Entity.EntityType.ROLE, user);
  }

  private static RelationalEntity<GroupEntity> roleRelation(
      SupportsRelationOperations.Type relationType, String role, GroupEntity group) {
    return new RelationalEntity<>(
        relationType, NameIdentifierUtil.ofRole("metalake", role), Entity.EntityType.ROLE, group);
  }

  private static void assertAssignment(RoleAssignmentDetailsDTO assignment) {
    Assertions.assertEquals("role1", assignment.getRole().getName());
    Assertions.assertArrayEquals(
        new String[] {"user1", "user2"}, assignment.getMembers().getUsers());
    Assertions.assertArrayEquals(new String[] {"group1"}, assignment.getMembers().getGroups());
    Assertions.assertEquals("owner1", assignment.getOwner().name());
    Assertions.assertEquals(Owner.Type.USER, assignment.getOwner().type());
    Assertions.assertEquals(ASSIGNED_AT, assignment.getAssignment().lastModifiedTime());
    Assertions.assertEquals("admin", assignment.getAssignment().lastModifier());
  }
}
