/*
 * Copyright 2026 Datastrato Inc.
 */
package com.datastrato.gravitino.server.web.rest;

import static javax.ws.rs.client.Entity.entity;
import static org.apache.gravitino.Configs.TREE_LOCK_CLEAN_INTERVAL;
import static org.apache.gravitino.Configs.TREE_LOCK_MAX_NODE_IN_MEMORY;
import static org.apache.gravitino.Configs.TREE_LOCK_MIN_NODE_IN_MEMORY;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.reset;
import static org.mockito.Mockito.when;

import com.datastrato.gravitino.ExtendedDatastratoGravitinoEnv;
import com.datastrato.gravitino.authorization.DatastratoAccessControlDispatcher;
import com.datastrato.gravitino.authorization.DirectoryGroup;
import com.datastrato.gravitino.authorization.IdpNameStatus;
import com.datastrato.gravitino.dto.authorization.DirectoryGroupDTO;
import com.datastrato.gravitino.dto.authorization.ExtendedGroupDTO;
import com.datastrato.gravitino.dto.authorization.ExtendedUserDTO;
import com.datastrato.gravitino.dto.authorization.IdentitySource;
import com.datastrato.gravitino.dto.requests.DirectoryGroupAddRequest;
import com.datastrato.gravitino.dto.requests.DirectoryGroupDeleteRequest;
import com.datastrato.gravitino.dto.requests.LocalGroupAddRequest;
import com.datastrato.gravitino.dto.responses.DirectoryGroupListResponse;
import com.datastrato.gravitino.dto.responses.DirectoryGroupResponse;
import com.datastrato.gravitino.dto.responses.ExtendedGroupListResponse;
import com.datastrato.gravitino.dto.responses.ExtendedGroupResponse;
import com.datastrato.gravitino.dto.responses.ExtendedUserListResponse;
import com.datastrato.gravitino.dto.responses.IdpGroupNameListResponse;
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
import org.apache.gravitino.dto.responses.NameListResponse;
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

public class TestExtendedGroupOperations extends JerseyTest {

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
    resourceConfig.register(ExtendedGroupOperations.class);
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
    Group jit = buildGroup(3L, "analysts", Collections.singletonList("Analyst"));
    when(accessControlDispatcher.listExtendedGroups(metalake))
        .thenReturn(
            new ExtendedGroupDTO[] {
              ExtendedGroupDTO.from(local, IdentitySource.LOCAL, 12),
              ExtendedGroupDTO.from(provisioned, IdentitySource.PROVISIONED, 8),
              ExtendedGroupDTO.from(jit, IdentitySource.JIT, 0)
            });

