/*
 * Copyright 2026 Datastrato Pvt Ltd.
 * This software is licensed under the Apache License version 2.
 */

package com.datastrato.gravitino.scim.web.rest;

import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.reset;
import static org.mockito.Mockito.when;

import com.datastrato.gravitino.scim.ScimTokenManager;
import com.datastrato.gravitino.scim.dto.requests.CreateScimTokenRequest;
import com.datastrato.gravitino.scim.dto.requests.RotateScimTokenRequest;
import com.datastrato.gravitino.scim.dto.responses.ScimTokenDeleteResponse;
import com.datastrato.gravitino.scim.dto.responses.ScimTokenResponse;
import com.datastrato.gravitino.scim.model.CreatedScimToken;
import java.io.IOException;
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
import org.junit.jupiter.api.Test;

class TestScimTokenOperations extends JerseyTest {

  private static final String ACCEPT = "application/vnd.gravitino.v1+json";
  private static final String METALAKE = "my_metalake";
  private static final ScimTokenManager TOKEN_MANAGER = mock(ScimTokenManager.class);

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
    resourceConfig.register(ScimTokenOperations.class);
    resourceConfig.register(
        new AbstractBinder() {
          @Override
          protected void configure() {
            bind(TOKEN_MANAGER).to(ScimTokenManager.class);
            bind(request).to(HttpServletRequest.class);
          }
        });
    return resourceConfig;
  }

  @Test
  void testCreateToken() throws Exception {
    CreateScimTokenRequest request = new CreateScimTokenRequest("prod", 30);
    doReturn(createdToken("prod", "gravitino_scim_secret", 1000L))
        .when(TOKEN_MANAGER)
        .createScimToken(eq(METALAKE), eq("prod"), eq(30));

    Response httpResponse = post("/metalakes/" + METALAKE + "/scim/tokens", request);
    Assertions.assertEquals(Response.Status.OK.getStatusCode(), httpResponse.getStatus());
    ScimTokenResponse response = httpResponse.readEntity(ScimTokenResponse.class);
    Assertions.assertEquals("prod", response.getToken().getTokenName());

    doThrow(new AlreadyExistsException("exists"))
        .when(TOKEN_MANAGER)
        .createScimToken(eq(METALAKE), eq("prod"), eq(30));
    assertStatus(
        Response.Status.CONFLICT, post("/metalakes/" + METALAKE + "/scim/tokens", request));
  }

  @Test
  void testRotateToken() throws Exception {
    RotateScimTokenRequest request = new RotateScimTokenRequest(7);
    doReturn(createdToken("prod", "gravitino_scim_new", 2000L))
        .when(TOKEN_MANAGER)
        .rotateScimToken(eq(METALAKE), eq("prod"), eq(7));

    Response httpResponse = post("/metalakes/" + METALAKE + "/scim/tokens/prod/rotate", request);
    Assertions.assertEquals(Response.Status.OK.getStatusCode(), httpResponse.getStatus());
    ScimTokenResponse response = httpResponse.readEntity(ScimTokenResponse.class);
    Assertions.assertEquals("gravitino_scim_new", response.getToken().getTokenValue());
  }

  @Test
  void testDeleteToken() {
    doReturn(true).when(TOKEN_MANAGER).deleteScimToken(METALAKE, "prod");

    ScimTokenDeleteResponse response =
        delete("/metalakes/" + METALAKE + "/scim/tokens/prod")
            .readEntity(ScimTokenDeleteResponse.class);
    Assertions.assertTrue(response.getDeleted());

    doReturn(false).when(TOKEN_MANAGER).deleteScimToken(METALAKE, "missing");
    assertStatus(
        Response.Status.NOT_FOUND, delete("/metalakes/" + METALAKE + "/scim/tokens/missing"));
  }

  @Test
  void testCreateTokenValidation() {
    assertStatus(
        Response.Status.BAD_REQUEST,
        post("/metalakes/" + METALAKE + "/scim/tokens", new CreateScimTokenRequest("", 30)));
  }

  private CreatedScimToken createdToken(String name, String value, long expiresAt) {
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

  private void assertStatus(Response.Status expected, Response response) {
    Assertions.assertEquals(expected.getStatusCode(), response.getStatus());
    response.readEntity(ErrorResponse.class);
  }
}
