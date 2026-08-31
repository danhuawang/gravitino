/*
 * Copyright 2026 Datastrato Inc.
 */

package com.datastrato.gravitino.scim.web.rest;

import com.codahale.metrics.annotation.ResponseMetered;
import com.codahale.metrics.annotation.Timed;
import com.datastrato.gravitino.scim.ScimTokenManager;
import com.datastrato.gravitino.scim.dto.ScimProvisioningDTO;
import com.datastrato.gravitino.scim.dto.responses.ScimProvisioningListResponse;
import com.datastrato.gravitino.scim.web.ScimOperationType;
import com.datastrato.gravitino.scim.web.ScimRESTUtils;
import java.util.List;
import java.util.stream.Collectors;
import javax.inject.Inject;
import javax.servlet.http.HttpServletRequest;
import javax.ws.rs.Consumes;
import javax.ws.rs.GET;
import javax.ws.rs.Path;
import javax.ws.rs.Produces;
import javax.ws.rs.core.Context;
import javax.ws.rs.core.MediaType;
import javax.ws.rs.core.Response;
import org.apache.gravitino.Metalake;
import org.apache.gravitino.metalake.MetalakeDispatcher;
import org.apache.gravitino.metrics.MetricNames;
import org.apache.gravitino.server.authorization.MetadataAuthzHelper;
import org.apache.gravitino.server.authorization.NameBindings;

/** REST resource for the SCIM Provisioning metalake overview on the main Gravitino server. */
@NameBindings.AccessControlInterfaces
@Path("/scim/provisioning")
@Consumes(MediaType.APPLICATION_JSON)
@Produces(MediaType.APPLICATION_JSON)
public class ScimProvisioningOperations {

  private static final String SCIM_TOKEN_OWNER_EXPRESSION = "METALAKE::OWNER";

  private final MetalakeDispatcher metalakeDispatcher;
  private final ScimTokenManager tokenManager;

  @Context private HttpServletRequest httpRequest;

  /**
   * Creates SCIM provisioning operations.
   *
   * @param metalakeDispatcher metalake dispatcher
   * @param tokenManager SCIM token manager
   */
  @Inject
  public ScimProvisioningOperations(
      MetalakeDispatcher metalakeDispatcher, ScimTokenManager tokenManager) {
    this.metalakeDispatcher = metalakeDispatcher;
    this.tokenManager = tokenManager;
  }

  /**
   * Lists metalakes the caller owns with SCIM endpoint, active token count, and last activity.
   *
   * <p>Visibility matches SCIM token admin APIs: only metalakes where the caller has {@code
   * METALAKE::OWNER} are returned.
   *
   * @return provisioning overview response
   */
  @GET
  @Produces("application/vnd.gravitino.v1+json")
  @Timed(name = "list-scim-provisioning." + MetricNames.HTTP_PROCESS_DURATION, absolute = true)
  @ResponseMetered(name = "list-scim-provisioning", absolute = true)
  public Response listProvisioning() {
    return ScimRESTUtils.doAs(
        httpRequest,
        () -> {
          Metalake[] metalakes = metalakeDispatcher.listMetalakes();
          metalakes = MetadataAuthzHelper.filterMetalakes(metalakes, SCIM_TOKEN_OWNER_EXPRESSION);
          List<ScimProvisioningDTO> rows =
              tokenManager.listProvisioningSummaries(metalakes).stream()
                  .map(ScimProvisioningDTO::from)
                  .collect(Collectors.toList());
          return ScimRESTUtils.ok(new ScimProvisioningListResponse(rows));
        },
        "",
        "",
        ScimOperationType.LIST_PROVISIONING);
  }
}
