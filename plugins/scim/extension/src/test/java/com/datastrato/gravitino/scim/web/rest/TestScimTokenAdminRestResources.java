/*
 * Copyright 2026 Datastrato Inc.
 */

package com.datastrato.gravitino.scim.web.rest;

import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.reset;
import static org.mockito.Mockito.when;

import com.datastrato.gravitino.scim.ScimFieldLimits;
import com.datastrato.gravitino.scim.ScimTokenManager;
import com.datastrato.gravitino.scim.dto.requests.CreateScimTokenRequest;
import com.datastrato.gravitino.scim.dto.requests.RotateScimTokenRequest;
import com.datastrato.gravitino.scim.dto.responses.ScimTokenDeleteResponse;
import com.datastrato.gravitino.scim.dto.responses.ScimTokenListResponse;
import com.datastrato.gravitino.scim.dto.responses.ScimTokenResponse;
import com.datastrato.gravitino.scim.model.CreatedScimToken;
import com.datastrato.gravitino.scim.model.ScimTokenSummary;
import java.io.IOException;
import java.util.List;
import java.util.stream.Stream;
import javax.servlet.http.HttpServletRequest;
import javax.ws.rs.client.Entity;
import javax.ws.rs.core.Application;
import javax.ws.rs.core.MediaType;
import javax.ws.rs.core.Response;
import org.apache.gravitino.dto.responses.ErrorResponse;
import org.apache.gravitino.exceptions.AlreadyExistsException;
import org.apache.gravitino.rest.RESTUtils;
import org.glassfish.hk2.utilities.binding.AbstractBinder;
import org.glassfish.jersey.server.ResourceConfig;
import org.glassfish.jersey.test.JerseyTest;
import org.glassfish.jersey.test.TestProperties;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;

/** Exercises the public and internal SCIM token REST resources against the shared admin handler. */
class TestScimTokenAdminRestResources extends JerseyTest {

  private static final String ACCEPT = "application/vnd.gravitino.v1+json";
  private static final ScimTokenManager TOKEN_MANAGER = mock(ScimTokenManager.class);

  static Stream<String> tokenBasePaths() {
    return Stream.of("/scim/tokens", "/internal/scim/tokens");
  }

  @BeforeEach
  void resetMocks() {
    reset(TOKEN_MANAGER);
  }

  @Override
  protected Application configure() {
    try {
      forceSet(
          TestProperties.CONTAINER_PORT, String.valueOf(RESTUtils.findAvailablePort(2000, 4000)));
    } catch (IOException e) {
      throw new RuntimeException(e);
    }

    HttpServletRequest request = mock(HttpServletRequest.class);
    when(request.getRemoteUser()).thenReturn(null);

    ResourceConfig resourceConfig = new ResourceConfig();
    resourceConfig.register(PublicScimTokenOperations.class);
    resourceConfig.register(ScimTokenOperations.class);
    resourceConfig.register(
        new AbstractBinder() {
          @Override
          protected void configure() {
            bind(TOKEN_MANAGER).to(ScimTokenManager.class);
            bind(new ScimTokenAdminHandler(TOKEN_MANAGER)).to(ScimTokenAdminHandler.class);
            bind(request).to(HttpServletRequest.class);
          }
        });
    return resourceConfig;
  }

  @ParameterizedTest
  @MethodSource("tokenBasePaths")
  void testList(String tokensPath) {
    doReturn(
            List.of(
                summary("prod", 1000L, "valid", 500L, 900L),
                summary("staging", 0L, "valid", 600L, 0L)))
        .when(TOKEN_MANAGER)
        .listScimTokens();

    ScimTokenListResponse body = get(tokensPath).readEntity(ScimTokenListResponse.class);
    Assertions.assertEquals(2, body.getTokens().size());
    Assertions.assertEquals("prod", body.getTokens().get(0).getTokenName());
    Assertions.assertEquals(900L, body.getTokens().get(0).getLastUsedAt());
  }

  @ParameterizedTest
  @MethodSource("tokenBasePaths")
  void testCreate(String tokensPath) throws Exception {
    CreateScimTokenRequest request = new CreateScimTokenRequest("prod", 30);
    doReturn(created("prod", "gravitino_scim_secret", 1000L))
        .when(TOKEN_MANAGER)
        .createScimToken(eq("prod"), eq(30));

    Assertions.assertEquals(
        "prod",
        post(tokensPath, request).readEntity(ScimTokenResponse.class).getToken().getTokenName());

    doThrow(new AlreadyExistsException("exists"))
        .when(TOKEN_MANAGER)
        .createScimToken(eq("prod"), eq(30));
    assertStatus(Response.Status.CONFLICT, post(tokensPath, request));
  }

  @ParameterizedTest
  @MethodSource("tokenBasePaths")
  void testRotate(String tokensPath) throws Exception {
    doReturn(created("prod", "gravitino_scim_new", 2000L))
        .when(TOKEN_MANAGER)
        .rotateScimToken(eq("prod"), eq(7));

    Assertions.assertEquals(
        "gravitino_scim_new",
        post(tokensPath + "/prod/rotate", new RotateScimTokenRequest(7))
            .readEntity(ScimTokenResponse.class)
            .getToken()
            .getTokenValue());
  }

  @ParameterizedTest
  @MethodSource("tokenBasePaths")
  void testDelete(String tokensPath) {
    doReturn(true).when(TOKEN_MANAGER).deleteScimToken("prod");
    Assertions.assertTrue(
        delete(tokensPath + "/prod").readEntity(ScimTokenDeleteResponse.class).getDeleted());

    doReturn(false).when(TOKEN_MANAGER).deleteScimToken("missing");
    assertStatus(Response.Status.NOT_FOUND, delete(tokensPath + "/missing"));
  }

  @ParameterizedTest
  @MethodSource("tokenBasePaths")
  void testCreateBadRequest(String tokensPath) {
    assertStatus(Response.Status.BAD_REQUEST, post(tokensPath, new CreateScimTokenRequest("", 30)));
  }

  @ParameterizedTest
  @MethodSource("tokenBasePaths")
  void testCreateTooLongTokenName(String tokensPath) {
    String tokenName = "t".repeat(ScimFieldLimits.MAX_TOKEN_NAME_LENGTH + 1);
    assertStatus(
        Response.Status.BAD_REQUEST, post(tokensPath, new CreateScimTokenRequest(tokenName, 30)));
  }

  private static ScimTokenSummary summary(
      String name, long expires, String status, long created, long used) {
    return ScimTokenSummary.builder()
        .withTokenName(name)
        .withExpiresAt(expires)
        .withStatus(status)
        .withCreatedAt(created)
        .withLastUsedAt(used)
        .build();
  }

  private static CreatedScimToken created(String name, String value, long expiresAt) {
    return CreatedScimToken.builder()
        .withTokenName(name)
        .withTokenValue(value)
        .withExpiresAt(expiresAt)
        .build();
  }

  private Response post(String path, Object body) {
    return target(path)
        .request()
        .accept(ACCEPT)
        .post(Entity.entity(body, MediaType.APPLICATION_JSON_TYPE));
  }

  private Response delete(String path) {
    return target(path).request().accept(ACCEPT).delete();
  }

  private Response get(String path) {
    return target(path).request().accept(ACCEPT).get();
  }

  private void assertStatus(Response.Status expected, Response response) {
    Assertions.assertEquals(expected.getStatusCode(), response.getStatus());
    response.readEntity(ErrorResponse.class);
  }
}
