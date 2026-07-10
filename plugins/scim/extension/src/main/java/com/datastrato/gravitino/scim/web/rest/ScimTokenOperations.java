/*
 * Copyright 2026 Datastrato Pvt Ltd.
 * This software is licensed under the Apache License version 2.
 */

package com.datastrato.gravitino.scim.web.rest;

import com.codahale.metrics.annotation.ResponseMetered;
import com.codahale.metrics.annotation.Timed;
import com.datastrato.gravitino.scim.ScimTokenManager;
import com.datastrato.gravitino.scim.dto.ScimTokenDTO;
import com.datastrato.gravitino.scim.dto.requests.CreateScimTokenRequest;
import com.datastrato.gravitino.scim.dto.requests.RotateScimTokenRequest;
import com.datastrato.gravitino.scim.dto.responses.ScimTokenDeleteResponse;
import com.datastrato.gravitino.scim.dto.responses.ScimTokenResponse;
import com.datastrato.gravitino.scim.model.CreatedScimToken;
import com.datastrato.gravitino.scim.web.ScimOperationType;
import com.datastrato.gravitino.scim.web.ScimRESTUtils;
import javax.annotation.Nullable;
import javax.inject.Inject;
import javax.servlet.http.HttpServletRequest;
import javax.ws.rs.Consumes;
import javax.ws.rs.DELETE;
import javax.ws.rs.POST;
import javax.ws.rs.Path;
import javax.ws.rs.PathParam;
import javax.ws.rs.Produces;
import javax.ws.rs.core.Context;
import javax.ws.rs.core.MediaType;
import javax.ws.rs.core.Response;
import org.apache.gravitino.Entity;
import org.apache.gravitino.exceptions.NotFoundException;
import org.apache.gravitino.metrics.MetricNames;
import org.apache.gravitino.server.authorization.NameBindings;
import org.apache.gravitino.server.authorization.annotations.AuthorizationExpression;
import org.apache.gravitino.server.authorization.annotations.AuthorizationMetadata;

/** REST resource for SCIM token administration on the main Gravitino server. */
@NameBindings.AccessControlInterfaces
@Path("/metalakes/{metalake}/scim/tokens")
@Consumes(MediaType.APPLICATION_JSON)
@Produces(MediaType.APPLICATION_JSON)
public class ScimTokenOperations {

  private final ScimTokenManager tokenManager;

  @Context private HttpServletRequest httpRequest;

  /**
   * Creates SCIM token operations.
   *
   * @param tokenManager SCIM token manager
   */
  @Inject
  public ScimTokenOperations(ScimTokenManager tokenManager) {
    this.tokenManager = tokenManager;
  }

  /**
   * Creates a new SCIM token for the given metalake.
   *
   * @param metalake target metalake name
   * @param request create request body
   * @return created token response
   */
  @POST
  @Produces("application/vnd.gravitino.v1+json")
  @Timed(name = "create-scim-token." + MetricNames.HTTP_PROCESS_DURATION, absolute = true)
  @ResponseMetered(name = "create-scim-token", absolute = true)
  @AuthorizationExpression(expression = "METALAKE::OWNER")
  public Response createToken(
      @PathParam("metalake") @AuthorizationMetadata(type = Entity.EntityType.METALAKE)
          String metalake,
      CreateScimTokenRequest request) {
    return ScimRESTUtils.doAs(
        httpRequest,
        () -> {
          request.validate();
          CreatedScimToken created =
              tokenManager.createScimToken(
                  metalake, request.getTokenName(), request.getExpiresInDays());
          return ScimRESTUtils.ok(new ScimTokenResponse(toTokenDto(metalake, created)));
        },
        metalake,
        request.getTokenName(),
        ScimOperationType.CREATE);
  }

  /**
   * Rotates the bearer secret for an existing named token.
   *
   * @param metalake target metalake name
   * @param tokenName existing token name
   * @param request optional rotate request body
   * @return rotated token response
   */
  @POST
  @Path("{tokenName}/rotate")
  @Produces("application/vnd.gravitino.v1+json")
  @Timed(name = "rotate-scim-token." + MetricNames.HTTP_PROCESS_DURATION, absolute = true)
  @ResponseMetered(name = "rotate-scim-token", absolute = true)
  @AuthorizationExpression(expression = "METALAKE::OWNER")
  public Response rotateToken(
      @PathParam("metalake") @AuthorizationMetadata(type = Entity.EntityType.METALAKE)
          String metalake,
      @PathParam("tokenName") String tokenName,
      @Nullable RotateScimTokenRequest request) {
    return ScimRESTUtils.doAs(
        httpRequest,
        () -> {
          if (request != null) {
            request.validate();
          }
          Integer expiresInDays = request == null ? null : request.getExpiresInDays();
          CreatedScimToken rotated =
              tokenManager.rotateScimToken(metalake, tokenName, expiresInDays);
          return ScimRESTUtils.ok(new ScimTokenResponse(toTokenDto(metalake, rotated)));
        },
        metalake,
        tokenName,
        ScimOperationType.ROTATE);
  }

  /**
   * Soft-deletes the named SCIM token for the given metalake.
   *
   * @param metalake target metalake name
   * @param tokenName token name to revoke
   * @return delete response
   */
  @DELETE
  @Path("{tokenName}")
  @Produces("application/vnd.gravitino.v1+json")
  @Timed(name = "delete-scim-token." + MetricNames.HTTP_PROCESS_DURATION, absolute = true)
  @ResponseMetered(name = "delete-scim-token", absolute = true)
  @AuthorizationExpression(expression = "METALAKE::OWNER")
  public Response deleteToken(
      @PathParam("metalake") @AuthorizationMetadata(type = Entity.EntityType.METALAKE)
          String metalake,
      @PathParam("tokenName") String tokenName) {
    return ScimRESTUtils.doAs(
        httpRequest,
        () -> {
          boolean deleted = tokenManager.deleteScimToken(metalake, tokenName);
          if (!deleted) {
            throw new NotFoundException("SCIM token not found: %s", tokenName);
          }
          return ScimRESTUtils.ok(new ScimTokenDeleteResponse(true));
        },
        metalake,
        tokenName,
        ScimOperationType.DELETE);
  }

  private ScimTokenDTO toTokenDto(String metalake, CreatedScimToken created) {
    return ScimTokenDTO.of(
        metalake, created.getTokenName(), created.getTokenValue(), created.getExpiresAt());
  }
}
