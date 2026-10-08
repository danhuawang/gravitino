/*
 * Copyright 2026 Datastrato Inc.
 */

package com.datastrato.gravitino.scim.web.rest;

import com.codahale.metrics.annotation.ResponseMetered;
import com.codahale.metrics.annotation.Timed;
import com.datastrato.gravitino.scim.dto.requests.CreateScimTokenRequest;
import com.datastrato.gravitino.scim.dto.requests.RotateScimTokenRequest;
import com.datastrato.gravitino.scim.web.ScimManagement;
import javax.annotation.Nullable;
import javax.inject.Inject;
import javax.servlet.http.HttpServletRequest;
import javax.ws.rs.Consumes;
import javax.ws.rs.DELETE;
import javax.ws.rs.GET;
import javax.ws.rs.POST;
import javax.ws.rs.Path;
import javax.ws.rs.PathParam;
import javax.ws.rs.Produces;
import javax.ws.rs.core.Context;
import javax.ws.rs.core.MediaType;
import javax.ws.rs.core.Response;
import org.apache.gravitino.metrics.MetricNames;

/**
 * Public REST resource for SCIM token administration on the main Gravitino server.
 *
 * <p>Callers must authenticate with the main-server API and be listed in {@code
 * gravitino.authorization.serviceAdmins}. The plaintext token value is returned only from create
 * and rotate responses.
 */
@ScimManagement
@Path("/scim/tokens")
@Consumes(MediaType.APPLICATION_JSON)
@Produces(MediaType.APPLICATION_JSON)
public class PublicScimTokenOperations {

  private final ScimTokenAdminHandler handler;

  @Context private HttpServletRequest httpRequest;

  /**
   * Creates the public SCIM token resource.
   *
   * @param handler the shared SCIM token admin handler
   */
  @Inject
  public PublicScimTokenOperations(ScimTokenAdminHandler handler) {
    this.handler = handler;
  }

  /**
   * Lists active SCIM tokens. Plaintext token values are never returned.
   *
   * @return the token list response
   */
  @GET
  @Produces("application/vnd.gravitino.v1+json")
  @Timed(name = "list-scim-tokens." + MetricNames.HTTP_PROCESS_DURATION, absolute = true)
  @ResponseMetered(name = "list-scim-tokens", absolute = true)
  public Response list() {
    return handler.listTokens(httpRequest);
  }

  /**
   * Creates a SCIM integration token. The plaintext {@code tokenValue} is returned only once.
   *
   * @param request the create request
   * @return the created token response
   */
  @POST
  @Produces("application/vnd.gravitino.v1+json")
  @Timed(name = "create-scim-token." + MetricNames.HTTP_PROCESS_DURATION, absolute = true)
  @ResponseMetered(name = "create-scim-token", absolute = true)
  public Response create(CreateScimTokenRequest request) {
    return handler.createToken(httpRequest, request);
  }

  /**
   * Rotates the bearer secret for an existing named token.
   *
   * @param tokenName the token name
   * @param request optional rotate request; {@code null} keeps the current expiry
   * @return the rotated token response
   */
  @POST
  @Path("{tokenName}/rotate")
  @Produces("application/vnd.gravitino.v1+json")
  @Timed(name = "rotate-scim-token." + MetricNames.HTTP_PROCESS_DURATION, absolute = true)
  @ResponseMetered(name = "rotate-scim-token", absolute = true)
  public Response rotate(
      @PathParam("tokenName") String tokenName, @Nullable RotateScimTokenRequest request) {
    return handler.rotateToken(httpRequest, tokenName, request);
  }

  /**
   * Soft-deletes the named SCIM token.
   *
   * @param tokenName the token name
   * @return the delete response
   */
  @DELETE
  @Path("{tokenName}")
  @Produces("application/vnd.gravitino.v1+json")
  @Timed(name = "delete-scim-token." + MetricNames.HTTP_PROCESS_DURATION, absolute = true)
  @ResponseMetered(name = "delete-scim-token", absolute = true)
  public Response delete(@PathParam("tokenName") String tokenName) {
    return handler.deleteToken(httpRequest, tokenName);
  }
}
