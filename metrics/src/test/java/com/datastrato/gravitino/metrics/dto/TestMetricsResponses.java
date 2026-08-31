/*
 * Copyright 2024 Datastrato Inc.
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
                .withValues(new Double[] {1.0, 2.0, 3.0})
                .withTimestamps(new long[] {1000, 2000, 3000})
                .withStates(
                    new MetricState[] {
                      MetricState.COMPLETE, MetricState.COMPLETE, MetricState.COMPLETE
                    })
                .withMessages(new String[] {null, null, null})
                .build(),
            "metric2",
            MetricDTO.builder()
                .withName("metric2")
                .withValues(new Double[] {4.0, null, 6.0})
                .withTimestamps(new long[] {4000, 5000, 6000})
                .withStates(
                    new MetricState[] {
                      MetricState.COMPLETE, MetricState.UNAVAILABLE, MetricState.COMPLETE
                    })
                .withMessages(new String[] {null, "Metric data is temporarily unavailable.", null})
                .build());

    MetricsResponse response = new MetricsResponse(metrics);
    Assertions.assertDoesNotThrow(response::validate);

    String serJson = JsonUtils.objectMapper().writeValueAsString(response);
    Assertions.assertTrue(serJson.contains("\"values\":[4.0,null,6.0]"));
    Assertions.assertTrue(
        serJson.contains("\"states\":[\"COMPLETE\",\"UNAVAILABLE\",\"COMPLETE\"]"));
    Assertions.assertFalse(serJson.contains("\"complete\""));
    Assertions.assertFalse(serJson.contains("\"unavailable\""));
    MetricsResponse deserialized =
        JsonUtils.objectMapper().readValue(serJson, MetricsResponse.class);
    Assertions.assertEquals(response, deserialized);
    Assertions.assertEquals(metrics, deserialized.getMetrics());

    MetricsResponse illegalResp = new MetricsResponse();
    Exception exception =
        Assertions.assertThrows(IllegalArgumentException.class, illegalResp::validate);
    Assertions.assertEquals("\"metrics\" cannot be null", exception.getMessage());
  }

  @Test
  public void testMetricDataPointsMustStayAlignedAndRespectStateValues() {
    Assertions.assertThrows(
        IllegalArgumentException.class,
        () ->
            MetricDTO.builder()
                .withName("misaligned")
                .withValues(new Double[] {1.0})
                .withTimestamps(new long[] {1L, 2L})
                .withStates(new MetricState[] {MetricState.COMPLETE})
                .withMessages(new String[] {null})
                .build());
    Assertions.assertThrows(
        IllegalArgumentException.class,
        () ->
            MetricDTO.builder()
                .withName("partial_without_value")
                .withValues(new Double[] {null})
                .withTimestamps(new long[] {1L})
                .withStates(new MetricState[] {MetricState.PARTIAL})
                .withMessages(new String[] {"Some catalog data is temporarily unavailable."})
                .build());
    Assertions.assertThrows(
        IllegalArgumentException.class,
        () ->
            MetricDTO.builder()
                .withName("unavailable_with_value")
                .withValues(new Double[] {1.0})
                .withTimestamps(new long[] {1L})
                .withStates(new MetricState[] {MetricState.UNAVAILABLE})
                .withMessages(new String[] {"Metric data is temporarily unavailable."})
                .build());
    Assertions.assertThrows(
        IllegalArgumentException.class,
        () ->
            MetricDTO.builder()
                .withName("partial_without_message")
                .withValues(new Double[] {1.0})
                .withTimestamps(new long[] {1L})
                .withStates(new MetricState[] {MetricState.PARTIAL})
                .withMessages(new String[] {null})
                .build());
  }
}
