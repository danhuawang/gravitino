/*
 * Copyright 2024 Datastrato Pvt Ltd.
 * This software is licensed under the Apache License version 2.
 */
package com.datastrato.gravitino.server.web.rest;

import com.datastrato.gravitino.dto.metrics.MetricDTO;
import com.datastrato.gravitino.dto.responses.MetricsResponse;
import com.datastrato.gravitino.server.web.metric.MetricsCollector;
import com.datastrato.gravitino.storage.relational.service.MetricDataService;
import com.google.common.collect.Maps;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.Arrays;
import java.util.concurrent.atomic.AtomicReference;
import javax.inject.Inject;
import javax.servlet.http.HttpServletRequest;
import javax.ws.rs.DefaultValue;
import javax.ws.rs.GET;
import javax.ws.rs.Path;
import javax.ws.rs.PathParam;
import javax.ws.rs.Produces;
import javax.ws.rs.QueryParam;
import javax.ws.rs.core.Context;
import javax.ws.rs.core.Response;
import org.apache.gravitino.exceptions.NoSuchMetalakeException;
import org.apache.gravitino.exceptions.NoSuchUserException;
import org.apache.gravitino.server.web.Utils;
import org.apache.gravitino.utils.PrincipalUtils;
import org.apache.logging.log4j.util.Strings;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

@Path("/web/metrics/metalakes/{metalake}")
public class MetricOperations {
  private static final Logger LOG = LoggerFactory.getLogger(MetricOperations.class);

  @Context private HttpServletRequest httpRequest;

  // used for querying metrics
  private final MetricDataService metricDataService;

  // used for forcing metrics collection
  private final MetricsCollector metricsCollector;

  @Inject
  public MetricOperations(MetricDataService metricDataService, MetricsCollector metricsCollector) {
    this.metricDataService = metricDataService;
    this.metricsCollector = metricsCollector;
  }

  @GET
  @Produces("application/vnd.gravitino.v1+json")
  public Response getMetrics(
      @PathParam("metalake") String metalakeName,
      @QueryParam("metrics") String metrics,
      @QueryParam("startTimestamp") Long startTimestamp,
      @QueryParam("endTimestamp") Long endTimestamp,
      @QueryParam("refresh") @DefaultValue("false") boolean refresh) {
    LOG.info("Received request to get metrics for metalake: {}", metalakeName);
    String[] metricNames = Strings.isBlank(metrics) ? new String[] {} : metrics.split(",");

    AtomicReference<String> currentUserName = new AtomicReference<>("");
    try {
      return Utils.doAs(
          httpRequest,
          () -> {
            String userName = PrincipalUtils.getCurrentUserName();
            currentUserName.set(userName);
            if (refresh) {
              LOG.info("Refreshing metrics for metalake: {}, user: {}", metalakeName, userName);
              metricsCollector.refreshMetricsForUser(metalakeName, userName);
              LOG.info("Metrics refreshed for metalake: {}, user: {}", metalakeName, userName);
            }

            long start =
                startTimestamp == null
                    ? LocalDate.now()
                        .atStartOfDay(ZoneId.systemDefault())
                        .toInstant()
                        .toEpochMilli()
                    : startTimestamp;
            long end = endTimestamp == null ? System.currentTimeMillis() : endTimestamp;

            MetricDTO[] metricValues =
                metricDataService.getMetricsByNameAndTimestamp(
                    metalakeName, userName, metricNames, start, end);
            return Utils.ok(
                new MetricsResponse(
                    Maps.uniqueIndex(Arrays.asList(metricValues), MetricDTO::name)));
          });
    } catch (NoSuchMetalakeException e) {
      LOG.warn("Metalake not found: {}", metalakeName, e);
      return Utils.notFound("Metalake not found: " + metalakeName, e);
    } catch (NoSuchUserException e) {
      LOG.warn("User not found: {} for metalake: {}", currentUserName.get(), metalakeName, e);
      return Utils.notFound(
          "User not found: " + currentUserName.get() + " for metalake: " + metalakeName, e);
    } catch (Exception e) {
      return Utils.internalError("Failed to get metrics for metalake: " + metalakeName, e);
    }
  }
}
