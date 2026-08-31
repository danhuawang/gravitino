/*
 * Copyright 2026 Datastrato Inc.
 */
package com.datastrato.gravitino.server.web.rest;

import static org.apache.gravitino.Configs.ENABLE_AUTHORIZATION;
import static org.apache.gravitino.Configs.TREE_LOCK_CLEAN_INTERVAL;
import static org.apache.gravitino.Configs.TREE_LOCK_MAX_NODE_IN_MEMORY;
import static org.apache.gravitino.Configs.TREE_LOCK_MIN_NODE_IN_MEMORY;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.datastrato.gravitino.ExtendedDatastratoGravitinoEnv;
import com.datastrato.gravitino.authorization.DatastratoAccessControlDispatcher;
import com.datastrato.gravitino.dto.authorization.AuthorizationSummaryDTO;
import com.datastrato.gravitino.dto.authorization.CatalogAuthorizationDTO;
import com.datastrato.gravitino.dto.authorization.ExtendedGroupDTO;
import com.datastrato.gravitino.dto.authorization.ObjectAuthorizationDTO;
import com.datastrato.gravitino.dto.authorization.RoleMembershipDTO;
import com.datastrato.gravitino.dto.responses.AuthorizationOverviewResponse;
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
import org.apache.gravitino.authorization.Group;
import org.apache.gravitino.authorization.Privileges;
import org.apache.gravitino.authorization.Role;
import org.apache.gravitino.authorization.SecurableObject;
import org.apache.gravitino.authorization.SecurableObjects;
import org.apache.gravitino.authorization.User;
import org.apache.gravitino.connector.PropertiesMetadata;
import org.apache.gravitino.dto.responses.ErrorConstants;
import org.apache.gravitino.dto.responses.ErrorResponse;
import org.apache.gravitino.lock.LockManager;
import org.apache.gravitino.meta.AuditInfo;
import org.apache.gravitino.meta.BaseMetalake;
import org.apache.gravitino.meta.RoleEntity;
import org.apache.gravitino.rest.RESTUtils;
import org.apache.gravitino.server.authorization.annotations.AuthorizationExpression;
import org.apache.gravitino.server.authorization.annotations.AuthorizationMetadata;
import org.glassfish.hk2.utilities.binding.AbstractBinder;
import org.glassfish.jersey.server.ResourceConfig;
import org.glassfish.jersey.test.JerseyTest;
import org.glassfish.jersey.test.TestProperties;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

public class TestExtendedAuthorizationOverviewOperations extends JerseyTest {

