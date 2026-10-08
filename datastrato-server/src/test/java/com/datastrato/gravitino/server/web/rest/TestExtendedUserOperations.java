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
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.reset;
import static org.mockito.Mockito.when;

import com.datastrato.gravitino.ExtendedDatastratoGravitinoEnv;
import com.datastrato.gravitino.authorization.DatastratoAccessControlDispatcher;
import com.datastrato.gravitino.authorization.DirectoryGroup;
import com.datastrato.gravitino.authorization.DirectoryUser;
import com.datastrato.gravitino.dto.authorization.DirectoryGroupDTO;
import com.datastrato.gravitino.dto.authorization.DirectoryUserDTO;
import com.datastrato.gravitino.dto.authorization.IdentitySource;
import com.datastrato.gravitino.dto.requests.DirectoryUserAddRequest;
import com.datastrato.gravitino.dto.requests.DirectoryUserDeleteRequest;
import com.datastrato.gravitino.dto.requests.DirectoryUserEnabledBatchUpdateRequest;
import com.datastrato.gravitino.dto.responses.DirectoryGroupListResponse;
import com.datastrato.gravitino.dto.responses.DirectoryUserListResponse;
import com.datastrato.gravitino.dto.responses.DirectoryUserResponse;
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
import org.apache.gravitino.auth.AuthConstants;
import org.apache.gravitino.authorization.GravitinoAuthorizer;
import org.apache.gravitino.connector.PropertiesMetadata;
import org.apache.gravitino.dto.responses.ErrorConstants;
import org.apache.gravitino.dto.responses.ErrorResponse;
import org.apache.gravitino.dto.responses.NameListResponse;
import org.apache.gravitino.exceptions.ForbiddenException;
import org.apache.gravitino.exceptions.UserAlreadyExistsException;
import org.apache.gravitino.lock.LockManager;
import org.apache.gravitino.meta.BaseMetalake;
import org.apache.gravitino.rest.RESTUtils;
import org.apache.gravitino.server.authorization.GravitinoAuthorizerProvider;
import org.apache.gravitino.server.authorization.annotations.AuthorizationExpression;
import org.apache.gravitino.utils.PrincipalUtils;
import org.glassfish.hk2.utilities.binding.AbstractBinder;
import org.glassfish.jersey.server.ResourceConfig;
import org.glassfish.jersey.test.JerseyTest;
import org.glassfish.jersey.test.TestProperties;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.MockedStatic;
import org.mockito.Mockito;

public class TestExtendedUserOperations extends JerseyTest {

  private static final DatastratoAccessControlDispatcher accessControlDispatcher =
      mock(DatastratoAccessControlDispatcher.class);
  private static final EntityStore entityStore = mock(EntityStore.class);
  private static final GravitinoAuthorizer gravitinoAuthorizer = mock(GravitinoAuthorizer.class);
  private static GravitinoAuthorizer previousAuthorizer;
  private static final ThreadLocal<String> authorizationHeader = new ThreadLocal<>();

  private static class MockServletRequestFactory extends ServletRequestFactoryBase {
    @Override
    public HttpServletRequest get() {
      HttpServletRequest request = mock(HttpServletRequest.class);
      when(request.getRemoteUser()).thenReturn(null);
      when(request.getHeader(AuthConstants.HTTP_HEADER_AUTHORIZATION))
          .thenAnswer(invocation -> authorizationHeader.get());
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
    GravitinoAuthorizerProvider authorizerProvider = GravitinoAuthorizerProvider.getInstance();
    previousAuthorizer =
        (GravitinoAuthorizer) FieldUtils.readField(authorizerProvider, "gravitinoAuthorizer", true);
    FieldUtils.writeField(authorizerProvider, "gravitinoAuthorizer", gravitinoAuthorizer, true);
  }

  @AfterAll
  public static void tearDownAuthorizer() throws IllegalAccessException {
    FieldUtils.writeField(
        GravitinoAuthorizerProvider.getInstance(), "gravitinoAuthorizer", previousAuthorizer, true);
  }

