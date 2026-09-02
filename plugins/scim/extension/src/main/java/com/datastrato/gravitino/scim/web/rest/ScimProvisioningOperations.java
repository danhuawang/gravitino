/*
 * Copyright 2026 Datastrato Inc.
 */

package com.datastrato.gravitino.scim.web.rest;

import com.codahale.metrics.annotation.ResponseMetered;
import com.codahale.metrics.annotation.Timed;
import com.datastrato.gravitino.scim.ScimTokenManager;
import com.datastrato.gravitino.scim.dto.ScimProvisioningDTO;
import com.datastrato.gravitino.scim.dto.responses.ScimProvisioningListResponse;
import com.datastrato.gravitino.scim.web.ScimManagement;
import com.datastrato.gravitino.scim.web.ScimOperationType;
import com.datastrato.gravitino.scim.web.ScimRESTUtils;
import java.util.List;
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

/** REST resource for the SCIM provisioning overview. */
@ScimManagement
@Path("/scim/provisioning")
@Consumes(MediaType.APPLICATION_JSON)
@Produces(MediaType.APPLICATION_JSON)
public class ScimProvisioningOperations {
  private final ScimTokenManager tokenManager;
  @Context private HttpServletRequest httpRequest;

  @Inject
  public ScimProvisioningOperations(ScimTokenManager tokenManager) {
    this.tokenManager = tokenManager;
  }

  @GET
  @Produces("application/vnd.gravitino.v1+json")
  @Timed(name = "list-scim-provisioning." + MetricNames.HTTP_PROCESS_DURATION, absolute = true)
  @ResponseMetered(name = "list-scim-provisioning", absolute = true)
  public Response listProvisioning() {
    return ScimRESTUtils.doAs(
        httpRequest,
        () -> {
          List<ScimProvisioningDTO> rows =
              List.of(ScimProvisioningDTO.from(tokenManager.getProvisioningSummary()));
          return ScimRESTUtils.ok(new ScimProvisioningListResponse(rows));
        },
        "",
        ScimOperationType.LIST_PROVISIONING);
  }
}
