/*
 * Copyright 2026 Datastrato Inc.
 */
package com.datastrato.gravitino.server.web.rest;

import static javax.ws.rs.client.Entity.entity;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.reset;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.datastrato.gravitino.ExtendedDatastratoGravitinoEnv;
import com.datastrato.gravitino.authorization.DatastratoAccessControlDispatcher;
import com.datastrato.gravitino.dto.requests.IdpMembershipAddRequest;
import com.datastrato.gravitino.dto.requests.IdpPasswordChangeRequest;
import java.io.IOException;
import java.util.List;
import javax.servlet.http.HttpServletRequest;
import javax.ws.rs.core.Application;
import javax.ws.rs.core.MediaType;
import javax.ws.rs.core.Response;
import org.apache.commons.lang3.reflect.FieldUtils;
import org.apache.gravitino.authorization.GravitinoAuthorizer;
import org.apache.gravitino.dto.responses.BaseResponse;
import org.apache.gravitino.dto.responses.NameListResponse;
import org.apache.gravitino.exceptions.UnauthorizedException;
import org.apache.gravitino.rest.RESTUtils;
import org.apache.gravitino.server.authorization.GravitinoAuthorizerProvider;
import org.apache.gravitino.server.authorization.annotations.AuthorizationExpression;
import org.glassfish.hk2.utilities.binding.AbstractBinder;
import org.glassfish.jersey.server.ResourceConfig;
import org.glassfish.jersey.test.JerseyTest;
import org.glassfish.jersey.test.TestProperties;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/** Tests instance-scoped Local IdP REST operations. */
public class TestExtendedIdpOperations extends JerseyTest {

  private static final DatastratoAccessControlDispatcher ACCESS_CONTROL_DISPATCHER =
      mock(DatastratoAccessControlDispatcher.class);
  private static final GravitinoAuthorizer GRAVITINO_AUTHORIZER = mock(GravitinoAuthorizer.class);
  private static GravitinoAuthorizer previousAuthorizer;

  private static class MockServletRequestFactory extends ServletRequestFactoryBase {
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
    FieldUtils.writeField(
        ExtendedDatastratoGravitinoEnv.getInstance(),
        "accessControlDispatcher",
        ACCESS_CONTROL_DISPATCHER,
        true);
    GravitinoAuthorizerProvider authorizerProvider = GravitinoAuthorizerProvider.getInstance();
    previousAuthorizer =
        (GravitinoAuthorizer) FieldUtils.readField(authorizerProvider, "gravitinoAuthorizer", true);
    FieldUtils.writeField(authorizerProvider, "gravitinoAuthorizer", GRAVITINO_AUTHORIZER, true);
  }

  /** Restores the previous authorizer after tests. */
  @AfterAll
  public static void tearDownAuthorizer() throws IllegalAccessException {
    FieldUtils.writeField(
        GravitinoAuthorizerProvider.getInstance(), "gravitinoAuthorizer", previousAuthorizer, true);
  }

  /** Resets mocks before each test. */
  @BeforeEach
  public void resetMocks() {
    reset(ACCESS_CONTROL_DISPATCHER, GRAVITINO_AUTHORIZER);
    when(GRAVITINO_AUTHORIZER.isServiceAdmin()).thenReturn(true);
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
    resourceConfig.register(ExtendedIdpOperations.class);
    resourceConfig.register(
        new AbstractBinder() {
          @Override
          protected void configure() {
            bindFactory(MockServletRequestFactory.class).to(HttpServletRequest.class);
          }
        });
    return resourceConfig;
  }

  /** Tests listing Local IdP usernames. */
  @Test
  public void testListIdpUserNames() {
    when(ACCESS_CONTROL_DISPATCHER.listIdpUserNames()).thenReturn(new String[] {"alice", "bob"});

    Response response = get("/internal/security/idp/users");
    Assertions.assertEquals(Response.Status.OK.getStatusCode(), response.getStatus());
    NameListResponse body = response.readEntity(NameListResponse.class);
    Assertions.assertEquals(0, body.getCode());
    Assertions.assertArrayEquals(new String[] {"alice", "bob"}, body.getNames());
  }

  /** Tests listing Local IdP group names. */
  @Test
  public void testListIdpGroupNames() {
    when(ACCESS_CONTROL_DISPATCHER.listIdpGroupNames()).thenReturn(new String[] {"ops", "eng"});

    Response response = get("/internal/security/idp/groups");
    Assertions.assertEquals(Response.Status.OK.getStatusCode(), response.getStatus());
    NameListResponse body = response.readEntity(NameListResponse.class);
    Assertions.assertEquals(0, body.getCode());
    Assertions.assertArrayEquals(new String[] {"ops", "eng"}, body.getNames());
  }

