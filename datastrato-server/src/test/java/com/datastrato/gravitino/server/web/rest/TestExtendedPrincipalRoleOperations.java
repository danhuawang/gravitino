/*
 * Copyright 2026 Datastrato Inc.
 */
package com.datastrato.gravitino.server.web.rest;

import static javax.ws.rs.client.Entity.entity;
import static org.apache.gravitino.Configs.ENABLE_AUTHORIZATION;
import static org.apache.gravitino.Configs.TREE_LOCK_CLEAN_INTERVAL;
import static org.apache.gravitino.Configs.TREE_LOCK_MAX_NODE_IN_MEMORY;
import static org.apache.gravitino.Configs.TREE_LOCK_MIN_NODE_IN_MEMORY;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.datastrato.gravitino.ExtendedDatastratoGravitinoEnv;
import com.datastrato.gravitino.authorization.DatastratoAccessControlDispatcher;
import com.datastrato.gravitino.authorization.RoleAssignment;
import com.datastrato.gravitino.dto.authorization.RoleAssignmentDTO;
import com.datastrato.gravitino.dto.authorization.RoleSummaryDTO;
import com.datastrato.gravitino.dto.requests.RoleAssignmentRequest;
import com.datastrato.gravitino.dto.responses.RoleAssignmentListResponse;
import com.datastrato.gravitino.dto.responses.RoleSummaryListResponse;
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
import org.apache.gravitino.RelationalEntity;
import org.apache.gravitino.SupportsRelationOperations;
import org.apache.gravitino.authorization.Group;
import org.apache.gravitino.authorization.Owner;
import org.apache.gravitino.authorization.Privilege;
import org.apache.gravitino.authorization.Privileges;
import org.apache.gravitino.authorization.Role;
import org.apache.gravitino.authorization.SecurableObjects;
import org.apache.gravitino.authorization.User;
import org.apache.gravitino.connector.PropertiesMetadata;
import org.apache.gravitino.dto.responses.BaseResponse;
import org.apache.gravitino.dto.responses.ErrorConstants;
import org.apache.gravitino.dto.responses.ErrorResponse;
import org.apache.gravitino.exceptions.NoSuchUserException;
import org.apache.gravitino.exceptions.NotFoundException;
import org.apache.gravitino.lock.LockManager;
import org.apache.gravitino.meta.AuditInfo;
import org.apache.gravitino.meta.BaseMetalake;
import org.apache.gravitino.meta.GroupEntity;
import org.apache.gravitino.meta.RoleEntity;
import org.apache.gravitino.meta.UserEntity;
import org.apache.gravitino.rest.RESTUtils;
import org.apache.gravitino.server.authorization.annotations.AuthorizationExpression;
import org.apache.gravitino.server.authorization.annotations.AuthorizationMetadata;
import org.apache.gravitino.utils.NameIdentifierUtil;
import org.glassfish.hk2.utilities.binding.AbstractBinder;
import org.glassfish.jersey.server.ResourceConfig;
import org.glassfish.jersey.test.JerseyTest;
import org.glassfish.jersey.test.TestProperties;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

/** Tests REST operations that list roles and their principal assignments. */
public class TestExtendedPrincipalRoleOperations extends JerseyTest {

  private static final DatastratoAccessControlDispatcher ACCESS_CONTROL_DISPATCHER =
      mock(DatastratoAccessControlDispatcher.class);
  private static final EntityStore ENTITY_STORE = mock(EntityStore.class);
  private static final SupportsRelationOperations RELATION_OPERATIONS =
      mock(SupportsRelationOperations.class);
  private static final Instant ROLE_CREATED_AT = Instant.parse("2026-08-26T01:02:03Z");
  private static final Instant ASSIGNED_AT = Instant.parse("2026-08-27T01:02:03Z");

  private static class MockServletRequestFactory extends ServletRequestFactoryBase {
    /** Returns a mock servlet request. */
    @Override
    public HttpServletRequest get() {
      HttpServletRequest request = mock(HttpServletRequest.class);
      when(request.getRemoteUser()).thenReturn(null);
      return request;
    }
  }