  @BeforeEach
  public void resetMocks() throws IOException {
    reset(accessControlDispatcher, entityStore, gravitinoAuthorizer);
    when(gravitinoAuthorizer.isServiceAdmin()).thenReturn(true);
    authorizationHeader.remove();
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
    resourceConfig.register(ExtendedUserOperations.class);
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
  public void testListDirectoryUsers() {
    when(accessControlDispatcher.listDirectoryUsers())
        .thenReturn(
            List.of(
                new DirectoryUser(
                    "dana.k",
                    true,
                    IdentitySource.PROVISIONED,
                    List.of("finance"),
                    List.of("Acme", "Contoso"),
                    false,
                    false),
                new DirectoryUser(
                    "jordan.m", true, null, List.of(), List.of("Contoso"), false, false),
                new DirectoryUser(
                    "lee.p", false, IdentitySource.LOCAL, List.of(), List.of("Acme"), true, true),
                new DirectoryUser(
                    "sam.o",
                    true,
                    IdentitySource.LOCAL,
                    List.of("governance", "ops"),
                    List.of("Contoso"),
                    true,
                    true)));

    Response response = get("/internal/security/directory/users");
    Assertions.assertEquals(Response.Status.OK.getStatusCode(), response.getStatus());
    DirectoryUserListResponse body = response.readEntity(DirectoryUserListResponse.class);
    Assertions.assertEquals(0, body.getCode());
    DirectoryUserDTO[] users = body.getUsers();
    Assertions.assertEquals(4, users.length);
    Assertions.assertEquals("dana.k", users[0].name());
    Assertions.assertEquals(IdentitySource.PROVISIONED, users[0].origin());
    Assertions.assertFalse(users[0].canDelete());
    Assertions.assertFalse(users[0].canSuspend());
    Assertions.assertEquals("jordan.m", users[1].name());
    Assertions.assertNull(users[1].origin());
    Assertions.assertEquals(IdentitySource.LOCAL, users[2].origin());
    Assertions.assertTrue(users[2].canDelete());
    Assertions.assertTrue(users[2].canSuspend());
    Assertions.assertEquals(IdentitySource.LOCAL, users[3].origin());
    Assertions.assertTrue(users[3].canDelete());
    Assertions.assertTrue(users[3].canSuspend());
  }

  @Test
  public void testListDirectoryUsersAuthorization() throws NoSuchMethodException {
    Assertions.assertEquals(
        "SERVICE_ADMIN",
        ExtendedUserOperations.class
            .getMethod("listDirectoryUsers")
            .getAnnotation(AuthorizationExpression.class)
            .expression());
  }

  @Test
  public void testGetDirectoryUserAuthorization() throws NoSuchMethodException {
    Assertions.assertEquals(
        "",
        ExtendedUserOperations.class
            .getMethod("getDirectoryUser", String.class, String.class)
            .getAnnotation(AuthorizationExpression.class)
            .expression());
  }

  @Test
  public void testRequireServiceAdminOrSelf() {
    when(gravitinoAuthorizer.isServiceAdmin()).thenReturn(true);
    Assertions.assertDoesNotThrow(() -> ExtendedUserOperations.requireServiceAdminOrSelf("anyone"));

    when(gravitinoAuthorizer.isServiceAdmin()).thenReturn(false);
    try (MockedStatic<PrincipalUtils> principalUtils = mockStatic(PrincipalUtils.class)) {
      principalUtils.when(PrincipalUtils::getCurrentUserName).thenReturn("dana.k");
      Assertions.assertDoesNotThrow(
          () -> ExtendedUserOperations.requireServiceAdminOrSelf("dana.k"));
      Assertions.assertThrows(
          ForbiddenException.class,
          () -> ExtendedUserOperations.requireServiceAdminOrSelf("other.user"));
    }
  }

  @Test
  public void testBatchUpdateDirectoryUserEnabled() {
    when(accessControlDispatcher.batchUpdateDirectoryUserEnabled(
            eq(List.of("sam.o", "lee.p")),
            eq(List.of(IdentitySource.LOCAL, IdentitySource.LOCAL)),
            eq(false)))
        .thenReturn(List.of("sam.o", "lee.p"));

    Response response =
        target("/internal/security/directory/users/enabled")
            .request(MediaType.APPLICATION_JSON_TYPE)
            .accept("application/vnd.gravitino.v1+json")
            .put(
                entity(
                    new DirectoryUserEnabledBatchUpdateRequest(
                        new DirectoryUserEnabledBatchUpdateRequest.DirectoryUserEnabledUpdate[] {
                          new DirectoryUserEnabledBatchUpdateRequest.DirectoryUserEnabledUpdate(
                              "sam.o", IdentitySource.LOCAL),
                          new DirectoryUserEnabledBatchUpdateRequest.DirectoryUserEnabledUpdate(
                              "lee.p", IdentitySource.LOCAL)
                        },
                        false),
                    MediaType.APPLICATION_JSON_TYPE));

    Assertions.assertEquals(Response.Status.OK.getStatusCode(), response.getStatus());
    NameListResponse body = response.readEntity(NameListResponse.class);
    Assertions.assertEquals(0, body.getCode());
    Assertions.assertArrayEquals(new String[] {"sam.o", "lee.p"}, body.getNames());
  }

  @Test
  public void testBatchUpdateDirectoryUserEnabledBadRequest() {
    Response response =
        target("/internal/security/directory/users/enabled")
            .request(MediaType.APPLICATION_JSON_TYPE)
            .accept("application/vnd.gravitino.v1+json")
            .put(
                entity(
                    new DirectoryUserEnabledBatchUpdateRequest(
                        new DirectoryUserEnabledBatchUpdateRequest.DirectoryUserEnabledUpdate[] {
                          new DirectoryUserEnabledBatchUpdateRequest.DirectoryUserEnabledUpdate(
                              " ", IdentitySource.LOCAL)
                        },
                        false),
                    MediaType.APPLICATION_JSON_TYPE));

    Assertions.assertEquals(Response.Status.BAD_REQUEST.getStatusCode(), response.getStatus());
  }

  @Test
  public void testBatchUpdateDirectoryUserEnabledAuthorization() throws NoSuchMethodException {
    Assertions.assertEquals(
        "SERVICE_ADMIN",
        ExtendedUserOperations.class
            .getMethod(
                "batchUpdateDirectoryUserEnabled", DirectoryUserEnabledBatchUpdateRequest.class)
            .getAnnotation(AuthorizationExpression.class)
            .expression());
  }

  @Test
  public void testAddDirectoryUser() {
    when(accessControlDispatcher.addDirectoryUser(
            eq("jordan.m"), eq("ChangeMe-2026!"), eq(List.of("governance", "ops"))))
        .thenReturn(
            new DirectoryUser(
                "jordan.m", true, IdentitySource.LOCAL, List.of("governance", "ops"), List.of()));

    Response response =
        target("/internal/security/directory/users")
            .request(MediaType.APPLICATION_JSON_TYPE)
            .accept("application/vnd.gravitino.v1+json")
            .post(
                entity(
                    new DirectoryUserAddRequest(
                        "jordan.m", "ChangeMe-2026!", List.of("governance", "ops")),
                    MediaType.APPLICATION_JSON_TYPE));

    Assertions.assertEquals(Response.Status.OK.getStatusCode(), response.getStatus());
    DirectoryUserResponse body = response.readEntity(DirectoryUserResponse.class);
    Assertions.assertEquals(0, body.getCode());
    Assertions.assertEquals("jordan.m", body.getUser().name());
    Assertions.assertEquals(IdentitySource.LOCAL, body.getUser().origin());
    Assertions.assertEquals(List.of("governance", "ops"), body.getUser().groups());
  }

  @Test
  public void testAddDirectoryUserConflict() {
    doThrow(new UserAlreadyExistsException("IdP user already exists: jordan.m"))
        .when(accessControlDispatcher)
        .addDirectoryUser(eq("jordan.m"), eq("ChangeMe-2026!"), eq(List.of()));

    Response response =
        target("/internal/security/directory/users")
            .request(MediaType.APPLICATION_JSON_TYPE)
            .accept("application/vnd.gravitino.v1+json")
            .post(
                entity(
                    new DirectoryUserAddRequest("jordan.m", "ChangeMe-2026!", List.of()),
                    MediaType.APPLICATION_JSON_TYPE));

    Assertions.assertEquals(Response.Status.CONFLICT.getStatusCode(), response.getStatus());
    ErrorResponse error = response.readEntity(ErrorResponse.class);
    Assertions.assertEquals(ErrorConstants.ALREADY_EXISTS_CODE, error.getCode());
    Assertions.assertEquals(UserAlreadyExistsException.class.getSimpleName(), error.getType());
  }

  @Test
  public void testDeleteDirectoryUsers() {
    when(accessControlDispatcher.deleteDirectoryUsers(
            eq(List.of("sam.o", "lee.p")), eq(List.of(IdentitySource.LOCAL, IdentitySource.LOCAL))))
        .thenReturn(List.of("sam.o", "lee.p"));

    Response response =
        target("/internal/security/directory/users/delete")
            .request(MediaType.APPLICATION_JSON_TYPE)
            .accept("application/vnd.gravitino.v1+json")
            .post(
                entity(
                    new DirectoryUserDeleteRequest(
                        new DirectoryUserDeleteRequest.DirectoryUserDelete[] {
                          new DirectoryUserDeleteRequest.DirectoryUserDelete(
                              "sam.o", IdentitySource.LOCAL),
                          new DirectoryUserDeleteRequest.DirectoryUserDelete(
                              "lee.p", IdentitySource.LOCAL)
                        }),
                    MediaType.APPLICATION_JSON_TYPE));

    Assertions.assertEquals(Response.Status.OK.getStatusCode(), response.getStatus());
    NameListResponse body = response.readEntity(NameListResponse.class);
    Assertions.assertEquals(0, body.getCode());
    Assertions.assertArrayEquals(new String[] {"sam.o", "lee.p"}, body.getNames());
  }

  @Test
  public void testDeleteDirectoryUsersBadRequest() {
    Response response =
        target("/internal/security/directory/users/delete")
            .request(MediaType.APPLICATION_JSON_TYPE)
            .accept("application/vnd.gravitino.v1+json")
            .post(
                entity(
                    new DirectoryUserDeleteRequest(
                        new DirectoryUserDeleteRequest.DirectoryUserDelete[] {
                          new DirectoryUserDeleteRequest.DirectoryUserDelete(
                              " ", IdentitySource.LOCAL)
                        }),
                    MediaType.APPLICATION_JSON_TYPE));

    Assertions.assertEquals(Response.Status.BAD_REQUEST.getStatusCode(), response.getStatus());
  }

  @Test
  public void testDeleteDirectoryUsersAuthorization() throws NoSuchMethodException {
    Assertions.assertEquals(
        "SERVICE_ADMIN",
        ExtendedUserOperations.class
            .getMethod("deleteDirectoryUsers", DirectoryUserDeleteRequest.class)
            .getAnnotation(AuthorizationExpression.class)
            .expression());
  }

  @Test
  public void testListDirectoryGroupsForUser() {
    when(accessControlDispatcher.listDirectoryGroupsForUser(
            eq("dana.k"), eq(IdentitySource.PROVISIONED)))
        .thenReturn(
            List.of(
                new DirectoryGroup("finance", 3, IdentitySource.PROVISIONED, List.of()),
                new DirectoryGroup("platform", 1, IdentitySource.PROVISIONED, List.of())));

    Response response =
        target("/internal/security/directory/users/dana.k/groups")
            .queryParam("origin", "Provisioned")
            .request(MediaType.APPLICATION_JSON_TYPE)
            .accept("application/vnd.gravitino.v1+json")
            .get();

    Assertions.assertEquals(Response.Status.OK.getStatusCode(), response.getStatus());
    DirectoryGroupListResponse body = response.readEntity(DirectoryGroupListResponse.class);
    Assertions.assertEquals(0, body.getCode());
    DirectoryGroupDTO[] groups = body.getGroups();
    Assertions.assertEquals(2, groups.length);
    Assertions.assertEquals("finance", groups[0].name());
    Assertions.assertEquals(3, groups[0].memberCount());
    Assertions.assertEquals(IdentitySource.PROVISIONED, groups[0].origin());
    Assertions.assertEquals("platform", groups[1].name());
  }

  @Test
  public void testListDirectoryGroupsForUserBadOrigin() {
    Response response =
        target("/internal/security/directory/users/dana.k/groups")
            .queryParam("origin", "Unknown")
            .request(MediaType.APPLICATION_JSON_TYPE)
            .accept("application/vnd.gravitino.v1+json")
            .get();

    Assertions.assertEquals(Response.Status.BAD_REQUEST.getStatusCode(), response.getStatus());
  }

  @Test
  public void testListDirectoryGroupsForUserAuthorization() throws NoSuchMethodException {
    Assertions.assertEquals(
        "",
        ExtendedUserOperations.class
            .getMethod("listDirectoryGroupsForUser", String.class, String.class)
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
