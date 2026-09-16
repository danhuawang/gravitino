/*
 * Copyright 2026 Datastrato Inc.
 */
package com.datastrato.gravitino.server.web.rest;

import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.reset;
import static org.mockito.Mockito.when;

import com.datastrato.gravitino.ExtendedDatastratoGravitinoEnv;
import com.datastrato.gravitino.authorization.DatastratoAccessControlDispatcher;
import com.datastrato.gravitino.dto.authorization.AuthMeRoleDTO;
import com.datastrato.gravitino.dto.authorization.AuthMeSecurableObjectDTO;
import com.datastrato.gravitino.dto.authorization.IdentitySource;
import com.datastrato.gravitino.dto.responses.ExtendedAuthMeResponse;
import java.io.IOException;
import java.util.List;
import java.util.Map;
import javax.servlet.http.HttpServletRequest;
import javax.ws.rs.core.Application;
import javax.ws.rs.core.MediaType;
import javax.ws.rs.core.Response;
import org.apache.commons.lang3.reflect.FieldUtils;
import org.apache.gravitino.Config;
import org.apache.gravitino.Configs;
import org.apache.gravitino.GravitinoEnv;
import org.apache.gravitino.MetadataObject;
import org.apache.gravitino.UserPrincipal;
import org.apache.gravitino.auth.AuthConstants;
import org.apache.gravitino.lock.LockManager;
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

public class TestExtendedAuthnOperations extends JerseyTest {

  private static final String TEST_PRINCIPAL = "sai";
  private static final DatastratoAccessControlDispatcher accessControlDispatcher =
      mock(DatastratoAccessControlDispatcher.class);

  private static class MockServletRequestFactory extends ServletRequestFactoryBase {
    @Override
    public HttpServletRequest get() {
      HttpServletRequest request = mock(HttpServletRequest.class);
      when(request.getRemoteUser()).thenReturn(null);
      when(request.getAttribute(AuthConstants.AUTHENTICATED_PRINCIPAL_ATTRIBUTE_NAME))
          .thenReturn(new UserPrincipal(TEST_PRINCIPAL));
      return request;
    }
  }

  @BeforeAll
  public static void setup() throws IllegalAccessException {
    Config config = mock(Config.class);
    Mockito.doReturn(100000L).when(config).get(Configs.TREE_LOCK_MAX_NODE_IN_MEMORY);
    Mockito.doReturn(1000L).when(config).get(Configs.TREE_LOCK_MIN_NODE_IN_MEMORY);
    Mockito.doReturn(36000L).when(config).get(Configs.TREE_LOCK_CLEAN_INTERVAL);
    FieldUtils.writeField(GravitinoEnv.getInstance(), "lockManager", new LockManager(config), true);
    FieldUtils.writeField(
        ExtendedDatastratoGravitinoEnv.getInstance(),
        "accessControlDispatcher",
        accessControlDispatcher,
        true);
  }

  @BeforeEach
  public void resetMocks() {
    reset(accessControlDispatcher);
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
    resourceConfig.register(new ExtendedAuthnOperations(accessControlDispatcher));
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
  public void testGetAuthnMe() {
    AuthMeSecurableObjectDTO catalog =
        new AuthMeSecurableObjectDTO(
            "catalog1", MetadataObject.Type.CATALOG, List.of("USE_CATALOG"));
    AuthMeRoleDTO admin = new AuthMeRoleDTO("admin", List.of(catalog));

    when(accessControlDispatcher.isServiceAdmin(eq(TEST_PRINCIPAL))).thenReturn(false);
    when(accessControlDispatcher.listUserOrigins(eq(TEST_PRINCIPAL)))
        .thenReturn(List.of(IdentitySource.LOCAL, IdentitySource.JIT));
    when(accessControlDispatcher.listOwnedMetalakes(eq(TEST_PRINCIPAL)))
        .thenReturn(List.of("metalake1"));
    when(accessControlDispatcher.listUserRolesByMetalake(eq(TEST_PRINCIPAL)))
        .thenReturn(Map.of("metalake1", List.of(admin), "metalake2", List.of()));

    Response response =
        target("/internal/security/authn/me")
            .request(MediaType.APPLICATION_JSON_TYPE)
            .accept("application/vnd.gravitino.v1+json")
            .get();

    Assertions.assertEquals(Response.Status.OK.getStatusCode(), response.getStatus());
    ExtendedAuthMeResponse body = response.readEntity(ExtendedAuthMeResponse.class);
    Assertions.assertEquals(0, body.getCode());
    Assertions.assertEquals(TEST_PRINCIPAL, body.getPrincipal());
    Assertions.assertFalse(body.isServiceAdmin());
    Assertions.assertEquals(List.of(IdentitySource.LOCAL, IdentitySource.JIT), body.getOrigins());
    Assertions.assertEquals(List.of("metalake1"), body.getOwnedMetalakes());
    Assertions.assertEquals(1, body.getRoles().get("metalake1").size());
    Assertions.assertEquals("admin", body.getRoles().get("metalake1").get(0).getName());
    Assertions.assertEquals(
        1, body.getRoles().get("metalake1").get(0).getSecurableObjects().size());
    Assertions.assertEquals(
        "catalog1",
        body.getRoles().get("metalake1").get(0).getSecurableObjects().get(0).getFullName());
    Assertions.assertEquals(
        MetadataObject.Type.CATALOG,
        body.getRoles().get("metalake1").get(0).getSecurableObjects().get(0).getType());
    Assertions.assertEquals(
        List.of("USE_CATALOG"),
        body.getRoles().get("metalake1").get(0).getSecurableObjects().get(0).getPrivileges());
    Assertions.assertTrue(body.getRoles().get("metalake2").isEmpty());
  }

  @Test
  public void testGetAuthnMeServiceAdmin() {
    when(accessControlDispatcher.isServiceAdmin(eq(TEST_PRINCIPAL))).thenReturn(true);
    when(accessControlDispatcher.listUserOrigins(eq(TEST_PRINCIPAL)))
        .thenReturn(List.of(IdentitySource.LOCAL));
    when(accessControlDispatcher.listOwnedMetalakes(eq(TEST_PRINCIPAL))).thenReturn(List.of());
    when(accessControlDispatcher.listUserRolesByMetalake(eq(TEST_PRINCIPAL))).thenReturn(Map.of());

    Response response =
        target("/internal/security/authn/me")
            .request(MediaType.APPLICATION_JSON_TYPE)
            .accept("application/vnd.gravitino.v1+json")
            .get();

    Assertions.assertEquals(Response.Status.OK.getStatusCode(), response.getStatus());
    ExtendedAuthMeResponse body = response.readEntity(ExtendedAuthMeResponse.class);
    Assertions.assertTrue(body.isServiceAdmin());
    Assertions.assertEquals(List.of(IdentitySource.LOCAL), body.getOrigins());
    Assertions.assertTrue(body.getOwnedMetalakes().isEmpty());
    Assertions.assertTrue(body.getRoles().isEmpty());
  }

  @Test
  public void testAuthnMeAuthorizationExpression() throws NoSuchMethodException {
    Assertions.assertEquals(
        "",
        ExtendedAuthnOperations.class
            .getMethod("me")
            .getAnnotation(AuthorizationExpression.class)
            .expression());
  }
}