  /** Initializes shared server test dependencies. */
  @BeforeAll
  public static void setup() throws IllegalAccessException {
    Config config = mock(Config.class);
    Mockito.doReturn(false).when(config).get(ENABLE_AUTHORIZATION);
    Mockito.doReturn(100000L).when(config).get(TREE_LOCK_MAX_NODE_IN_MEMORY);
    Mockito.doReturn(1000L).when(config).get(TREE_LOCK_MIN_NODE_IN_MEMORY);
    Mockito.doReturn(36000L).when(config).get(TREE_LOCK_CLEAN_INTERVAL);
    FieldUtils.writeField(GravitinoEnv.getInstance(), "config", config, true);
    FieldUtils.writeField(GravitinoEnv.getInstance(), "lockManager", new LockManager(config), true);
    FieldUtils.writeField(GravitinoEnv.getInstance(), "entityStore", ENTITY_STORE, true);
    FieldUtils.writeField(
        ExtendedDatastratoGravitinoEnv.getInstance(),
        "accessControlDispatcher",
        ACCESS_CONTROL_DISPATCHER,
        true);
  }

  /** Resets mocks before each test. */
  @BeforeEach
  public void resetMocks() throws IOException {
    Mockito.reset(ACCESS_CONTROL_DISPATCHER, ENTITY_STORE, RELATION_OPERATIONS);
    when(ENTITY_STORE.relationOperations()).thenReturn(RELATION_OPERATIONS);
    mockInUseMetalake();
  }

  /** Configures the Jersey test application. */
  @Override
  protected Application configure() {
    try {
      forceSet(
          TestProperties.CONTAINER_PORT, String.valueOf(RESTUtils.findAvailablePort(2000, 3000)));
    } catch (IOException e) {
      throw new RuntimeException(e);
    }

    ResourceConfig resourceConfig = new ResourceConfig();
    resourceConfig.register(ExtendedPrincipalRoleOperations.class);
    resourceConfig.register(
        new AbstractBinder() {
          /** Registers the mock servlet request factory. */
          @Override
          protected void configure() {
            bindFactory(MockServletRequestFactory.class).to(HttpServletRequest.class);
          }
        });
    return resourceConfig;
  }

  /** Tests assigning multiple roles to multiple users and groups. */
  @Test
  public void testAssignRolesToPrincipals() {
    RoleAssignmentRequest request =
        new RoleAssignmentRequest(
            Lists.newArrayList("reader", "writer"),
            Lists.newArrayList("alice", "bob"),
            Lists.newArrayList("analysts", "admins"));

    Response response =
        target("/web/security/metalakes/metalake1/roles/assignments")
            .request(MediaType.APPLICATION_JSON_TYPE)
            .accept("application/vnd.gravitino.v1+json")
            .put(entity(request, MediaType.APPLICATION_JSON_TYPE));

    Assertions.assertEquals(Response.Status.OK.getStatusCode(), response.getStatus());
    BaseResponse baseResponse = response.readEntity(BaseResponse.class);
    Assertions.assertEquals(0, baseResponse.getCode());
    verify(ACCESS_CONTROL_DISPATCHER)
        .assignRolesToPrincipals(
            "metalake1",
            Lists.newArrayList("reader", "writer"),
            Lists.newArrayList("alice", "bob"),
            Lists.newArrayList("analysts", "admins"));
  }

  /** Tests rejecting an assignment request without roles. */
  @Test
  public void testAssignRolesToPrincipalsRejectsEmptyRoles() {
    RoleAssignmentRequest request =
        new RoleAssignmentRequest(Collections.emptyList(), Lists.newArrayList("alice"), null);

    Response response =
        target("/web/security/metalakes/metalake1/roles/assignments")
            .request(MediaType.APPLICATION_JSON_TYPE)
            .accept("application/vnd.gravitino.v1+json")
            .put(entity(request, MediaType.APPLICATION_JSON_TYPE));

    Assertions.assertEquals(Response.Status.BAD_REQUEST.getStatusCode(), response.getStatus());
    verify(ACCESS_CONTROL_DISPATCHER, never()).assignRolesToPrincipals(any(), any(), any(), any());
  }

  /** Tests returning not found when a requested principal does not exist. */
  @Test
  public void testAssignRolesToPrincipalsReturnsNotFound() {
    RoleAssignmentRequest request =
        new RoleAssignmentRequest(
            Lists.newArrayList("reader"),
            Lists.newArrayList("alice"),
            Lists.newArrayList("missing-group"));
    doThrow(new NotFoundException("Group missing-group does not exist in the IdP"))
        .when(ACCESS_CONTROL_DISPATCHER)
        .assignRolesToPrincipals(any(), any(), any(), any());

    Response response =
        target("/web/security/metalakes/metalake1/roles/assignments")
            .request(MediaType.APPLICATION_JSON_TYPE)
            .accept("application/vnd.gravitino.v1+json")
            .put(entity(request, MediaType.APPLICATION_JSON_TYPE));

    Assertions.assertEquals(Response.Status.NOT_FOUND.getStatusCode(), response.getStatus());
    ErrorResponse errorResponse = response.readEntity(ErrorResponse.class);
    Assertions.assertEquals(ErrorConstants.NOT_FOUND_CODE, errorResponse.getCode());
    Assertions.assertEquals(NotFoundException.class.getSimpleName(), errorResponse.getType());
  }

