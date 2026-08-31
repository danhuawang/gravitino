/*
 * Copyright 2026 Datastrato Inc.
 */
package com.datastrato.gravitino.server.web.rest;

import com.codahale.metrics.annotation.ResponseMetered;
import com.codahale.metrics.annotation.Timed;
import com.datastrato.gravitino.dto.responses.MetalakeSummaryResponse;
import javax.annotation.Nullable;
import javax.servlet.http.HttpServletRequest;
import javax.ws.rs.GET;
import javax.ws.rs.Path;
import javax.ws.rs.PathParam;
import javax.ws.rs.Produces;
import javax.ws.rs.core.Context;
import javax.ws.rs.core.Response;
import org.apache.gravitino.Entity;
import org.apache.gravitino.GravitinoEnv;
import org.apache.gravitino.Namespace;
import org.apache.gravitino.authorization.AccessControlDispatcher;
import org.apache.gravitino.metrics.MetricNames;
import org.apache.gravitino.server.authorization.annotations.AuthorizationExpression;
import org.apache.gravitino.server.authorization.annotations.AuthorizationMetadata;
import org.apache.gravitino.server.web.Utils;
import org.apache.gravitino.server.web.rest.ExceptionHandlers;
import org.apache.gravitino.server.web.rest.OperationType;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * REST operations for retrieving aggregate information about a metalake.
 *
 * <p>The endpoint requires metalake ownership and reports complete counts without per-object
 * visibility filtering. Filtering would be a no-op: both {@code
 * LOAD_CATALOG_AUTHORIZATION_EXPRESSION} and {@code LOAD_ROLE_AUTHORIZATION_EXPRESSION} accept
 * {@code METALAKE::OWNER} for every object, which every caller of this endpoint has.
 */
@Path("/web/metalakes/{metalake}/summary")
public class MetalakeSummaryOperations {

  private static final Logger LOG = LoggerFactory.getLogger(MetalakeSummaryOperations.class);

  @Context private HttpServletRequest httpRequest;

  /**
   * Returns aggregate information about a metalake.
   *
   * <p>Every count comes from Gravitino directly. A listing that fails propagates as an error
   * instead of being reported as zero so that clients do not receive a misleading summary.
   *
   * @param metalake The metalake name.
   * @return The metalake summary response.
   */
  @GET
  @Produces("application/vnd.gravitino.v1+json")
  @Timed(name = "get-metalake-summary." + MetricNames.HTTP_PROCESS_DURATION, absolute = true)
  @ResponseMetered(name = "get-metalake-summary", absolute = true)
  @AuthorizationExpression(expression = "METALAKE::OWNER")
  public Response getSummary(
      @PathParam("metalake") @AuthorizationMetadata(type = Entity.EntityType.METALAKE)
          String metalake) {
    LOG.info("Received summary request for metalake: {}", metalake);
    try {
      return Utils.doAs(
          httpRequest,
          () -> {
            AccessControlDispatcher accessControlDispatcher =
                GravitinoEnv.getInstance().accessControlDispatcher();
            MetalakeSummaryResponse response =
                new MetalakeSummaryResponse(
                    countCatalogs(metalake),
                    countUsers(accessControlDispatcher, metalake),
                    countRoles(accessControlDispatcher, metalake));
            LOG.info("Loaded summary for metalake: {}, {}", metalake, response);
            return Utils.ok(response);
          });
    } catch (Exception e) {
      return ExceptionHandlers.handleMetalakeException(OperationType.LOAD, metalake, e);
    }
  }

  /** Returns the number of catalogs in the metalake. */
  private long countCatalogs(String metalake) {
    return GravitinoEnv.getInstance()
        .catalogDispatcher()
        .listCatalogs(Namespace.of(metalake))
        .length;
  }

  /** Returns the number of users, or null when access control is disabled. */
  @Nullable
  private Long countUsers(
      @Nullable AccessControlDispatcher accessControlDispatcher, String metalake) {
    if (accessControlDispatcher == null) {
      return null;
    }

    return accessControlDispatcher.countUsers(metalake);
  }

  /** Returns the number of roles, or null when access control is disabled. */
  @Nullable
  private Long countRoles(
      @Nullable AccessControlDispatcher accessControlDispatcher, String metalake) {
    if (accessControlDispatcher == null) {
      return null;
    }

    return (long) accessControlDispatcher.listRoleNames(metalake).length;
  }
}
