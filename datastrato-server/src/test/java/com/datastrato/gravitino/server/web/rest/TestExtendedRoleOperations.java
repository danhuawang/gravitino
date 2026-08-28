/*
 * Copyright 2025 Datastrato Pvt Ltd.
 * This software is licensed under the Apache License version 2.
 */
package com.datastrato.gravitino.server.web.rest;

import static org.apache.gravitino.Configs.TREE_LOCK_CLEAN_INTERVAL;
import static org.apache.gravitino.Configs.TREE_LOCK_MAX_NODE_IN_MEMORY;
import static org.apache.gravitino.Configs.TREE_LOCK_MIN_NODE_IN_MEMORY;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.reset;
import static org.mockito.Mockito.when;

import com.datastrato.gravitino.ExtendedDatastratoGravitinoEnv;
import com.datastrato.gravitino.authorization.DatastratoAccessControlDispatcher;
import com.datastrato.gravitino.authorization.RoleGroupAssignment;
import com.datastrato.gravitino.authorization.RoleUserAssignment;
import com.datastrato.gravitino.dto.authorization.IdentitySource;
import com.datastrato.gravitino.dto.requests.PermissionUpdateRequest;
import com.datastrato.gravitino.dto.requests.RoleAssignmentRequest;
import com.datastrato.gravitino.dto.responses.RoleGroupAssignmentListResponse;
import com.datastrato.gravitino.dto.responses.RoleUserAssignmentListResponse;
import com.google.common.collect.Lists;
import java.io.IOException;
import java.time.Instant;
import java.util.Collections;
import javax.servlet.http.HttpServletRequest;
import javax.ws.rs.client.Entity;
import javax.ws.rs.core.Application;
import javax.ws.rs.core.MediaType;
import javax.ws.rs.core.Response;
import org.apache.commons.lang3.reflect.FieldUtils;
import org.apache.gravitino.Config;
import org.apache.gravitino.GravitinoEnv;
import org.apache.gravitino.MetadataObject;
import org.apache.gravitino.Namespace;
import org.apache.gravitino.authorization.Group;
import org.apache.gravitino.authorization.Privilege;
import org.apache.gravitino.authorization.Privileges;
import org.apache.gravitino.authorization.Role;
import org.apache.gravitino.authorization.SecurableObject;
import org.apache.gravitino.authorization.SecurableObjects;
import org.apache.gravitino.authorization.User;
import org.apache.gravitino.catalog.TableDispatcher;
import org.apache.gravitino.dto.authorization.PrivilegeDTO;
import org.apache.gravitino.dto.authorization.SecurableObjectDTO;
import org.apache.gravitino.dto.responses.BaseResponse;
import org.apache.gravitino.dto.responses.ErrorConstants;
import org.apache.gravitino.dto.responses.ErrorResponse;
import org.apache.gravitino.dto.responses.RoleResponse;
import org.apache.gravitino.exceptions.NoSuchGroupException;
import org.apache.gravitino.exceptions.NoSuchMetadataObjectException;
import org.apache.gravitino.exceptions.NoSuchRoleException;
import org.apache.gravitino.lock.LockManager;
import org.apache.gravitino.meta.AuditInfo;
import org.apache.gravitino.meta.GroupEntity;
import org.apache.gravitino.meta.RoleEntity;
import org.apache.gravitino.meta.UserEntity;
import org.apache.gravitino.rest.RESTUtils;
import org.glassfish.hk2.utilities.binding.AbstractBinder;
import org.glassfish.jersey.server.ResourceConfig;
import org.glassfish.jersey.test.JerseyTest;
import org.glassfish.jersey.test.TestProperties;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

public class TestExtendedRoleOperations extends JerseyTest {