  /** Tests listing all visible roles with owners and direct principal counts. */
  @Test
  public void testListRoleSummaries() throws IOException {
    when(ACCESS_CONTROL_DISPATCHER.listRolesWithSecurableObjects("metalake1"))
        .thenReturn(new Role[] {buildRole("role1"), buildRole("role2"), buildRole("role3")});
    User user1 = mockUser("user1", Lists.newArrayList("role1", "role2"));
    User user2 = mockUser("user2", Lists.newArrayList("role1"));
    when(ACCESS_CONTROL_DISPATCHER.listUsers("metalake1")).thenReturn(new User[] {user1, user2});
    Group group1 = mockGroup("group1", Lists.newArrayList("role2"));
    when(ACCESS_CONTROL_DISPATCHER.listGroups("metalake1")).thenReturn(new Group[] {group1});

    UserEntity owner = mock(UserEntity.class);
    when(owner.name()).thenReturn("owner1");
    RelationalEntity<UserEntity> ownerRelation =
        new RelationalEntity<>(
            SupportsRelationOperations.Type.OWNER_REL,
            NameIdentifierUtil.ofRole("metalake1", "role1"),
            Entity.EntityType.ROLE,
            owner);
    GroupEntity groupOwner = mock(GroupEntity.class);
    when(groupOwner.name()).thenReturn("ownerGroup");
    RelationalEntity<GroupEntity> groupOwnerRelation =
        new RelationalEntity<>(
            SupportsRelationOperations.Type.OWNER_REL,
            NameIdentifierUtil.ofRole("metalake1", "role2"),
            Entity.EntityType.ROLE,
            groupOwner);
    when(RELATION_OPERATIONS.batchListEntitiesByRelation(
            eq(SupportsRelationOperations.Type.OWNER_REL), anyList(), eq(Entity.EntityType.ROLE)))
        .thenReturn(Lists.newArrayList(ownerRelation, groupOwnerRelation));

    Response response =
        target("/web/security/metalakes/metalake1/roles")
            .request(MediaType.APPLICATION_JSON_TYPE)
            .accept("application/vnd.gravitino.v1+json")
            .get();

    Assertions.assertEquals(Response.Status.OK.getStatusCode(), response.getStatus());
    RoleSummaryListResponse result = response.readEntity(RoleSummaryListResponse.class);
    result.validate();
    Assertions.assertEquals(3, result.getRoles().length);
    assertRoleSummary(result.getRoles()[0], "role1", "owner1", Owner.Type.USER, 2, 0);
    assertRoleSummary(result.getRoles()[1], "role2", "ownerGroup", Owner.Type.GROUP, 1, 1);
    Assertions.assertEquals("role3", result.getRoles()[2].getRole());
    Assertions.assertNull(result.getRoles()[2].getOwner());
    Assertions.assertEquals(0, result.getRoles()[2].getUserCount());
    Assertions.assertEquals(0, result.getRoles()[2].getGroupCount());
    Assertions.assertEquals(0, result.getRoles()[2].getAssignCount());
    Assertions.assertEquals(ROLE_CREATED_AT, result.getRoles()[2].getCreateTime());
    verify(RELATION_OPERATIONS)
        .batchListEntitiesByRelation(
            eq(SupportsRelationOperations.Type.OWNER_REL),
            argThat(identifiers -> identifiers.size() == 3),
            eq(Entity.EntityType.ROLE));
  }

  /** Tests listing role summaries when the metalake has no roles. */
  @Test
  public void testListRoleSummariesWithEmptyResult() throws IOException {
    when(ACCESS_CONTROL_DISPATCHER.listRolesWithSecurableObjects("metalake1"))
        .thenReturn(new Role[0]);
    when(ACCESS_CONTROL_DISPATCHER.listUsers("metalake1")).thenReturn(new User[0]);
    when(ACCESS_CONTROL_DISPATCHER.listGroups("metalake1")).thenReturn(new Group[0]);

    Response response =
        target("/web/security/metalakes/metalake1/roles")
            .request(MediaType.APPLICATION_JSON_TYPE)
            .accept("application/vnd.gravitino.v1+json")
            .get();

    Assertions.assertEquals(Response.Status.OK.getStatusCode(), response.getStatus());
    RoleSummaryListResponse result = response.readEntity(RoleSummaryListResponse.class);
    Assertions.assertEquals(0, result.getRoles().length);
    verify(RELATION_OPERATIONS, never())
        .batchListEntitiesByRelation(any(), anyList(), eq(Entity.EntityType.ROLE));
  }

