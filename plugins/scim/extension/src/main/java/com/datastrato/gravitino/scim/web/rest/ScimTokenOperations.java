/*
 * Copyright 2026 Datastrato Inc.
 */

package com.datastrato.gravitino.scim.web.rest;

import com.codahale.metrics.annotation.ResponseMetered;
import com.codahale.metrics.annotation.Timed;
import com.datastrato.gravitino.scim.ScimTokenManager;
import com.datastrato.gravitino.scim.dto.ScimTokenDTO;
import com.datastrato.gravitino.scim.dto.ScimTokenSummaryDTO;
import com.datastrato.gravitino.scim.dto.requests.CreateScimTokenRequest;
import com.datastrato.gravitino.scim.dto.requests.RotateScimTokenRequest;
import com.datastrato.gravitino.scim.dto.responses.ScimTokenDeleteResponse;
import com.datastrato.gravitino.scim.dto.responses.ScimTokenListResponse;
import com.datastrato.gravitino.scim.dto.responses.ScimTokenResponse;
import com.datastrato.gravitino.scim.model.CreatedScimToken;
import com.datastrato.gravitino.scim.web.ScimManagement;
import com.datastrato.gravitino.scim.web.ScimOperationType;
import com.datastrato.gravitino.scim.web.ScimRESTUtils;
import java.util.List;
import java.util.stream.Collectors;
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
import org.apache.gravitino.exceptions.NotFoundException;
import org.apache.gravitino.metrics.MetricNames;

/** REST resource for SCIM token administration on the main Gravitino server. */
@ScimManagement
@Path("/scim/tokens")
@Consumes(MediaType.APPLICATION_JSON)
@Produces(MediaType.APPLICATION_JSON)
public class ScimTokenOperations {
  private final ScimTokenManager tokenManager;
  @Context private HttpServletRequest httpRequest;

  @Inject
  public ScimTokenOperations(ScimTokenManager tokenManager) {
    this.tokenManager = tokenManager;
  }

  @GET
  @Produces("application/vnd.gravitino.v1+json")
  @Timed(name = "list-scim-tokens." + MetricNames.HTTP_PROCESS_DURATION, absolute = true)
  @ResponseMetered(name = "list-scim-tokens", absolute = true)
  public Response listTokens() {
    return ScimRESTUtils.doAs(
        httpRequest,
        () -> {
          List<ScimTokenSummaryDTO> tokens =
              tokenManager.listScimTokens().stream()
                  .map(ScimTokenSummaryDTO::from)
                  .collect(Collectors.toList());
          return ScimRESTUtils.ok(new ScimTokenListResponse(tokens));
        },
        "",
        ScimOperationType.LIST);
  }

  @POST
  @Produces("application/vnd.gravitino.v1+json")
  @Timed(name = "create-scim-token." + MetricNames.HTTP_PROCESS_DURATION, absolute = true)
  @ResponseMetered(name = "create-scim-token", absolute = true)
  public Response createToken(CreateScimTokenRequest request) {
    return ScimRESTUtils.doAs(
        httpRequest,
        () -> {
          request.validate();
          CreatedScimToken created =
              tokenManager.createScimToken(request.getTokenName(), request.getExpiresInDays());
          return ScimRESTUtils.ok(new ScimTokenResponse(toTokenDto(created)));
        },
        request.getTokenName(),
        ScimOperationType.CREATE);
  }

  @POST
  @Path("{tokenName}/rotate")
  @Produces("application/vnd.gravitino.v1+json")
  public Response rotateToken(
      @PathParam("tokenName") String tokenName, @Nullable RotateScimTokenRequest request) {
    return ScimRESTUtils.doAs(
        httpRequest,
        () -> {
          if (request != null) request.validate();
          Integer expiresInDays = request == null ? null : request.getExpiresInDays();
          CreatedScimToken rotated = tokenManager.rotateScimToken(tokenName, expiresInDays);
          return ScimRESTUtils.ok(new ScimTokenResponse(toTokenDto(rotated)));
        },
        tokenName,
        ScimOperationType.ROTATE);
  }

  @DELETE
  @Path("{tokenName}")
  @Produces("application/vnd.gravitino.v1+json")
  public Response deleteToken(@PathParam("tokenName") String tokenName) {
    return ScimRESTUtils.doAs(
        httpRequest,
        () -> {
          if (!tokenManager.deleteScimToken(tokenName)) {
            throw new NotFoundException("SCIM token not found: %s", tokenName);
          }
          return ScimRESTUtils.ok(new ScimTokenDeleteResponse(true));
        },
        tokenName,
        ScimOperationType.DELETE);
  }

  private ScimTokenDTO toTokenDto(CreatedScimToken created) {
    return ScimTokenDTO.of(created.getTokenName(), created.getTokenValue(), created.getExpiresAt());
  }
}