    Response response =
        target("/web/security/metalakes/" + metalake + "/groups")
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
    Assertions.assertEquals(IdentitySource.JIT, body.getGroups()[2].origin());
    Assertions.assertEquals(0, body.getGroups()[2].userCount());
  }

  @Test
  public void testGetGroup() {
    Group governance = buildGroup(1L, "governance", Collections.singletonList("Gov Admin"));
    when(accessControlDispatcher.getExtendedGroup("metalake", "governance"))
        .thenReturn(ExtendedGroupDTO.from(governance, IdentitySource.PROVISIONED, 2));

    Response response = get("/web/security/metalakes/metalake/groups/governance");
    Assertions.assertEquals(Response.Status.OK.getStatusCode(), response.getStatus());
    ExtendedGroupResponse body = response.readEntity(ExtendedGroupResponse.class);
    Assertions.assertEquals("governance", body.getGroup().name());
    Assertions.assertEquals(IdentitySource.PROVISIONED, body.getGroup().origin());
    Assertions.assertEquals(2, body.getGroup().userCount());
  }

  @Test
  public void testListGroupUsers() {
    ExtendedUserDTO user = ExtendedUserDTO.from(buildUser("alice"), true);
    when(accessControlDispatcher.listExtendedUsersForGroup("metalake", "contractors"))
        .thenReturn(new ExtendedUserDTO[] {user});
    ExtendedUserListResponse body =
        get("/web/security/metalakes/metalake/groups/contractors/users")
            .readEntity(ExtendedUserListResponse.class);
    Assertions.assertEquals(1, body.getUsers().length);
    Assertions.assertEquals("alice", body.getUsers()[0].name());
    Assertions.assertEquals(IdentitySource.LOCAL, body.getUsers()[0].origin());
  }

  @Test
  public void testListIdpGroups() {
    when(accessControlDispatcher.listIdpGroups("metalake"))
        .thenReturn(new IdpNameStatus[] {new IdpNameStatus("contractors", false)});

    IdpGroupNameListResponse body =
        get("/web/security/metalakes/metalake/groups/idp")
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
        target("/web/security/metalakes/" + metalake + "/groups")
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
        target("/web/security/metalakes/" + metalake + "/groups")
            .request(MediaType.APPLICATION_JSON_TYPE)
            .accept("application/vnd.gravitino.v1+json")
            .post(entity(new LocalGroupAddRequest(" ", null), MediaType.APPLICATION_JSON_TYPE));

    Assertions.assertEquals(Response.Status.BAD_REQUEST.getStatusCode(), response.getStatus());
  }

  @Test
  public void testListDirectoryGroups() {
    when(accessControlDispatcher.listDirectoryGroups())
        .thenReturn(
            List.of(
                new DirectoryGroup("analysts", 0, IdentitySource.JIT, List.of("Contoso")),
                new DirectoryGroup("contractors", 0, IdentitySource.LOCAL, List.of("Acme")),
                new DirectoryGroup(
                    "governance", 2, IdentitySource.LOCAL, List.of("Acme", "Contoso")),
                new DirectoryGroup("platform", 1, IdentitySource.PROVISIONED, List.of("Contoso"))));

    Response response = get("/web/security/directory/groups");
    Assertions.assertEquals(Response.Status.OK.getStatusCode(), response.getStatus());
    DirectoryGroupListResponse body = response.readEntity(DirectoryGroupListResponse.class);
    Assertions.assertEquals(0, body.getCode());
    DirectoryGroupDTO[] groups = body.getGroups();
    Assertions.assertEquals(4, groups.length);
    Assertions.assertEquals(IdentitySource.JIT, groups[0].origin());
    Assertions.assertEquals(IdentitySource.LOCAL, groups[1].origin());
    Assertions.assertEquals(2, groups[2].memberCount());
    Assertions.assertEquals(IdentitySource.PROVISIONED, groups[3].origin());
  }

  @Test
  public void testListDirectoryGroupsAuthorization() throws NoSuchMethodException {
    Assertions.assertEquals(
        "SERVICE_ADMIN",
        ExtendedGroupOperations.class
            .getMethod("listDirectoryGroups")
            .getAnnotation(AuthorizationExpression.class)
            .expression());
  }

  @Test
  public void testAddDirectoryGroup() {
    when(accessControlDispatcher.addDirectoryGroup(
            eq("ops"), eq("Operations"), eq(List.of("sam.o", "lee.p"))))
        .thenReturn(new DirectoryGroup("ops", 2, IdentitySource.LOCAL, List.of()));

    Response response =
        target("/web/security/directory/groups")
            .request(MediaType.APPLICATION_JSON_TYPE)
            .accept("application/vnd.gravitino.v1+json")
            .post(
                entity(
                    new DirectoryGroupAddRequest("ops", "Operations", List.of("sam.o", "lee.p")),
                    MediaType.APPLICATION_JSON_TYPE));

    Assertions.assertEquals(Response.Status.OK.getStatusCode(), response.getStatus());
    DirectoryGroupResponse body = response.readEntity(DirectoryGroupResponse.class);
    Assertions.assertEquals(0, body.getCode());
    Assertions.assertEquals("ops", body.getGroup().name());
    Assertions.assertEquals(2, body.getGroup().memberCount());
    Assertions.assertEquals(IdentitySource.LOCAL, body.getGroup().origin());
  }

  @Test
  public void testDeleteDirectoryGroups() {
    when(accessControlDispatcher.deleteDirectoryGroups(
            eq(List.of("governance", "ops")),
            eq(List.of(IdentitySource.LOCAL, IdentitySource.LOCAL))))
        .thenReturn(List.of("governance", "ops"));

    Response response =
        target("/web/security/directory/groups/delete")
            .request(MediaType.APPLICATION_JSON_TYPE)
            .accept("application/vnd.gravitino.v1+json")
            .post(
                entity(
                    new DirectoryGroupDeleteRequest(
                        new DirectoryGroupDeleteRequest.DirectoryGroupDelete[] {
                          new DirectoryGroupDeleteRequest.DirectoryGroupDelete(
                              "governance", IdentitySource.LOCAL),
                          new DirectoryGroupDeleteRequest.DirectoryGroupDelete(
                              "ops", IdentitySource.LOCAL)
                        }),
                    MediaType.APPLICATION_JSON_TYPE));

    Assertions.assertEquals(Response.Status.OK.getStatusCode(), response.getStatus());
    NameListResponse body = response.readEntity(NameListResponse.class);
    Assertions.assertEquals(0, body.getCode());
    Assertions.assertArrayEquals(new String[] {"governance", "ops"}, body.getNames());
  }

  @Test
  public void testDeleteDirectoryGroupsBadRequest() {
    Response response =
        target("/web/security/directory/groups/delete")
            .request(MediaType.APPLICATION_JSON_TYPE)
            .accept("application/vnd.gravitino.v1+json")
            .post(
                entity(
                    new DirectoryGroupDeleteRequest(
                        new DirectoryGroupDeleteRequest.DirectoryGroupDelete[] {
                          new DirectoryGroupDeleteRequest.DirectoryGroupDelete(
                              " ", IdentitySource.LOCAL)
                        }),
                    MediaType.APPLICATION_JSON_TYPE));

    Assertions.assertEquals(Response.Status.BAD_REQUEST.getStatusCode(), response.getStatus());
  }

  @Test
  public void testDeleteDirectoryGroupsAuthorization() throws NoSuchMethodException {
    Assertions.assertEquals(
        "SERVICE_ADMIN",
        ExtendedGroupOperations.class
            .getMethod("deleteDirectoryGroups", DirectoryGroupDeleteRequest.class)
            .getAnnotation(AuthorizationExpression.class)
            .expression());
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

  private static Group buildGroup(Long id, String name, List<String> roles) {
    return GroupEntity.builder()
        .withId(id)
        .withName(name)
        .withNamespace(Namespace.of("metalake", "system", "group"))
        .withRoleNames(roles)
        .withAuditInfo(
            AuditInfo.builder().withCreator("test").withCreateTime(Instant.now()).build())
        .build();
  }

  private static User buildUser(String name) {
    return UserEntity.builder()
        .withId(1L)
        .withName(name)
        .withNamespace(Namespace.of("metalake", "system", "user"))
        .withRoleNames(Collections.emptyList())
        .withAuditInfo(
            AuditInfo.builder().withCreator("test").withCreateTime(Instant.now()).build())
        .build();
  }
}