  /** Tests role summary error responses. */
  @Test
  public void testListRoleSummariesWithException() {
    doThrow(new RuntimeException("mock error"))
        .when(ACCESS_CONTROL_DISPATCHER)
        .listRolesWithSecurableObjects("metalake1");

    Response response =
        target("/web/security/metalakes/metalake1/roles")
            .request(MediaType.APPLICATION_JSON_TYPE)
            .accept("application/vnd.gravitino.v1+json")
            .get();

    Assertions.assertEquals(
        Response.Status.INTERNAL_SERVER_ERROR.getStatusCode(), response.getStatus());
    ErrorResponse error = response.readEntity(ErrorResponse.class);
    Assertions.assertEquals(ErrorConstants.INTERNAL_ERROR_CODE, error.getCode());
    Assertions.assertEquals(RuntimeException.class.getSimpleName(), error.getType());
  }

  /** Tests listing role assignments for a user. */
  @Test
  public void testListUserRoleAssignments() {
    when(ACCESS_CONTROL_DISPATCHER.listRoleAssignmentsByUser("metalake1", "user1"))
        .thenReturn(new RoleAssignment[] {buildAssignment("role1")});

    Response response =
        target("/web/security/metalakes/metalake1/users/user1/roles")
            .request(MediaType.APPLICATION_JSON_TYPE)
            .accept("application/vnd.gravitino.v1+json")
            .get();

    Assertions.assertEquals(Response.Status.OK.getStatusCode(), response.getStatus());
    RoleAssignmentListResponse result = response.readEntity(RoleAssignmentListResponse.class);
    Assertions.assertEquals(0, result.getCode());
    assertAssignment(result.getRoles()[0]);
    verify(ACCESS_CONTROL_DISPATCHER).listRoleAssignmentsByUser("metalake1", "user1");
  }

  /** Tests listing role assignments for a group. */
  @Test
  public void testListGroupRoleAssignments() {
    when(ACCESS_CONTROL_DISPATCHER.listRoleAssignmentsByGroup("metalake1", "group1"))
        .thenReturn(new RoleAssignment[] {buildAssignment("role1")});

    Response response =
        target("/web/security/metalakes/metalake1/groups/group1/roles")
            .request(MediaType.APPLICATION_JSON_TYPE)
            .accept("application/vnd.gravitino.v1+json")
            .get();

    Assertions.assertEquals(Response.Status.OK.getStatusCode(), response.getStatus());
    RoleAssignmentListResponse result = response.readEntity(RoleAssignmentListResponse.class);
    Assertions.assertEquals(1, result.getRoles().length);
    assertAssignment(result.getRoles()[0]);
    verify(ACCESS_CONTROL_DISPATCHER).listRoleAssignmentsByGroup("metalake1", "group1");
  }

  /** Tests user assignment error responses. */
  @Test
  public void testListUserRoleAssignmentsWithException() {
    doThrow(new NoSuchUserException("user1 does not exist"))
        .when(ACCESS_CONTROL_DISPATCHER)
        .listRoleAssignmentsByUser("metalake1", "user1");

    Response response =
        target("/web/security/metalakes/metalake1/users/user1/roles")
            .request(MediaType.APPLICATION_JSON_TYPE)
            .accept("application/vnd.gravitino.v1+json")
            .get();

    Assertions.assertEquals(Response.Status.NOT_FOUND.getStatusCode(), response.getStatus());
    ErrorResponse error = response.readEntity(ErrorResponse.class);
    Assertions.assertEquals(ErrorConstants.NOT_FOUND_CODE, error.getCode());
    Assertions.assertEquals(NoSuchUserException.class.getSimpleName(), error.getType());
  }

