/*
 * Copyright 2026 Datastrato Pvt Ltd.
 */
package com.datastrato.gravitino.server.web.rest;

import static org.apache.gravitino.Configs.ENABLE_AUTHORIZATION;
import static org.apache.gravitino.Configs.TREE_LOCK_CLEAN_INTERVAL;
import static org.apache.gravitino.Configs.TREE_LOCK_MAX_NODE_IN_MEMORY;
import static org.apache.gravitino.Configs.TREE_LOCK_MIN_NODE_IN_MEMORY;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.datastrato.gravitino.ExtendedDatastratoGravitinoEnv;
import com.datastrato.gravitino.authorization.DatastratoAccessControlDispatcher;
import com.datastrato.gravitino.authorization.RoleAssignment;
import com.datastrato.gravitino.dto.authorization.RoleAssignmentDTO;
import com.datastrato.gravitino.dto.responses.RoleAssignmentListResponse;
import com.google.common.collect.Lists;
import java.io.IOException;
import java.lang.reflect.Method;
import java.lang.reflect.Parameter;
import java.time.Instant;
import java.util.Collections;
import javax.servlet.http.HttpServletRequest;
import javax.ws.rs.core.Application;
import javax.ws.rs.core.MediaType;
import javax.ws.rs.core.Response;
import org.apache.commons.lang3.reflect.FieldUtils;
import org.apache.gravitino.Config;
import org.apache.gravitino.Entity;
import org.apache.gravitino.EntityStore;
import org.apache.gravitino.GravitinoEnv;
import org.apache.gravitino.authorization.Privilege;
import org.apache.gravitino.authorization.Privileges;
import org.apache.gravitino.authorization.Role;
import org.apache.gravitino.authorization.SecurableObjects;
import org.apache.gravitino.connector.PropertiesMetadata;
import org.apache.gravitino.dto.responses.ErrorConstants;
import org.apache.gravitino.dto.responses.ErrorResponse;
import org.apache.gravitino.exceptions.NoSuchUserException;
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

/** Tests REST operations that list user and group role assignments. */
public class TestExtendedPrincipalRoleOperations extends JerseyTest {

  private static final DatastratoAccessControlDispatcher ACCESS_CONTROL_DISPATCHER =
      mock(DatastratoAccessControlDispatcher.class);
  private static final EntityStore ENTITY_STORE = mock(EntityStore.class);
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
    Mockito.reset(ACCESS_CONTROL_DISPATCHER, ENTITY_STORE);
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
    assertAuthorizationMetadata(
        "listUserRoleAssignments",
        "METALAKE::OWNER || METALAKE::MANAGE_USERS || USER::SELF",
        Entity.EntityType.USER);
    assertAuthorizationMetadata(
        "listGroupRoleAssignments",
        "METALAKE::OWNER || METALAKE::MANAGE_GROUPS || GROUP::SELF",
        Entity.EntityType.GROUP);
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
