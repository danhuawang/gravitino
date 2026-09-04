/*
 * Copyright 2026 Datastrato Inc.
 */
package com.datastrato.gravitino.server.web.rest;

import com.codahale.metrics.annotation.ResponseMetered;
import com.codahale.metrics.annotation.Timed;
import com.datastrato.gravitino.dto.responses.MetalakeSummaryResponse;
import com.datastrato.gravitino.metalake.MetalakeSummaryCounts;
import com.datastrato.gravitino.metalake.MetalakeSummaryMetaService;
import com.google.common.base.Preconditions;
import javax.servlet.http.HttpServletRequest;
import javax.ws.rs.GET;
import javax.ws.rs.Path;
import javax.ws.rs.PathParam;
import javax.ws.rs.Produces;
import javax.ws.rs.core.Context;
import javax.ws.rs.core.Response;
import org.apache.gravitino.Entity;
import org.apache.gravitino.GravitinoEnv;
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
 * <p>The endpoint requires metalake ownership and reports an exact catalog count plus exact user
 * and role counts when authorization is enabled, without per-object visibility filtering. Filtering
 * would be a no-op: both {@code LOAD_CATALOG_AUTHORIZATION_EXPRESSION} and {@code
 * LOAD_ROLE_AUTHORIZATION_EXPRESSION} accept {@code METALAKE::OWNER} for every object, which every
 * caller of this endpoint has.
 */
@Path("/web/metalakes/{metalake}/summary")
public class MetalakeSummaryOperations {

  private static final Logger LOG = LoggerFactory.getLogger(MetalakeSummaryOperations.class);

  private final MetalakeSummaryMetaService summaryMetaService;

  @Context private HttpServletRequest httpRequest;

  /** Creates metalake summary REST operations. */
  public MetalakeSummaryOperations() {
    this(MetalakeSummaryMetaService.getInstance());
  }

  MetalakeSummaryOperations(MetalakeSummaryMetaService summaryMetaService) {
    this.summaryMetaService = Preconditions.checkNotNull(summaryMetaService);
  }

  /**
   * Returns aggregate information about a metalake.
   *
   * <p>All counts come from one relational query so they describe the same persisted snapshot. A
   * query failure propagates as an error instead of being reported as zero. Reading persistence
   * directly also keeps the summary available while the metalake is disabled, when normal
   * child-entity APIs intentionally reject operations.
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
            MetalakeSummaryCounts counts = summaryMetaService.loadCounts(metalake);
            MetalakeSummaryResponse response =
                new MetalakeSummaryResponse(
                    counts.catalogCount(),
                    accessControlDispatcher == null ? null : counts.userCount(),
                    accessControlDispatcher == null ? null : counts.roleCount());
            LOG.info("Loaded summary for metalake: {}, {}", metalake, response);
            return Utils.ok(response);
          });
    } catch (Exception e) {
      return ExceptionHandlers.handleMetalakeException(OperationType.LOAD, metalake, e);
    }
  }
}
