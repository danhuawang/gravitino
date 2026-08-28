/*
 * Copyright 2026 Datastrato Pvt Ltd.
 */
package com.datastrato.gravitino.server.web.rest;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.reset;
import static org.mockito.Mockito.when;

import com.datastrato.gravitino.ExtendedDatastratoGravitinoEnv;
import com.datastrato.gravitino.authorization.DatastratoAccessControlDispatcher;
import com.datastrato.gravitino.authorization.GroupLookupInfo;
import com.datastrato.gravitino.dto.authorization.IdentityType;
import com.datastrato.gravitino.dto.responses.GroupInfoResponse;
import com.datastrato.gravitino.dto.responses.UserGroupNamesResponse;
import java.util.Collections;
import java.util.List;
import javax.servlet.http.HttpServletRequest;
import javax.ws.rs.core.Application;
import javax.ws.rs.core.MediaType;
import javax.ws.rs.core.Response;
import org.apache.commons.lang3.reflect.FieldUtils;
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

/** Tests for {@link ExtendedIdentityOperations}. */
public class TestExtendedIdentityOperations extends JerseyTest {

  private static final DatastratoAccessControlDispatcher accessControlDispatcher =
      mock(DatastratoAccessControlDispatcher.class);

  private static class MockServletRequestFactory extends ServletRequestFactoryBase {
    @Override
    public HttpServletRequest get() {
      HttpServletRequest request = mock(HttpServletRequest.class);
      when(request.getRemoteUser()).thenReturn(null);
      return request;
    }
  }

  /** Installs the mocked access-control dispatcher into the test environment. */
  @BeforeAll
  public static void setup() throws IllegalAccessException {
    FieldUtils.writeField(
        ExtendedDatastratoGravitinoEnv.getInstance(),
        "accessControlDispatcher",
        accessControlDispatcher,
        true);
  }

  /** Resets mocks before each test. */
  @BeforeEach
  public void resetMocks() {
    reset(accessControlDispatcher);
  }

  /** Configures the Jersey test application. */
  @Override
  protected Application configure() {
    try {
      forceSet(
          TestProperties.CONTAINER_PORT, String.valueOf(RESTUtils.findAvailablePort(2000, 3000)));
    } catch (Exception e) {
      throw new RuntimeException(e);
    }

    ResourceConfig resourceConfig = new ResourceConfig();
    resourceConfig.register(ExtendedIdentityOperations.class);
    resourceConfig.register(
        new AbstractBinder() {
          @Override
          protected void configure() {
            bindFactory(MockServletRequestFactory.class).to(HttpServletRequest.class);
          }
        });
    return resourceConfig;
  }

  /** Verifies local user identity group lookup. */
  @Test
  public void testGetUserGroupsLocal() {
    when(accessControlDispatcher.lookupUserGroupNames("alice", IdentityType.LOCAL))
        .thenReturn(List.of("contractors", "analysts"));

    Response response =
        target("/web/security/identity/users/alice/groups")
            .queryParam("type", "local")
            .request(MediaType.APPLICATION_JSON_TYPE)
            .accept("application/vnd.gravitino.v1+json")
            .get();

    Assertions.assertEquals(Response.Status.OK.getStatusCode(), response.getStatus());
    UserGroupNamesResponse body = response.readEntity(UserGroupNamesResponse.class);
    Assertions.assertEquals(0, body.getCode());
    Assertions.assertEquals("alice", body.getUsername());
    Assertions.assertEquals(IdentityType.LOCAL, body.getType());
    Assertions.assertArrayEquals(new String[] {"contractors", "analysts"}, body.getGroupNames());
  }

  /** Verifies provisioned user identity group lookup returns empty groups for now. */
  @Test
  public void testGetUserGroupsProvisioned() {
    when(accessControlDispatcher.lookupUserGroupNames("dana.k", IdentityType.PROVISIONED))
        .thenReturn(Collections.emptyList());

    Response response =
        target("/web/security/identity/users/dana.k/groups")
            .queryParam("type", "provisioned")
            .request(MediaType.APPLICATION_JSON_TYPE)
            .accept("application/vnd.gravitino.v1+json")
            .get();

    Assertions.assertEquals(Response.Status.OK.getStatusCode(), response.getStatus());
    UserGroupNamesResponse body = response.readEntity(UserGroupNamesResponse.class);
    Assertions.assertEquals(0, body.getCode());
    Assertions.assertEquals("dana.k", body.getUsername());
    Assertions.assertEquals(IdentityType.PROVISIONED, body.getType());
    Assertions.assertArrayEquals(new String[0], body.getGroupNames());
  }

  /** Verifies local group identity lookup. */
  @Test
  public void testGetGroupInfoLocal() {
    when(accessControlDispatcher.lookupGroupInfo("contractors", IdentityType.LOCAL))
        .thenReturn(
            new GroupLookupInfo(
                "contractors", "External analysts on time-boxed access", List.of("alice", "bob")));

    Response response =
        target("/web/security/identity/groups/contractors")
            .queryParam("type", "local")
            .request(MediaType.APPLICATION_JSON_TYPE)
            .accept("application/vnd.gravitino.v1+json")
            .get();

    Assertions.assertEquals(Response.Status.OK.getStatusCode(), response.getStatus());
    GroupInfoResponse body = response.readEntity(GroupInfoResponse.class);
    Assertions.assertEquals(0, body.getCode());
    Assertions.assertEquals("contractors", body.getGroupName());
    Assertions.assertEquals(IdentityType.LOCAL, body.getType());
    Assertions.assertEquals("External analysts on time-boxed access", body.getComment());
    Assertions.assertEquals(List.of("alice", "bob"), body.getMembers());
  }

  /** Verifies provisioned group identity lookup returns empty metadata for now. */
  @Test
  public void testGetGroupInfoProvisioned() {
    when(accessControlDispatcher.lookupGroupInfo("scim-team", IdentityType.PROVISIONED))
        .thenReturn(new GroupLookupInfo("scim-team", "", Collections.emptyList()));

    Response response =
        target("/web/security/identity/groups/scim-team")
            .queryParam("type", "provisioned")
            .request(MediaType.APPLICATION_JSON_TYPE)
            .accept("application/vnd.gravitino.v1+json")
            .get();

    Assertions.assertEquals(Response.Status.OK.getStatusCode(), response.getStatus());
    GroupInfoResponse body = response.readEntity(GroupInfoResponse.class);
    Assertions.assertEquals(0, body.getCode());
    Assertions.assertEquals("scim-team", body.getGroupName());
    Assertions.assertEquals(IdentityType.PROVISIONED, body.getType());
    Assertions.assertEquals("", body.getComment());
    Assertions.assertEquals(Collections.emptyList(), body.getMembers());
  }

  /** Verifies authorization annotations on lookup endpoints. */
  @Test
  public void testAuthorizationAnnotations() throws NoSuchMethodException {
    Assertions.assertEquals(
        "SERVICE_ADMIN",
        ExtendedIdentityOperations.class
            .getMethod("getUserGroups", String.class, String.class)
            .getAnnotation(AuthorizationExpression.class)
            .expression());
    Assertions.assertEquals(
        "SERVICE_ADMIN",
        ExtendedIdentityOperations.class
            .getMethod("getGroupInfo", String.class, String.class)
            .getAnnotation(AuthorizationExpression.class)
            .expression());
  }
}