  private static final DatastratoAccessControlDispatcher accessControlDispatcher =
      mock(DatastratoAccessControlDispatcher.class);
  private static final TableDispatcher tableDispatcher = mock(TableDispatcher.class);

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
    FieldUtils.writeField(GravitinoEnv.getInstance(), "tableDispatcher", tableDispatcher, true);
    FieldUtils.writeField(
        ExtendedDatastratoGravitinoEnv.getInstance(),
        "accessControlDispatcher",
        accessControlDispatcher,
        true);
  }

  @BeforeEach
  public void resetMocks() {
    reset(accessControlDispatcher, tableDispatcher);
  }

  protected Application configure() {
    try {
      forceSet(
          TestProperties.CONTAINER_PORT, String.valueOf(RESTUtils.findAvailablePort(2000, 3000)));
    } catch (IOException e) {
      throw new RuntimeException(e);
    }

    ResourceConfig resourceConfig = new ResourceConfig();
    resourceConfig.register(ExtendedRoleOperations.class);
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
  public void testListUsersByRole() {
    when(accessControlDispatcher.listUserAssignmentsByRole(any(), any()))
        .thenReturn(
            new RoleUserAssignment[] {
              new RoleUserAssignment(buildUser("user1"), buildAssignmentAudit(), true),
              new RoleUserAssignment(buildUser("user2"), buildAssignmentAudit(), false)
            });

    Response resp =
        target("/web/security/metalakes/testMetalake/roles/testRole/users")
            .request(MediaType.APPLICATION_JSON_TYPE)
            .accept("application/vnd.gravitino.v1+json")
            .get();

    Assertions.assertEquals(Response.Status.OK.getStatusCode(), resp.getStatus());
    Assertions.assertEquals(MediaType.APPLICATION_JSON_TYPE, resp.getMediaType());

    RoleUserAssignmentListResponse userListResponse =
        resp.readEntity(RoleUserAssignmentListResponse.class);
    Assertions.assertEquals(0, userListResponse.getCode());
    Assertions.assertEquals(2, userListResponse.getUsers().length);
    Assertions.assertEquals("user1", userListResponse.getUsers()[0].name());
    Assertions.assertEquals(IdentitySource.LOCAL, userListResponse.getUsers()[0].origin());
    Assertions.assertEquals(
        "assigner", userListResponse.getUsers()[0].assignmentAudit().lastModifier());
    Assertions.assertEquals("user2", userListResponse.getUsers()[1].name());
    Assertions.assertEquals(IdentitySource.PROVISIONED, userListResponse.getUsers()[1].origin());
    Assertions.assertEquals(
        Instant.parse("2026-08-28T01:02:03Z"),
        userListResponse.getUsers()[1].assignmentAudit().lastModifiedTime());

    when(accessControlDispatcher.listUserAssignmentsByRole(any(), any()))
        .thenThrow(new NoSuchRoleException("Role testRole does not exist"));
    Response errorResp =
        target("/web/security/metalakes/testMetalake/roles/testRole/users")
            .request(MediaType.APPLICATION_JSON_TYPE)
            .accept("application/vnd.gravitino.v1+json")
            .get();

    Assertions.assertEquals(Response.Status.NOT_FOUND.getStatusCode(), errorResp.getStatus());
    ErrorResponse errorResponse = errorResp.readEntity(ErrorResponse.class);
    Assertions.assertEquals(ErrorConstants.NOT_FOUND_CODE, errorResponse.getCode());
    Assertions.assertEquals(NoSuchRoleException.class.getSimpleName(), errorResponse.getType());
  }

  @Test
  public void testListGroupsByRole() {
    when(accessControlDispatcher.listGroupAssignmentsByRole(any(), any()))
        .thenReturn(
            new RoleGroupAssignment[] {
              new RoleGroupAssignment(buildGroup("group1"), buildAssignmentAudit(), 3),
              new RoleGroupAssignment(buildGroup("group2"), buildAssignmentAudit(), 7)
            });

    Response resp =
        target("/web/security/metalakes/testMetalake/roles/testRole/groups")
            .request(MediaType.APPLICATION_JSON_TYPE)
            .accept("application/vnd.gravitino.v1+json")
            .get();

    Assertions.assertEquals(Response.Status.OK.getStatusCode(), resp.getStatus());
    Assertions.assertEquals(MediaType.APPLICATION_JSON_TYPE, resp.getMediaType());

    RoleGroupAssignmentListResponse groupListResponse =
        resp.readEntity(RoleGroupAssignmentListResponse.class);
    Assertions.assertEquals(0, groupListResponse.getCode());
    Assertions.assertEquals(2, groupListResponse.getGroups().length);
    Assertions.assertEquals("group1", groupListResponse.getGroups()[0].name());
    Assertions.assertEquals(3, groupListResponse.getGroups()[0].userCount());
    Assertions.assertEquals(
        "assigner", groupListResponse.getGroups()[0].assignmentAudit().lastModifier());
    Assertions.assertEquals("group2", groupListResponse.getGroups()[1].name());
    Assertions.assertEquals(7, groupListResponse.getGroups()[1].userCount());

    when(accessControlDispatcher.listGroupAssignmentsByRole(any(), any()))
        .thenThrow(new RuntimeException("Test exception"));
    Response errorResp =
        target("/web/security/metalakes/testMetalake/roles/testRole/groups")
            .request(MediaType.APPLICATION_JSON_TYPE)
            .accept("application/vnd.gravitino.v1+json")
            .get();

    Assertions.assertEquals(
        Response.Status.INTERNAL_SERVER_ERROR.getStatusCode(), errorResp.getStatus());
    ErrorResponse errorResponse = errorResp.readEntity(ErrorResponse.class);
    Assertions.assertEquals(ErrorConstants.INTERNAL_ERROR_CODE, errorResponse.getCode());
    Assertions.assertEquals(RuntimeException.class.getSimpleName(), errorResponse.getType());
  }

  @Test
  public void testAssignRoleToPrincipals() {
    RoleAssignmentRequest request =
        new RoleAssignmentRequest(
            Lists.newArrayList("alice", "bob"), Lists.newArrayList("analysts", "admins"));

    Response response =
        target("/web/security/metalakes/testMetalake/roles/testRole/assignments")
            .request(MediaType.APPLICATION_JSON_TYPE)
            .accept("application/vnd.gravitino.v1+json")
            .put(Entity.entity(request, MediaType.APPLICATION_JSON_TYPE));

    Assertions.assertEquals(Response.Status.OK.getStatusCode(), response.getStatus());
    BaseResponse baseResponse = response.readEntity(BaseResponse.class);
    Assertions.assertEquals(0, baseResponse.getCode());
    Mockito.verify(accessControlDispatcher)
        .assignRoleToPrincipals(
            "testMetalake",
            "testRole",
            Lists.newArrayList("alice", "bob"),
            Lists.newArrayList("analysts", "admins"));
  }

  @Test
  public void testAssignRoleToPrincipalsRejectsEmptyRequest() {
    RoleAssignmentRequest request =
        new RoleAssignmentRequest(Collections.emptyList(), Collections.emptyList());

    Response response =
        target("/web/security/metalakes/testMetalake/roles/testRole/assignments")
            .request(MediaType.APPLICATION_JSON_TYPE)
            .accept("application/vnd.gravitino.v1+json")
            .put(Entity.entity(request, MediaType.APPLICATION_JSON_TYPE));

    Assertions.assertEquals(Response.Status.BAD_REQUEST.getStatusCode(), response.getStatus());
    Mockito.verify(accessControlDispatcher, Mockito.never())
        .assignRoleToPrincipals(any(), any(), any(), any());
  }

  @Test
  public void testAssignRoleToPrincipalsReturnsNotFound() {
    RoleAssignmentRequest request =
        new RoleAssignmentRequest(Lists.newArrayList("alice"), Lists.newArrayList("missing-group"));
    Mockito.doThrow(new NoSuchGroupException("Group missing-group does not exist"))
        .when(accessControlDispatcher)
        .assignRoleToPrincipals(any(), any(), any(), any());

    Response response =
        target("/web/security/metalakes/testMetalake/roles/testRole/assignments")
            .request(MediaType.APPLICATION_JSON_TYPE)
            .accept("application/vnd.gravitino.v1+json")
            .put(Entity.entity(request, MediaType.APPLICATION_JSON_TYPE));

    Assertions.assertEquals(Response.Status.NOT_FOUND.getStatusCode(), response.getStatus());
    ErrorResponse errorResponse = response.readEntity(ErrorResponse.class);
    Assertions.assertEquals(ErrorConstants.NOT_FOUND_CODE, errorResponse.getCode());
    Assertions.assertEquals(NoSuchGroupException.class.getSimpleName(), errorResponse.getType());
  }

  @Test
  public void testBulkUpdateObjects() {
    SecurableObjectDTO[] updates =
        new SecurableObjectDTO[] {
          SecurableObjectDTO.builder()
              .withPrivileges(
                  new PrivilegeDTO[] {
                    PrivilegeDTO.builder()
                        .withName(Privilege.Name.SELECT_TABLE)
                        .withCondition(Privilege.Condition.ALLOW)
                        .build()
                  })
              .withType(MetadataObject.Type.TABLE)
              .withFullName("test1.test2.test3")
              .build()
        };

    PermissionUpdateRequest req = new PermissionUpdateRequest(updates);

    when(accessControlDispatcher.updatePrivilegesForRole(any(), any(), any()))
        .thenReturn(buildRole("role1"));
    when(tableDispatcher.tableExists(any())).thenReturn(true);

    Response resp =
        target("/web/security/metalakes/testMetalake/roles/testRole/")
            .request(MediaType.APPLICATION_JSON_TYPE)
            .accept("application/vnd.gravitino.v1+json")
            .put(Entity.entity(req, MediaType.APPLICATION_JSON_TYPE));

    Assertions.assertEquals(Response.Status.OK.getStatusCode(), resp.getStatus());
    Assertions.assertEquals(MediaType.APPLICATION_JSON_TYPE, resp.getMediaType());

    RoleResponse roleResponse = resp.readEntity(RoleResponse.class);
    Assertions.assertEquals(0, roleResponse.getCode());
    Role roleDTO = roleResponse.getRole();
    Assertions.assertEquals("role1", roleDTO.name());
    Assertions.assertTrue(roleDTO.properties().isEmpty());
    Assertions.assertEquals(
        SecurableObjects.ofCatalog("catalog", Lists.newArrayList(Privileges.UseCatalog.allow()))
            .fullName(),
        roleDTO.securableObjects().get(0).fullName());
    Assertions.assertEquals(1, roleDTO.securableObjects().get(0).privileges().size());
    Assertions.assertEquals(
        Privileges.UseCatalog.allow().name(),
        roleDTO.securableObjects().get(0).privileges().get(0).name());
    Assertions.assertEquals(
        Privileges.UseCatalog.allow().condition(),
        roleDTO.securableObjects().get(0).privileges().get(0).condition());

    // Throw no found exception
    when(tableDispatcher.tableExists(any())).thenReturn(false);
    Response resp1 =
        target("/web/security/metalakes/testMetalake/roles/testRole/")
            .request(MediaType.APPLICATION_JSON_TYPE)
            .accept("application/vnd.gravitino.v1+json")
            .put(Entity.entity(req, MediaType.APPLICATION_JSON_TYPE));

    Assertions.assertEquals(Response.Status.NOT_FOUND.getStatusCode(), resp1.getStatus());

    ErrorResponse errorResponse = resp1.readEntity(ErrorResponse.class);
    Assertions.assertEquals(ErrorConstants.NOT_FOUND_CODE, errorResponse.getCode());
    Assertions.assertEquals(
        NoSuchMetadataObjectException.class.getSimpleName(), errorResponse.getType());

    // Throw runtime exception
    when(tableDispatcher.tableExists(any())).thenReturn(true);
    when(accessControlDispatcher.updatePrivilegesForRole(any(), any(), any()))
        .thenThrow(new RuntimeException("Test exception"));
    Response resp2 =
        target("/web/security/metalakes/testMetalake/roles/testRole/")
            .request(MediaType.APPLICATION_JSON_TYPE)
            .accept("application/vnd.gravitino.v1+json")
            .put(Entity.entity(req, MediaType.APPLICATION_JSON_TYPE));

    Assertions.assertEquals(
        Response.Status.INTERNAL_SERVER_ERROR.getStatusCode(), resp2.getStatus());
    ErrorResponse errorResponse2 = resp2.readEntity(ErrorResponse.class);
    Assertions.assertEquals(ErrorConstants.INTERNAL_ERROR_CODE, errorResponse2.getCode());
    Assertions.assertEquals(RuntimeException.class.getSimpleName(), errorResponse2.getType());
  }

  private User buildUser(String user) {
    return UserEntity.builder()
        .withId(1L)
        .withName(user)
        .withNamespace(Namespace.of("testMetalake", "system", "user"))
        .withRoleNames(Collections.emptyList())
        .withAuditInfo(
            AuditInfo.builder().withCreator("creator").withCreateTime(Instant.now()).build())
        .build();
  }

  private Group buildGroup(String group) {
    return GroupEntity.builder()
        .withId(1L)
        .withName(group)
        .withNamespace(Namespace.of("testMetalake", "system", "group"))
        .withRoleNames(Collections.emptyList())
        .withAuditInfo(
            AuditInfo.builder().withCreator("creator").withCreateTime(Instant.now()).build())
        .build();
  }

  private AuditInfo buildAssignmentAudit() {
    return AuditInfo.builder()
        .withCreator("creator")
        .withCreateTime(Instant.EPOCH)
        .withLastModifier("assigner")
        .withLastModifiedTime(Instant.parse("2026-08-28T01:02:03Z"))
        .build();
  }

  private Role buildRole(String role) {
    SecurableObject catalog =
        SecurableObjects.ofCatalog("catalog", Lists.newArrayList(Privileges.UseCatalog.allow()));
    SecurableObject anotherSecurableObject =
        SecurableObjects.ofCatalog(
            "another_catalog", Lists.newArrayList(Privileges.CreateSchema.deny()));

    return RoleEntity.builder()
        .withId(1L)
        .withName(role)
        .withProperties(Collections.emptyMap())
        .withSecurableObjects(Lists.newArrayList(catalog, anotherSecurableObject))
        .withAuditInfo(
            AuditInfo.builder().withCreator("creator").withCreateTime(Instant.now()).build())
        .build();
  }
}
