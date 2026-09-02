/*
 * Copyright 2026 Datastrato Inc.
 */

package com.datastrato.gravitino.scim.web.rest;

import com.codahale.metrics.annotation.ResponseMetered;
import com.codahale.metrics.annotation.Timed;
import com.datastrato.gravitino.scim.ScimTokenManager;
import com.datastrato.gravitino.scim.dto.ScimTokenOverviewDTO;
import com.datastrato.gravitino.scim.dto.responses.ScimTokenOverviewResponse;
import com.datastrato.gravitino.scim.model.ScimTokenOverview;
import com.datastrato.gravitino.scim.web.ScimManagement;
import com.datastrato.gravitino.scim.web.ScimOperationType;
import com.datastrato.gravitino.scim.web.ScimRESTUtils;
import javax.inject.Inject;
import javax.servlet.http.HttpServletRequest;
import javax.ws.rs.Consumes;
import javax.ws.rs.GET;
import javax.ws.rs.Path;
import javax.ws.rs.Produces;
import javax.ws.rs.core.Context;
import javax.ws.rs.core.MediaType;
import javax.ws.rs.core.Response;
import org.apache.gravitino.metrics.MetricNames;

/** REST resource for the SCIM token overview on the main Gravitino server. */
@ScimManagement
@Path("/scim/tokens")
@Consumes(MediaType.APPLICATION_JSON)
@Produces(MediaType.APPLICATION_JSON)
public class ScimTokenOverviewOperations {

  private final ScimTokenManager tokenManager;

  @Context private HttpServletRequest httpRequest;

  /**
   * Creates SCIM token overview operations.
   *
   * @param tokenManager SCIM token manager
   */
  @Inject
  public ScimTokenOverviewOperations(ScimTokenManager tokenManager) {
    this.tokenManager = tokenManager;
  }

  /**
   * Returns the SCIM token overview for the Identity Provider admin UI.
   *
   * @return token overview response
   */
  @GET
  @Path("overview")
  @Produces("application/vnd.gravitino.v1+json")
  @Timed(name = "get-scim-token-overview." + MetricNames.HTTP_PROCESS_DURATION, absolute = true)
  @ResponseMetered(name = "get-scim-token-overview", absolute = true)
  public Response getTokenOverview() {
    return ScimRESTUtils.doAs(
        httpRequest,
        () -> {
          ScimTokenOverview overview = tokenManager.getScimTokenOverview();
          ScimTokenOverviewDTO dto = ScimTokenOverviewDTO.from(overview);
          return ScimRESTUtils.ok(
              new ScimTokenOverviewResponse(
                  dto.getLastUsedAt(), dto.getTokenCount(), dto.getTokens()));
        },
        "",
        ScimOperationType.LIST_OVERVIEW);
  }
}