  /** Tests bulk-adding Local IdP memberships. */
  @Test
  public void testAddIdpUserGroupMemberships() {
    Response response =
        target("/internal/security/idp/memberships")
            .request(MediaType.APPLICATION_JSON_TYPE)
            .accept("application/vnd.gravitino.v1+json")
            .post(
                entity(
                    new IdpMembershipAddRequest(
                        List.of("sam.o", "lee.p"), List.of("governance", "ops")),
                    MediaType.APPLICATION_JSON_TYPE));

    Assertions.assertEquals(Response.Status.OK.getStatusCode(), response.getStatus());
    BaseResponse body = response.readEntity(BaseResponse.class);
    Assertions.assertEquals(0, body.getCode());
    verify(ACCESS_CONTROL_DISPATCHER)
        .addIdpUserGroupMemberships(
            eq(List.of("sam.o", "lee.p")), eq(List.of("governance", "ops")));
  }

  /** Tests changing a Local IdP password after verifying the old password. */
  @Test
  public void testChangeIdpUserPassword() {
    Response response =
        target("/internal/security/idp/users/password")
            .request(MediaType.APPLICATION_JSON_TYPE)
            .accept("application/vnd.gravitino.v1+json")
            .put(
                entity(
                    new IdpPasswordChangeRequest("dana.k", "ChangeMe-2026!", "ChangeMe-2027!"),
                    MediaType.APPLICATION_JSON_TYPE));

    Assertions.assertEquals(Response.Status.OK.getStatusCode(), response.getStatus());
    BaseResponse body = response.readEntity(BaseResponse.class);
    Assertions.assertEquals(0, body.getCode());
    verify(ACCESS_CONTROL_DISPATCHER)
        .changeIdpUserPassword(eq("dana.k"), eq("ChangeMe-2026!"), eq("ChangeMe-2027!"));
  }

  /** Tests rejecting password change when old credentials are invalid. */
  @Test
  public void testChangeIdpUserPasswordUnauthorized() {
    doThrow(new UnauthorizedException("Invalid username or password", "Basic"))
        .when(ACCESS_CONTROL_DISPATCHER)
        .changeIdpUserPassword(eq("dana.k"), eq("wrong-password"), eq("ChangeMe-2027!"));

    Response response =
        target("/internal/security/idp/users/password")
            .request(MediaType.APPLICATION_JSON_TYPE)
            .accept("application/vnd.gravitino.v1+json")
            .put(
                entity(
                    new IdpPasswordChangeRequest("dana.k", "wrong-password", "ChangeMe-2027!"),
                    MediaType.APPLICATION_JSON_TYPE));

    Assertions.assertEquals(Response.Status.UNAUTHORIZED.getStatusCode(), response.getStatus());
  }

  /** Tests rejecting an empty membership request. */
  @Test
  public void testAddIdpUserGroupMembershipsBadRequest() {
    Response response =
        target("/internal/security/idp/memberships")
            .request(MediaType.APPLICATION_JSON_TYPE)
            .accept("application/vnd.gravitino.v1+json")
            .post(
                entity(
                    new IdpMembershipAddRequest(List.of(), List.of("ops")),
                    MediaType.APPLICATION_JSON_TYPE));

    Assertions.assertEquals(Response.Status.BAD_REQUEST.getStatusCode(), response.getStatus());
  }

  /** Tests SERVICE_ADMIN authorization on IdP endpoints. */
  @Test
  public void testAuthorizationAnnotations() throws NoSuchMethodException {
    Assertions.assertEquals(
        "SERVICE_ADMIN",
        ExtendedIdpOperations.class
            .getMethod("listIdpUserNames")
            .getAnnotation(AuthorizationExpression.class)
            .expression());
    Assertions.assertEquals(
        "SERVICE_ADMIN",
        ExtendedIdpOperations.class
            .getMethod("listIdpGroupNames")
            .getAnnotation(AuthorizationExpression.class)
            .expression());
    Assertions.assertEquals(
        "SERVICE_ADMIN",
        ExtendedIdpOperations.class
            .getMethod("addIdpUserGroupMemberships", IdpMembershipAddRequest.class)
            .getAnnotation(AuthorizationExpression.class)
            .expression());
    Assertions.assertEquals(
        "",
        ExtendedIdpOperations.class
            .getMethod("changeIdpUserPassword", IdpPasswordChangeRequest.class)
            .getAnnotation(AuthorizationExpression.class)
            .expression());
  }

  private Response get(String path) {
    return target(path)
        .request(MediaType.APPLICATION_JSON_TYPE)
        .accept("application/vnd.gravitino.v1+json")
        .get();
  }
}