  private static final Instant ROLE_CREATED_AT = Instant.parse("2026-08-26T01:02:03Z");

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
    Mockito.doReturn(false).when(config).get(ENABLE_AUTHORIZATION);
    Mockito.doReturn(100000L).when(config).get(TREE_LOCK_MAX_NODE_IN_MEMORY);
    Mockito.doReturn(1000L).when(config).get(TREE_LOCK_MIN_NODE_IN_MEMORY);
    Mockito.doReturn(36000L).when(config).get(TREE_LOCK_CLEAN_INTERVAL);
    FieldUtils.writeField(GravitinoEnv.getInstance(), "config", config, true);
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
    Mockito.reset(accessControlDispatcher, entityStore);
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
    resourceConfig.register(ExtendedAuthorizationOverviewOperations.class);
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
  public void testGetAuthorizationOverview() {
    SecurableObject catalog1 =
        SecurableObjects.ofCatalog("catalog1", Lists.newArrayList(Privileges.UseCatalog.allow()));
    SecurableObject schema1 =
        SecurableObjects.ofSchema(catalog1, "schema1", Collections.emptyList());
    SecurableObject table1 =
        SecurableObjects.ofTable(
            schema1, "table1", Lists.newArrayList(Privileges.ModifyTable.allow()));
    SecurableObject table1Deny =
        SecurableObjects.ofTable(
            schema1, "table1", Lists.newArrayList(Privileges.ModifyTable.deny()));
    SecurableObject catalog2 =
        SecurableObjects.ofCatalog("catalog2", Lists.newArrayList(Privileges.UseCatalog.allow()));
    SecurableObject schema2 =
        SecurableObjects.ofSchema(catalog2, "schema2", Collections.emptyList());
    SecurableObject table2 =
        SecurableObjects.ofTable(
            schema2, "table2", Lists.newArrayList(Privileges.SelectTable.allow()));

    when(accessControlDispatcher.listRolesWithSecurableObjects("metalake1"))
        .thenReturn(
            new Role[] {
              buildRole("role1", Lists.newArrayList(table1, table2)),
              buildRole("role2", Lists.newArrayList(table1Deny)),
              buildRole("role3", Collections.emptyList())
            });
    User user1 = mockUser("user1", Lists.newArrayList("role1", "role2"), true);
    User user2 = mockUser("user2", Lists.newArrayList("role1"), false);
    ExtendedGroupDTO group1 = mockExtendedGroup("group1", Lists.newArrayList("role2"), 2);
    ExtendedGroupDTO emptyGroup = mockExtendedGroup("empty", Collections.emptyList(), 0);
    when(accessControlDispatcher.listUsers("metalake1")).thenReturn(new User[] {user1, user2});
    when(accessControlDispatcher.listExtendedGroups("metalake1"))
        .thenReturn(new ExtendedGroupDTO[] {group1, emptyGroup});

    Response response =
        target("/web/security/metalakes/metalake1/authorization/overview")
            .request(MediaType.APPLICATION_JSON_TYPE)
            .accept("application/vnd.gravitino.v1+json")
            .get();

    Assertions.assertEquals(Response.Status.OK.getStatusCode(), response.getStatus());
    AuthorizationOverviewResponse overview =
        response.readEntity(AuthorizationOverviewResponse.class);
    Assertions.assertEquals(0, overview.getCode());
    Assertions.assertArrayEquals(new String[] {"role3"}, overview.getUnassignedRoles());

    AuthorizationSummaryDTO summary = overview.getSummary();
    Assertions.assertEquals(2, summary.getUserCount());
    Assertions.assertEquals(1, summary.getActiveUserCount());
    Assertions.assertEquals(1, summary.getSuspendedUserCount());
    Assertions.assertEquals(2, summary.getGroupCount());
    Assertions.assertEquals(1, summary.getEmptyGroupCount());
    Assertions.assertEquals(3, summary.getRoleCount());
    Assertions.assertEquals(1, summary.getUnassignedRoleCount());

    CatalogAuthorizationDTO[] catalogs = overview.getCatalogs();
    Assertions.assertEquals(2, catalogs.length);
    assertCatalog1(catalogs[0]);
    Assertions.assertEquals("catalog2", catalogs[1].getCatalog());
    Assertions.assertArrayEquals(new String[] {"role1"}, catalogs[1].getRoles());
    Assertions.assertArrayEquals(new String[] {"user1", "user2"}, catalogs[1].getUsers());
    Assertions.assertEquals(2, catalogs[1].getMemberCount());
    Assertions.assertEquals(0, catalogs[1].getPrivilegedPrincipalCount());
    Assertions.assertEquals(0.0, catalogs[1].getPrivileged());
    Assertions.assertEquals(1, catalogs[1].getObjects().length);
    Assertions.assertEquals(
        "catalog2.schema2.table2", catalogs[1].getObjects()[0].getMetadataObject().fullName());

    RoleMembershipDTO[] roles = overview.getRoles();
    Assertions.assertEquals(3, roles.length);
    Assertions.assertEquals("role1", roles[0].getRole());
    Assertions.assertEquals(2, roles[0].getUserCount());
    Assertions.assertEquals(0, roles[0].getGroupCount());
    Assertions.assertEquals(2, roles[0].getMemberCount());
    Assertions.assertTrue(roles[0].isAssigned());
    Assertions.assertArrayEquals(new String[] {"catalog1", "catalog2"}, roles[0].getCatalogs());
    Assertions.assertEquals(2, roles[0].getObjectCount());
    Assertions.assertEquals(2, roles[0].getPrivilegeCount());
    Assertions.assertEquals("role3", roles[2].getRole());
    Assertions.assertFalse(roles[2].isAssigned());
    Assertions.assertEquals(0, roles[2].getMemberCount());
  }

  @Test
  public void testGetAuthorizationOverviewWithEmptyResult() {
    when(accessControlDispatcher.listRolesWithSecurableObjects("metalake1"))
        .thenReturn(new Role[0]);
    when(accessControlDispatcher.listUsers("metalake1")).thenReturn(new User[0]);
    when(accessControlDispatcher.listExtendedGroups("metalake1"))
        .thenReturn(new ExtendedGroupDTO[0]);

    Response response =
        target("/web/security/metalakes/metalake1/authorization/overview")
            .request(MediaType.APPLICATION_JSON_TYPE)
            .accept("application/vnd.gravitino.v1+json")
            .get();

    Assertions.assertEquals(Response.Status.OK.getStatusCode(), response.getStatus());
    AuthorizationOverviewResponse overview =
        response.readEntity(AuthorizationOverviewResponse.class);
    Assertions.assertEquals(0, overview.getCatalogs().length);
    Assertions.assertEquals(0, overview.getRoles().length);
    Assertions.assertEquals(0, overview.getUnassignedRoles().length);
    AuthorizationSummaryDTO summary = overview.getSummary();
    Assertions.assertEquals(0, summary.getUserCount());
    Assertions.assertEquals(0, summary.getActiveUserCount());
    Assertions.assertEquals(0, summary.getSuspendedUserCount());
    Assertions.assertEquals(0, summary.getGroupCount());
    Assertions.assertEquals(0, summary.getEmptyGroupCount());
    Assertions.assertEquals(0, summary.getRoleCount());
    Assertions.assertEquals(0, summary.getUnassignedRoleCount());
  }

