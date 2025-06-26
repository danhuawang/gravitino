/*
 * Copyright 2024 Datastrato Pvt Ltd.
 * This software is licensed under the Apache License version 2.
 */
package com.datastrato.gravitino.server.web.rest;

import static org.apache.gravitino.Configs.TREE_LOCK_CLEAN_INTERVAL;
import static org.apache.gravitino.Configs.TREE_LOCK_MAX_NODE_IN_MEMORY;
import static org.apache.gravitino.Configs.TREE_LOCK_MIN_NODE_IN_MEMORY;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doNothing;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.datastrato.gravitino.dto.metrics.MetricDTO;
import com.datastrato.gravitino.dto.responses.MetricsResponse;
import com.datastrato.gravitino.metrics.MetricDataService;
import com.datastrato.gravitino.server.web.metric.MetricsCollector;
import com.google.common.collect.ImmutableMap;
import java.io.IOException;
import javax.servlet.http.HttpServletRequest;
import javax.ws.rs.core.Application;
import javax.ws.rs.core.MediaType;
import javax.ws.rs.core.Response;
import org.apache.commons.lang3.reflect.FieldUtils;
import org.apache.gravitino.Config;
import org.apache.gravitino.GravitinoEnv;
import org.apache.gravitino.dto.responses.ErrorResponse;
import org.apache.gravitino.exceptions.NoSuchMetalakeException;
import org.apache.gravitino.lock.LockManager;
import org.apache.gravitino.rest.RESTUtils;
import org.glassfish.hk2.utilities.binding.AbstractBinder;
import org.glassfish.jersey.server.ResourceConfig;
import org.glassfish.jersey.test.JerseyTest;
import org.glassfish.jersey.test.TestProperties;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

public class TestMetricOperations extends JerseyTest {
  private static class MockServletRequestFactory extends ServletRequestFactoryBase {
    @Override
    public HttpServletRequest get() {
      HttpServletRequest request = mock(HttpServletRequest.class);
      when(request.getRemoteUser()).thenReturn(null);
      return request;
    }
  }

  private final MetricDataService metricDataService = mock(MetricDataService.class);
  private final MetricsCollector metricsCollector = mock(MetricsCollector.class);

  @BeforeAll
  public static void setup() throws IllegalAccessException {
    Config config = mock(Config.class);
    Mockito.doReturn(100000L).when(config).get(TREE_LOCK_MAX_NODE_IN_MEMORY);
    Mockito.doReturn(1000L).when(config).get(TREE_LOCK_MIN_NODE_IN_MEMORY);
    Mockito.doReturn(36000L).when(config).get(TREE_LOCK_CLEAN_INTERVAL);
    FieldUtils.writeField(GravitinoEnv.getInstance(), "lockManager", new LockManager(config), true);
  }

  @Override
  protected Application configure() {
    try {
      forceSet(
          TestProperties.CONTAINER_PORT, String.valueOf(RESTUtils.findAvailablePort(2000, 3000)));
    } catch (IOException e) {
      throw new RuntimeException(e);
    }

    ResourceConfig resourceConfig = new ResourceConfig();
    resourceConfig.register(MetricOperations.class);
    resourceConfig.register(
        new AbstractBinder() {
          @Override
          protected void configure() {
            bind(metricDataService).to(MetricDataService.class).ranked(2);
            bind(metricsCollector).to(MetricsCollector.class).ranked(2);
            bindFactory(TestMetricOperations.MockServletRequestFactory.class)
                .to(HttpServletRequest.class);
          }
        });

    return resourceConfig;
  }

  @Test
  public void testGetMetrics() {
    // test metalake does not exist
    doThrow(new NoSuchMetalakeException("metalake1"))
        .when(metricDataService)
        .getMetricsByNameAndTimestamp(eq("metalake1"), any(), any(), anyLong(), anyLong());

    Response resp =
        target("/web/metrics/metalakes/metalake1")
            .request(MediaType.APPLICATION_JSON_TYPE)
            .accept("application/vnd.gravitino.v1+json")
            .get();

    verify(metricsCollector, Mockito.never()).refreshMetricsForUser(eq("metalake1"), any());
    Assertions.assertEquals(Response.Status.NOT_FOUND.getStatusCode(), resp.getStatus());
    Assertions.assertEquals(MediaType.APPLICATION_JSON_TYPE, resp.getMediaType());

    ErrorResponse errorResponse = resp.readEntity(ErrorResponse.class);
    Assertions.assertEquals(NoSuchMetalakeException.class.getSimpleName(), errorResponse.getType());

    // test get metrics
    MetricDTO metric1 =
        MetricDTO.builder()
            .withName("metric1")
            .withValues(new double[] {1, 2, 3})
            .withTimestamps(new long[] {1000, 2000, 3000})
            .build();
    MetricDTO metric2 =
        MetricDTO.builder()
            .withName("metric2")
            .withValues(new double[] {4, 5, 6})
            .withTimestamps(new long[] {4000, 5000, 6000})
            .build();
    MetricDTO[] metrics = new MetricDTO[] {metric1, metric2};

    doNothing().when(metricsCollector).refreshMetricsForUser(eq("metalake1"), any());
    when(metricDataService.getMetricsByNameAndTimestamp(
            eq("metalake1"), any(), any(), anyLong(), anyLong()))
        .thenReturn(metrics);

    Response response =
        target("/web/metrics/metalakes/metalake1")
            .queryParam("metrics", "metric1,metric2")
            .queryParam("refresh", true)
            .request(MediaType.APPLICATION_JSON_TYPE)
            .accept("application/vnd.gravitino.v1+json")
            .get();

    verify(metricsCollector).refreshMetricsForUser(eq("metalake1"), any());
    Assertions.assertEquals(Response.Status.OK.getStatusCode(), response.getStatus());
    Assertions.assertEquals(MediaType.APPLICATION_JSON_TYPE, response.getMediaType());

    MetricsResponse metricsResponse = response.readEntity(MetricsResponse.class);
    Assertions.assertNotNull(metricsResponse);
    Assertions.assertEquals(
        ImmutableMap.of(metric1.name(), metric1, metric2.name(), metric2),
        metricsResponse.getMetrics());
  }
}