  /** Tests authorization metadata on the role-assignment endpoints. */
  @Test
  public void testAuthorizationAnnotations() throws NoSuchMethodException {
    Method listRoleSummaries =
        ExtendedPrincipalRoleOperations.class.getMethod("listRoleSummaries", String.class);
    AuthorizationExpression listExpression =
        listRoleSummaries.getAnnotation(AuthorizationExpression.class);
    Assertions.assertNotNull(listExpression);
    Assertions.assertEquals("", listExpression.expression());
    Assertions.assertEquals(
        Entity.EntityType.METALAKE,
        listRoleSummaries.getParameters()[0].getAnnotation(AuthorizationMetadata.class).type());
    Method assignRoles =
        ExtendedPrincipalRoleOperations.class.getMethod(
            "assignRolesToPrincipals", String.class, RoleAssignmentRequest.class);
    AuthorizationExpression assignExpression =
        assignRoles.getAnnotation(AuthorizationExpression.class);
    Assertions.assertEquals(
        "METALAKE::OWNER || (METALAKE::MANAGE_GRANTS && METALAKE::CREATE_ROLE"
            + " && METALAKE::MANAGE_USERS && METALAKE::MANAGE_GROUPS)",
        assignExpression.expression());
    Assertions.assertEquals(
        Entity.EntityType.METALAKE,
        assignRoles.getParameters()[0].getAnnotation(AuthorizationMetadata.class).type());

    assertAuthorizationMetadata(
        "listUserRoleAssignments",
        "METALAKE::OWNER || METALAKE::MANAGE_USERS || USER::SELF",
        Entity.EntityType.USER);
    assertAuthorizationMetadata(
        "listGroupRoleAssignments",
        "METALAKE::OWNER || METALAKE::MANAGE_GROUPS || GROUP::SELF",
        Entity.EntityType.GROUP);
  }

  private void assertRoleSummary(
      RoleSummaryDTO summary,
      String role,
      String owner,
      Owner.Type ownerType,
      int userCount,
      int groupCount) {
    Assertions.assertEquals(role, summary.getRole());
    Assertions.assertEquals(owner, summary.getOwner().name());
    Assertions.assertEquals(ownerType, summary.getOwner().type());
    Assertions.assertEquals(userCount, summary.getUserCount());
    Assertions.assertEquals(groupCount, summary.getGroupCount());
    Assertions.assertEquals(userCount + groupCount, summary.getAssignCount());
    Assertions.assertEquals(ROLE_CREATED_AT, summary.getCreateTime());
  }

  private void assertAssignment(RoleAssignmentDTO assignment) {
    Role role = assignment.getRole();
    Assertions.assertEquals("role1", role.name());
    Assertions.assertEquals(1, role.securableObjects().size());
    Assertions.assertEquals("catalog1", role.securableObjects().get(0).fullName());
    Assertions.assertEquals(
        Privilege.Name.USE_CATALOG, role.securableObjects().get(0).privileges().get(0).name());
    Assertions.assertEquals(ASSIGNED_AT, assignment.getAssignmentAudit().lastModifiedTime());
    Assertions.assertEquals("admin", assignment.getAssignmentAudit().lastModifier());
  }

  private RoleAssignment buildAssignment(String roleName) {
    AuditInfo roleAudit =
        AuditInfo.builder().withCreator("creator").withCreateTime(Instant.EPOCH).build();
    RoleEntity role =
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

  private Role buildRole(String roleName) {
    return RoleEntity.builder()
        .withId(1L)
        .withName(roleName)
        .withProperties(Collections.emptyMap())
        .withSecurableObjects(Collections.emptyList())
        .withAuditInfo(
            AuditInfo.builder().withCreator("creator").withCreateTime(ROLE_CREATED_AT).build())
        .build();
  }

  private User mockUser(String name, List<String> roles) {
    User user = mock(User.class);
    when(user.name()).thenReturn(name);
    when(user.roles()).thenReturn(roles);
    return user;
  }

  private Group mockGroup(String name, List<String> roles) {
    Group group = mock(Group.class);
    when(group.name()).thenReturn(name);
    when(group.roles()).thenReturn(roles);
    return group;
  }

  private void assertAuthorizationMetadata(
      String methodName, String expectedExpression, Entity.EntityType principalType)
      throws NoSuchMethodException {
    Method method =
        ExtendedPrincipalRoleOperations.class.getMethod(methodName, String.class, String.class);
    AuthorizationExpression expression = method.getAnnotation(AuthorizationExpression.class);
    Assertions.assertNotNull(expression);
    Assertions.assertEquals(expectedExpression, expression.expression());

    Parameter[] parameters = method.getParameters();
    Assertions.assertEquals(
        Entity.EntityType.METALAKE,
        parameters[0].getAnnotation(AuthorizationMetadata.class).type());
    Assertions.assertEquals(
        principalType, parameters[1].getAnnotation(AuthorizationMetadata.class).type());
  }

  private void mockInUseMetalake() throws IOException {
    BaseMetalake metalake = mock(BaseMetalake.class);
    PropertiesMetadata propertiesMetadata = mock(PropertiesMetadata.class);
    when(propertiesMetadata.getOrDefault(any(), any())).thenReturn(true);
    when(metalake.propertiesMetadata()).thenReturn(propertiesMetadata);
    when(ENTITY_STORE.get(any(), any(), any())).thenReturn(metalake);
  }
}
