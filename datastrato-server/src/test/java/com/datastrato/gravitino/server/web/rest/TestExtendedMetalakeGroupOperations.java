/*
 * Copyright 2026 Datastrato Inc.
 */
package com.datastrato.gravitino.server.web.rest;

import static javax.ws.rs.client.Entity.entity;
import static org.apache.gravitino.Configs.TREE_LOCK_CLEAN_INTERVAL;
import static org.apache.gravitino.Configs.TREE_LOCK_MAX_NODE_IN_MEMORY;
import static org.apache.gravitino.Configs.TREE_LOCK_MIN_NODE_IN_MEMORY;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.reset;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.datastrato.gravitino.ExtendedDatastratoGravitinoEnv;
import com.datastrato.gravitino.authorization.DatastratoAccessControlDispatcher;
import com.datastrato.gravitino.authorization.IdpNameStatus;
import com.datastrato.gravitino.authorization.RoleAssignment;
import com.datastrato.gravitino.dto.authorization.ExtendedGroupDTO;
import com.datastrato.gravitino.dto.authorization.ExtendedUserDTO;
import com.datastrato.gravitino.dto.authorization.IdentitySource;
import com.datastrato.gravitino.dto.authorization.RoleAssignmentDetailsDTO;
import com.datastrato.gravitino.dto.requests.LocalGroupAddRequest;
import com.datastrato.gravitino.dto.responses.ExtendedGroupListResponse;
import com.datastrato.gravitino.dto.responses.ExtendedGroupResponse;
import com.datastrato.gravitino.dto.responses.ExtendedUserListResponse;
import com.datastrato.gravitino.dto.responses.IdpGroupNameListResponse;
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
import org.apache.gravitino.authorization.Group;
import org.apache.gravitino.authorization.Owner;
import org.apache.gravitino.authorization.Privileges;
import org.apache.gravitino.authorization.Role;
import org.apache.gravitino.authorization.SecurableObjects;
import org.apache.gravitino.connector.PropertiesMetadata;
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

public class TestExtendedMetalakeGroupOperations extends JerseyTest {

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
    resourceConfig.register(ExtendedMetalakeGroupOperations.class);
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
  public void testListGroups() {
    String metalake = "metalake";
    Group local = buildGroup(1L, "contractors", Collections.singletonList("pii_reader"));
    Group provisioned = buildGroup(2L, "governance", Collections.singletonList("Gov Admin"));
    Group unknown = buildGroup(3L, "analysts", Collections.singletonList("Analyst"));
    when(accessControlDispatcher.listExtendedGroups(metalake))
        .thenReturn(
            new ExtendedGroupDTO[] {
              ExtendedGroupDTO.from(local, IdentitySource.LOCAL, 12),
              ExtendedGroupDTO.from(provisioned, IdentitySource.PROVISIONED, 8),
              ExtendedGroupDTO.from(unknown, null, 0)
            });

    Response response =
        target("/internal/security/metalakes/" + metalake + "/groups")
            .request(MediaType.APPLICATION_JSON_TYPE)
            .accept("application/vnd.gravitino.v1+json")
            .get();

    Assertions.assertEquals(Response.Status.OK.getStatusCode(), response.getStatus());
    ExtendedGroupListResponse body = response.readEntity(ExtendedGroupListResponse.class);
    Assertions.assertEquals(0, body.getCode());
    Assertions.assertEquals(3, body.getGroups().length);
    Assertions.assertEquals("contractors", body.getGroups()[0].name());
    Assertions.assertEquals(IdentitySource.LOCAL, body.getGroups()[0].origin());
    Assertions.assertEquals(12, body.getGroups()[0].userCount());
    Assertions.assertEquals("governance", body.getGroups()[1].name());
    Assertions.assertEquals(IdentitySource.PROVISIONED, body.getGroups()[1].origin());
    Assertions.assertEquals(8, body.getGroups()[1].userCount());
    Assertions.assertEquals("analysts", body.getGroups()[2].name());
    Assertions.assertNull(body.getGroups()[2].origin());
    Assertions.assertEquals(0, body.getGroups()[2].userCount());
  }

  @Test
  public void testGetGroup() {
    Group governance = buildGroup(1L, "governance", Collections.singletonList("Gov Admin"));
    when(accessControlDispatcher.getExtendedGroup("metalake", "governance"))
        .thenReturn(ExtendedGroupDTO.from(governance, IdentitySource.PROVISIONED, 2));

    Response response = get("/internal/security/metalakes/metalake/groups/governance");
    Assertions.assertEquals(Response.Status.OK.getStatusCode(), response.getStatus());
    ExtendedGroupResponse body = response.readEntity(ExtendedGroupResponse.class);
    Assertions.assertEquals("governance", body.getGroup().name());
    Assertions.assertEquals(IdentitySource.PROVISIONED, body.getGroup().origin());
    Assertions.assertEquals(2, body.getGroup().userCount());
  }