  @Test
  public void testAuthorizationAnnotations() throws NoSuchMethodException {
    Method method =
        ExtendedAuthorizationOverviewOperations.class.getMethod(
            "getAuthorizationOverview", String.class);
    AuthorizationExpression expression = method.getAnnotation(AuthorizationExpression.class);
    Assertions.assertNotNull(expression);
    Assertions.assertEquals("", expression.expression());

    Parameter metalakeParameter = method.getParameters()[0];
    AuthorizationMetadata authorizationMetadata =
        metalakeParameter.getAnnotation(AuthorizationMetadata.class);
    Assertions.assertNotNull(authorizationMetadata);
    Assertions.assertEquals(Entity.EntityType.METALAKE, authorizationMetadata.type());
  }

  @Test
  public void testGetAuthorizationOverviewWithException() {
    doThrow(new RuntimeException("mock error"))
        .when(accessControlDispatcher)
        .listRolesWithSecurableObjects("metalake1");

    Response response =
        target("/web/security/metalakes/metalake1/authorization/overview")
            .request(MediaType.APPLICATION_JSON_TYPE)
            .accept("application/vnd.gravitino.v1+json")
            .get();

    Assertions.assertEquals(
        Response.Status.INTERNAL_SERVER_ERROR.getStatusCode(), response.getStatus());
    ErrorResponse errorResponse = response.readEntity(ErrorResponse.class);
    Assertions.assertEquals(ErrorConstants.INTERNAL_ERROR_CODE, errorResponse.getCode());
    Assertions.assertEquals(RuntimeException.class.getSimpleName(), errorResponse.getType());
  }

  private void assertCatalog1(CatalogAuthorizationDTO catalog) {
    Assertions.assertEquals("catalog1", catalog.getCatalog());
    Assertions.assertArrayEquals(new String[] {"role1", "role2"}, catalog.getRoles());
    Assertions.assertArrayEquals(new String[] {"user1", "user2"}, catalog.getUsers());
    Assertions.assertArrayEquals(new String[] {"group1"}, catalog.getGroups());
    Assertions.assertEquals(3, catalog.getMemberCount());

    ObjectAuthorizationDTO[] objects = catalog.getObjects();
    Assertions.assertEquals(1, objects.length);
    Assertions.assertEquals("catalog1.schema1.table1", objects[0].getMetadataObject().fullName());
    Assertions.assertEquals(2, objects[0].getRolePrivileges().length);
    Assertions.assertEquals(ROLE_CREATED_AT, objects[0].getRolePrivileges()[0].getCreateTime());
    Assertions.assertEquals(2, objects[0].getRolePrivileges()[0].getAssignCount());
    Assertions.assertEquals(ROLE_CREATED_AT, objects[0].getRolePrivileges()[1].getCreateTime());
    Assertions.assertEquals(2, objects[0].getRolePrivileges()[1].getAssignCount());
    Assertions.assertEquals(2, catalog.getPrivilegedPrincipalCount());
    Assertions.assertEquals(2.0 / 3.0, catalog.getPrivileged());
  }

  private void mockInUseMetalake() throws IOException {
    BaseMetalake metalake = mock(BaseMetalake.class);
    PropertiesMetadata propertiesMetadata = mock(PropertiesMetadata.class);
    when(propertiesMetadata.getOrDefault(any(), any())).thenReturn(true);
    when(metalake.propertiesMetadata()).thenReturn(propertiesMetadata);
    when(entityStore.get(any(), any(), any())).thenReturn(metalake);
  }

  private Role buildRole(String role, List<SecurableObject> securableObjects) {
    return RoleEntity.builder()
        .withId(1L)
        .withName(role)
        .withProperties(Collections.emptyMap())
        .withSecurableObjects(securableObjects)
        .withAuditInfo(
            AuditInfo.builder().withCreator("creator").withCreateTime(ROLE_CREATED_AT).build())
        .build();
  }

  private User mockUser(String name, List<String> roles, boolean enabled) {
    User user = mock(User.class);
    when(user.name()).thenReturn(name);
    when(user.roles()).thenReturn(roles);
    when(user.enabled()).thenReturn(enabled);
    return user;
  }

  private ExtendedGroupDTO mockExtendedGroup(String name, List<String> roles, int userCount) {
    Group group = mock(Group.class);
    when(group.name()).thenReturn(name);
    when(group.roles()).thenReturn(roles);
    when(group.externalId()).thenReturn(null);
    when(group.auditInfo())
        .thenReturn(
            AuditInfo.builder().withCreator("creator").withCreateTime(Instant.now()).build());
    return ExtendedGroupDTO.from(group, true, userCount);
  }
}
