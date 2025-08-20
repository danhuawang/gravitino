/*
 * Copyright 2024 Datastrato Pvt Ltd.
 * This software is licensed under the Apache License version 2.
 */
package com.datastrato.gravitino.metrics.dto;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.google.common.collect.ImmutableMap;
import java.util.Map;
import org.apache.gravitino.json.JsonUtils;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

public class TestMetricsResponses {
  @Test
  public void testMetricsResponse() throws JsonProcessingException {
    Map<String, MetricDTO> metrics =
        ImmutableMap.of(
            "metric1",
            MetricDTO.builder()
                .withName("metric1")
                .withValues(new double[] {1, 2, 3})
                .withTimestamps(new long[] {1000, 2000, 3000})
                .build(),
            "metric2",
            MetricDTO.builder()
                .withName("metric2")
                .withValues(new double[] {4, 5, 6})
                .withTimestamps(new long[] {4000, 5000, 6000})
                .build());

    MetricsResponse response = new MetricsResponse(metrics);
    Assertions.assertDoesNotThrow(response::validate);

    String serJson = JsonUtils.objectMapper().writeValueAsString(response);
    MetricsResponse deserialized =
        JsonUtils.objectMapper().readValue(serJson, MetricsResponse.class);
    Assertions.assertEquals(response, deserialized);
    Assertions.assertEquals(metrics, deserialized.getMetrics());

    MetricsResponse illegalResp = new MetricsResponse();
    Exception exception =
        Assertions.assertThrows(IllegalArgumentException.class, illegalResp::validate);
    Assertions.assertEquals("\"metrics\" cannot be null", exception.getMessage());
  }
}
