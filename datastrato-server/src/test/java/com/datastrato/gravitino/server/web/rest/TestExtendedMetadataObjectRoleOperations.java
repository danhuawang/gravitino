/*
 * Copyright 2026 Datastrato Pvt Ltd.
 * This software is licensed under the Apache License version 2.
 */
package com.datastrato.gravitino.server.web.rest;

import static org.apache.gravitino.Configs.ENABLE_AUTHORIZATION;
import static org.apache.gravitino.Configs.TREE_LOCK_CLEAN_INTERVAL;
import static org.apache.gravitino.Configs.TREE_LOCK_MAX_NODE_IN_MEMORY;
import static org.apache.gravitino.Configs.TREE_LOCK_MIN_NODE_IN_MEMORY;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.datastrato.gravitino.ExtendedDatastratoGravitinoEnv;
import com.datastrato.gravitino.authorization.DatastratoAccessControlDispatcher;
import com.datastrato.gravitino.dto.authorization.ObjectRolePrivilegeDTO;
import com.datastrato.gravitino.dto.authorization.RolePrivilegeDTO;
import com.datastrato.gravitino.dto.responses.ObjectRolePrivilegeListResponse;
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
import org.apache.gravitino.authorization.Privileges;
import org.apache.gravitino.authorization.Role;
import org.apache.gravitino.authorization.SecurableObject;
import org.apache.gravitino.authorization.SecurableObjects;
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

public class TestExtendedMetadataObjectRoleOperations extends JerseyTest {

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
    resourceConfig.register(ExtendedMetadataObjectRoleOperations.class);
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
  public void testListObjectRolePrivileges() {
    SecurableObject catalog =
        SecurableObjects.ofCatalog("catalog1", Lists.newArrayList(Privileges.UseCatalog.allow()));
    SecurableObject schema = SecurableObjects.ofSchema(catalog, "schema1", Lists.newArrayList());
    SecurableObject table1 =
        SecurableObjects.ofTable(
            schema, "table1", Lists.newArrayList(Privileges.SelectTable.allow()));
    SecurableObject table1ForRole2 =
        SecurableObjects.ofTable(
            schema, "table1", Lists.newArrayList(Privileges.SelectTable.deny()));
    SecurableObject table2 =
        SecurableObjects.ofTable(
            schema, "table2", Lists.newArrayList(Privileges.CreateTable.allow()));

    when(accessControlDispatcher.listRolesWithSecurableObjects("metalake1"))
        .thenReturn(
            new Role[] {
              buildRole("role1", Lists.newArrayList(table1, table2)),
              buildRole("role2", Lists.newArrayList(table1ForRole2))
            });

    Response resp =
        target("/web/security/metalakes/metalake1/objects/roles")
            .request(MediaType.APPLICATION_JSON_TYPE)
            .accept("application/vnd.gravitino.v1+json")
            .get();

    Assertions.assertEquals(Response.Status.OK.getStatusCode(), resp.getStatus());
    Assertions.assertEquals(MediaType.APPLICATION_JSON_TYPE, resp.getMediaType());

    ObjectRolePrivilegeListResponse listResponse =
        resp.readEntity(ObjectRolePrivilegeListResponse.class);
    Assertions.assertEquals(0, listResponse.getCode());

    ObjectRolePrivilegeDTO[] objectRolePrivileges = listResponse.getObjectRolePrivileges();
    Assertions.assertEquals(2, objectRolePrivileges.length);

    Assertions.assertEquals(
        "catalog1.schema1.table1", objectRolePrivileges[0].metadataObject().fullName());
    Assertions.assertEquals(2, objectRolePrivileges[0].rolePrivileges().size());
    assertRolePrivilege(
        objectRolePrivileges[0].rolePrivileges().get(0), "role1", "SELECT_TABLE", "ALLOW");
    assertRolePrivilege(
        objectRolePrivileges[0].rolePrivileges().get(1), "role2", "SELECT_TABLE", "DENY");

    Assertions.assertEquals(
        "catalog1.schema1.table2", objectRolePrivileges[1].metadataObject().fullName());
    Assertions.assertEquals(1, objectRolePrivileges[1].rolePrivileges().size());
    assertRolePrivilege(
        objectRolePrivileges[1].rolePrivileges().get(0), "role1", "CREATE_TABLE", "ALLOW");
    verify(accessControlDispatcher, never()).listRoleNames("metalake1");
    verify(accessControlDispatcher, never()).getRole(any(), any());
  }

  @Test
  public void testListObjectRolePrivilegesWithEmptyResult() {
    when(accessControlDispatcher.listRolesWithSecurableObjects("metalake1"))
        .thenReturn(new Role[0]);

    Response resp =
        target("/web/security/metalakes/metalake1/objects/roles")
            .request(MediaType.APPLICATION_JSON_TYPE)
            .accept("application/vnd.gravitino.v1+json")
            .get();

    Assertions.assertEquals(Response.Status.OK.getStatusCode(), resp.getStatus());

    ObjectRolePrivilegeListResponse listResponse =
        resp.readEntity(ObjectRolePrivilegeListResponse.class);
    Assertions.assertEquals(0, listResponse.getCode());
    Assertions.assertEquals(0, listResponse.getObjectRolePrivileges().length);
  }

  @Test
  public void testListObjectRolePrivilegesAuthorizationAnnotations() throws NoSuchMethodException {
    Method method =
        ExtendedMetadataObjectRoleOperations.class.getMethod(
            "listObjectRolePrivileges", String.class);
    AuthorizationExpression authorizationExpression =
        method.getAnnotation(AuthorizationExpression.class);
    Assertions.assertNotNull(authorizationExpression);
    Assertions.assertEquals("", authorizationExpression.expression());

    Parameter metalakeParameter = method.getParameters()[0];
    AuthorizationMetadata authorizationMetadata =
        metalakeParameter.getAnnotation(AuthorizationMetadata.class);
    Assertions.assertNotNull(authorizationMetadata);
    Assertions.assertEquals(Entity.EntityType.METALAKE, authorizationMetadata.type());
  }

  @Test
  public void testListObjectRolePrivilegesWithException() {
    doThrow(new RuntimeException("mock error"))
        .when(accessControlDispatcher)
        .listRolesWithSecurableObjects("metalake1");

    Response resp =
        target("/web/security/metalakes/metalake1/objects/roles")
            .request(MediaType.APPLICATION_JSON_TYPE)
            .accept("application/vnd.gravitino.v1+json")
            .get();

    Assertions.assertEquals(
        Response.Status.INTERNAL_SERVER_ERROR.getStatusCode(), resp.getStatus());

    ErrorResponse errorResponse = resp.readEntity(ErrorResponse.class);
    Assertions.assertEquals(ErrorConstants.INTERNAL_ERROR_CODE, errorResponse.getCode());
    Assertions.assertEquals(RuntimeException.class.getSimpleName(), errorResponse.getType());
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
            AuditInfo.builder().withCreator("creator").withCreateTime(Instant.now()).build())
        .build();
  }

  private void assertRolePrivilege(
      RolePrivilegeDTO rolePrivilege, String role, String privilege, String condition) {
    Assertions.assertEquals(role, rolePrivilege.role());
    Assertions.assertEquals(1, rolePrivilege.privileges().size());
    Assertions.assertEquals(privilege, rolePrivilege.privileges().get(0).name().name());
    Assertions.assertEquals(condition, rolePrivilege.privileges().get(0).condition().name());
  }
}