  @Test
  public void testListGroupRoleAssignments() throws IOException {
    when(accessControlDispatcher.listRoleAssignmentsByGroup("metalake", "group1"))
        .thenReturn(new RoleAssignment[] {buildAssignment("role1")});
    when(relationOperations.batchListEntitiesByRelation(
            eq(SupportsRelationOperations.Type.ROLE_USER_REL),
            anyList(),
            eq(Entity.EntityType.ROLE)))
        .thenReturn(
            Lists.newArrayList(
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
                    buildGroup(2L, "group2", Collections.emptyList())),
                roleRelation(
                    SupportsRelationOperations.Type.ROLE_GROUP_REL,
                    "role1",
                    buildGroup(1L, "group1", Collections.emptyList()))));
    when(relationOperations.batchListEntitiesByRelation(
            eq(SupportsRelationOperations.Type.OWNER_REL), anyList(), eq(Entity.EntityType.ROLE)))
        .thenReturn(
            Lists.newArrayList(
                roleRelation(
                    SupportsRelationOperations.Type.OWNER_REL,
                    "role1",
                    buildGroup(3L, "owner-group", Collections.emptyList()))));

    Response response = get("/internal/security/metalakes/metalake/groups/group1/roles");

    Assertions.assertEquals(Response.Status.OK.getStatusCode(), response.getStatus());
    RoleAssignmentDetailsListResponse result =
        response.readEntity(RoleAssignmentDetailsListResponse.class);
    Assertions.assertEquals(1, result.getRoles().length);
    assertAssignment(result.getRoles()[0]);
    verify(accessControlDispatcher).listRoleAssignmentsByGroup("metalake", "group1");
    verifyBatchRelationLookup(SupportsRelationOperations.Type.ROLE_USER_REL);
    verifyBatchRelationLookup(SupportsRelationOperations.Type.ROLE_GROUP_REL);
    verifyBatchRelationLookup(SupportsRelationOperations.Type.OWNER_REL);
  }

  @Test
  public void testListGroupRoleAssignmentsAuthorization() throws NoSuchMethodException {
    Method method =
        ExtendedMetalakeGroupOperations.class.getMethod(
            "listGroupRoleAssignments", String.class, String.class);
    AuthorizationExpression expression = method.getAnnotation(AuthorizationExpression.class);
    Assertions.assertNotNull(expression);
    Assertions.assertEquals("SERVICE_ADMIN && METALAKE::OWNER", expression.expression());

    Parameter[] parameters = method.getParameters();
    Assertions.assertEquals(
        Entity.EntityType.METALAKE,
        parameters[0].getAnnotation(AuthorizationMetadata.class).type());
    Assertions.assertEquals(
        Entity.EntityType.GROUP, parameters[1].getAnnotation(AuthorizationMetadata.class).type());
  }

  @Test
  public void testAddGroupAuthorization() throws NoSuchMethodException {
    Method method =
        ExtendedMetalakeGroupOperations.class.getMethod(
            "addGroup", String.class, LocalGroupAddRequest.class);
    AuthorizationExpression expression = method.getAnnotation(AuthorizationExpression.class);
    Assertions.assertNotNull(expression);
    Assertions.assertEquals("SERVICE_ADMIN && METALAKE::OWNER", expression.expression());
  }

  @Test
  public void testListGroupUsers() {
    ExtendedUserDTO user = ExtendedUserDTO.from(buildUser("alice"), true);
    when(accessControlDispatcher.listExtendedUsersForGroup("metalake", "contractors"))
        .thenReturn(new ExtendedUserDTO[] {user});
    ExtendedUserListResponse body =
        get("/internal/security/metalakes/metalake/groups/contractors/users")
            .readEntity(ExtendedUserListResponse.class);
    Assertions.assertEquals(1, body.getUsers().length);
    Assertions.assertEquals("alice", body.getUsers()[0].name());
    Assertions.assertEquals(IdentitySource.LOCAL, body.getUsers()[0].origin());
  }

  @Test
  public void testListGroupsRequiresServiceAdminAndFiltersByPrivilege() throws Exception {
    String metalake = "metalake";
    when(accessControlDispatcher.listExtendedGroups(metalake))
        .thenReturn(
            new ExtendedGroupDTO[] {
              ExtendedGroupDTO.from(buildGroup(1L, "visible", List.of()), IdentitySource.LOCAL, 1),
              ExtendedGroupDTO.from(buildGroup(2L, "hidden", List.of()), IdentitySource.LOCAL, 1)
            });
    Method method = ExtendedMetalakeGroupOperations.class.getMethod("listGroups", String.class);
    Assertions.assertEquals(
        "SERVICE_ADMIN", method.getAnnotation(AuthorizationExpression.class).expression());

    ExtendedMetalakeGroupOperations operations = new ExtendedMetalakeGroupOperations();
    FieldUtils.writeField(operations, "httpRequest", mock(HttpServletRequest.class), true);
    try (MockedStatic<MetadataListingHelper> listing =
        Mockito.mockStatic(MetadataListingHelper.class)) {
      listing
          .when(
              () ->
                  MetadataListingHelper.filterByExpression(
                      Mockito.eq(metalake),
                      Mockito.eq(
                          AuthorizationExpressionConstants.LOAD_GROUP_AUTHORIZATION_EXPRESSION),
                      Mockito.eq(Entity.EntityType.GROUP),
                      Mockito.any(ExtendedGroupDTO[].class),
                      Mockito.any()))
          .thenAnswer(
              invocation -> {
                ExtendedGroupDTO[] candidates = invocation.getArgument(3);
                return new ExtendedGroupDTO[] {candidates[0]};
              });

      Response response = operations.listGroups(metalake);

      Assertions.assertEquals(Response.Status.OK.getStatusCode(), response.getStatus());
      ExtendedGroupListResponse body = (ExtendedGroupListResponse) response.getEntity();
      Assertions.assertEquals(1, body.getGroups().length);
      Assertions.assertEquals("visible", body.getGroups()[0].name());
    }
  }

  @Test
  public void testListIdpGroups() {
    when(accessControlDispatcher.listIdpGroups("metalake"))
        .thenReturn(new IdpNameStatus[] {new IdpNameStatus("contractors", false)});

    IdpGroupNameListResponse body =
        get("/internal/security/metalakes/metalake/groups/idp")
            .readEntity(IdpGroupNameListResponse.class);
    Assertions.assertEquals("contractors", body.getGroups()[0].getName());
    Assertions.assertFalse(body.getGroups()[0].isStatus());
  }

  @Test
  public void testAddLocalGroup() {
    String metalake = "metalake";
    Group added = buildGroup(1L, "contractors", Collections.singletonList("Analyst"));
    when(accessControlDispatcher.addLocalGroup(
            eq(metalake), eq("contractors"), eq(List.of("Analyst"))))
        .thenReturn(added);

    Response response =
        target("/internal/security/metalakes/" + metalake + "/groups")
            .request(MediaType.APPLICATION_JSON_TYPE)
            .accept("application/vnd.gravitino.v1+json")
            .post(
                entity(
                    new LocalGroupAddRequest("contractors", List.of("Analyst")),
                    MediaType.APPLICATION_JSON_TYPE));

    ExtendedGroupResponse body = response.readEntity(ExtendedGroupResponse.class);
    Assertions.assertEquals("contractors", body.getGroup().name());
    Assertions.assertEquals(IdentitySource.LOCAL, body.getGroup().origin());
  }

  @Test
  public void testAddLocalGroupBadRequest() {
    String metalake = "metalake";
    Response response =
        target("/internal/security/metalakes/" + metalake + "/groups")
            .request(MediaType.APPLICATION_JSON_TYPE)
            .accept("application/vnd.gravitino.v1+json")
            .post(entity(new LocalGroupAddRequest(" ", null), MediaType.APPLICATION_JSON_TYPE));

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

  private static GroupEntity buildGroup(Long id, String name, List<String> roles) {
    return GroupEntity.builder()
        .withId(id)
        .withName(name)
        .withNamespace(Namespace.of("metalake", "system", "group"))
        .withRoleNames(roles)
        .withAuditInfo(
            AuditInfo.builder().withCreator("test").withCreateTime(Instant.now()).build())
        .build();
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

  private void verifyBatchRelationLookup(SupportsRelationOperations.Type relationType)
      throws IOException {
    verify(relationOperations)
        .batchListEntitiesByRelation(
            eq(relationType),
            argThat(identifiers -> identifiers.size() == 1),
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
    Assertions.assertArrayEquals(new String[] {"user1"}, assignment.getMembers().getUsers());
    Assertions.assertArrayEquals(
        new String[] {"group1", "group2"}, assignment.getMembers().getGroups());
    Assertions.assertEquals("owner-group", assignment.getOwner().name());
    Assertions.assertEquals(Owner.Type.GROUP, assignment.getOwner().type());
    Assertions.assertEquals(ASSIGNED_AT, assignment.getAssignment().lastModifiedTime());
    Assertions.assertEquals("admin", assignment.getAssignment().lastModifier());
  }
}
