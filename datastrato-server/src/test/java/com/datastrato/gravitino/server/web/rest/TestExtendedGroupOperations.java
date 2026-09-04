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
import com.datastrato.gravitino.dto.authorization.DirectoryGroupDTO;
import com.datastrato.gravitino.dto.authorization.IdentitySource;
import com.datastrato.gravitino.dto.requests.DirectoryGroupAddRequest;
import com.datastrato.gravitino.dto.requests.DirectoryGroupDeleteRequest;
import com.datastrato.gravitino.dto.responses.DirectoryGroupListResponse;
import com.datastrato.gravitino.dto.responses.DirectoryGroupResponse;
import java.io.IOException;
import java.util.List;
import javax.servlet.http.HttpServletRequest;
import javax.ws.rs.core.Application;
import javax.ws.rs.core.MediaType;
import javax.ws.rs.core.Response;
import org.apache.commons.lang3.reflect.FieldUtils;
import org.apache.gravitino.Config;
import org.apache.gravitino.EntityStore;
import org.apache.gravitino.GravitinoEnv;
import org.apache.gravitino.connector.PropertiesMetadata;
import org.apache.gravitino.dto.responses.NameListResponse;
import org.apache.gravitino.lock.LockManager;
import org.apache.gravitino.meta.BaseMetalake;
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
}
