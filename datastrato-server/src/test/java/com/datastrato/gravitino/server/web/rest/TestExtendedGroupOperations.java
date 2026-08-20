/*
 * Copyright 2026 Datastrato Pvt Ltd.
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
import com.datastrato.gravitino.dto.authorization.IdentitySource;
import com.datastrato.gravitino.dto.responses.ExtendedGroupListResponse;
import java.io.IOException;
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
import org.apache.gravitino.Namespace;
import org.apache.gravitino.authorization.Group;
import org.apache.gravitino.connector.PropertiesMetadata;
import org.apache.gravitino.lock.LockManager;
import org.apache.gravitino.meta.AuditInfo;
import org.apache.gravitino.meta.BaseMetalake;
import org.apache.gravitino.meta.GroupEntity;
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
    Group local = buildGroup(1L, "contractors", null, Collections.singletonList("pii_reader"));
    Group provisioned =
        buildGroup(2L, "governance", "azure-oid", Collections.singletonList("Gov Admin"));
    when(accessControlDispatcher.listGroups(metalake)).thenReturn(new Group[] {local, provisioned});

    Response response =
        target("/web/security/metalakes/" + metalake + "/groups")
            .request(MediaType.APPLICATION_JSON_TYPE)
            .accept("application/vnd.gravitino.v1+json")
            .get();

    Assertions.assertEquals(Response.Status.OK.getStatusCode(), response.getStatus());
    ExtendedGroupListResponse body = response.readEntity(ExtendedGroupListResponse.class);
    Assertions.assertEquals(0, body.getCode());
    Assertions.assertEquals(2, body.getGroups().length);
    Assertions.assertEquals("contractors", body.getGroups()[0].name());
    Assertions.assertNull(body.getGroups()[0].externalId());
    Assertions.assertEquals(IdentitySource.LOCAL, body.getGroups()[0].origin());
    Assertions.assertEquals("governance", body.getGroups()[1].name());
    Assertions.assertEquals("azure-oid", body.getGroups()[1].externalId());
    Assertions.assertEquals(IdentitySource.PROVISIONED, body.getGroups()[1].origin());
  }

  @Test
  public void testAuthorizationAnnotations() throws NoSuchMethodException {
    java.lang.reflect.Method listGroups =
        ExtendedGroupOperations.class.getMethod("listGroups", String.class);
    Assertions.assertEquals(
        "METALAKE::OWNER || METALAKE::MANAGE_GROUPS",
        listGroups.getAnnotation(AuthorizationExpression.class).expression());
    Assertions.assertEquals(
        Entity.EntityType.METALAKE,
        listGroups.getParameters()[0].getAnnotation(AuthorizationMetadata.class).type());
  }

  private void mockInUseMetalake() throws IOException {
    BaseMetalake metalake = mock(BaseMetalake.class);
    PropertiesMetadata propertiesMetadata = mock(PropertiesMetadata.class);
    when(propertiesMetadata.getOrDefault(any(), any())).thenReturn(true);
    when(metalake.propertiesMetadata()).thenReturn(propertiesMetadata);
    when(entityStore.get(any(), any(), any())).thenReturn(metalake);
  }

  private static Group buildGroup(
      Long id, String name, String externalId, java.util.List<String> roles) {
    return GroupEntity.builder()
        .withId(id)
        .withName(name)
        .withNamespace(Namespace.of("metalake", "system", "group"))
        .withExternalId(externalId)
        .withRoleNames(roles)
        .withAuditInfo(
            AuditInfo.builder().withCreator("test").withCreateTime(Instant.now()).build())
        .build();
  }
}
