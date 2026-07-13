/*
 * Copyright 2026 Datastrato Pvt Ltd.
 * This software is licensed under the Apache License version 2.
 */

package com.datastrato.gravitino.scim.service.rest;

import com.codahale.metrics.annotation.ResponseMetered;
import com.codahale.metrics.annotation.Timed;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;
import java.util.Collections;
import java.util.List;
import org.apache.gravitino.dto.HealthCheckDTO;
import org.apache.gravitino.dto.responses.HealthResponse;
import org.apache.gravitino.metrics.MetricNames;

/**
 * Health check endpoints for the SCIM auxiliary listener. Follows the same MicroProfile Health
 * semantics as the main Gravitino server.
 *
 * <ul>
 *   <li>{@code GET /scim/health/live} — liveness, 200 as long as the HTTP thread can respond
 *   <li>{@code GET /scim/health/ready} — readiness, 200 when the listener is initialized
 *   <li>{@code GET /scim/health} — aggregate, 200 when both pass
 * </ul>
 */
@Path("/health")
@Produces(MediaType.APPLICATION_JSON)
public class ScimHealthOperations {

  private static final String CHECK_HTTP_SERVER = "httpServer";

  /**
   * Liveness probe. Returns 200 as long as the HTTP thread can respond.
   *
   * @return 200 OK with an UP {@link HealthResponse}
   */
  @GET
  @Path("/live")
  @Timed(name = "scim.health.live." + MetricNames.HTTP_PROCESS_DURATION, absolute = true)
  @ResponseMetered(name = "scim.health.live", absolute = true)
  public Response live() {
    HealthCheckDTO check = up(CHECK_HTTP_SERVER);
    HealthResponse healthResponse =
        new HealthResponse(HealthCheckDTO.Status.UP, Collections.singletonList(check));
    return Response.ok(healthResponse).build();
  }

  /**
   * Readiness probe. Returns 200 when the SCIM listener is ready to accept requests.
   *
   * @return 200 OK when ready
   */
  @GET
  @Path("/ready")
  @Timed(name = "scim.health.ready." + MetricNames.HTTP_PROCESS_DURATION, absolute = true)
  @ResponseMetered(name = "scim.health.ready", absolute = true)
  public Response ready() {
    HealthCheckDTO check = up(CHECK_HTTP_SERVER);
    HealthResponse healthResponse =
        new HealthResponse(HealthCheckDTO.Status.UP, Collections.singletonList(check));
    return Response.ok(healthResponse).build();
  }

  /**
   * Aggregate health check. Returns 200 when liveness and readiness pass.
   *
   * @return 200 OK when healthy
   */
  @GET
  @Timed(name = "scim.health." + MetricNames.HTTP_PROCESS_DURATION, absolute = true)
  @ResponseMetered(name = "scim.health", absolute = true)
  public Response health() {
    List<HealthCheckDTO> checks = Collections.singletonList(up(CHECK_HTTP_SERVER));
    HealthResponse body = new HealthResponse(HealthCheckDTO.Status.UP, checks);
    return Response.ok(body).build();
  }

  private static HealthCheckDTO up(String name) {
    return new HealthCheckDTO(name, HealthCheckDTO.Status.UP, Collections.emptyMap());
  }
}
