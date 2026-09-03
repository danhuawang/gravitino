/*
 * Copyright 2026 Datastrato Inc.
 */
package com.datastrato.gravitino.metrics;

import static org.junit.jupiter.api.Assertions.assertEquals;

import org.junit.jupiter.api.Test;

class TestDashboardMetricsCollectorMetricsSource {

  @Test
  void testRecordsLowCardinalityDurationsRowsAndOutcomes() {
    DashboardMetricsCollectorMetricsSource source = new DashboardMetricsCollectorMetricsSource();

    source.recordLoadDuration(1_000L);
    source.recordCalculationDuration(2_000L);
    source.recordPublishDuration(3_000L);
    source.recordTotalDuration(6_000L);
    source.recordPublishedRows(25L, 9L);
    source.recordOutcome(MetricsCollector.CollectionOutcome.COMPLETE);
    source.recordOutcome(MetricsCollector.CollectionOutcome.INCOMPLETE);
    source.recordFailure();

    assertEquals(1L, source.getMetricRegistry().timer("load-duration").getCount());
    assertEquals(1L, source.getMetricRegistry().timer("calculation-duration").getCount());
    assertEquals(1L, source.getMetricRegistry().timer("publish-duration").getCount());
    assertEquals(1L, source.getMetricRegistry().timer("total-duration").getCount());
    assertEquals(
        25L, source.getMetricRegistry().histogram("published-row-count").getSnapshot().getMax());
    assertEquals(
        9L, source.getMetricRegistry().histogram("direct-child-row-count").getSnapshot().getMax());
    assertEquals(1L, source.getMetricRegistry().meter("complete-collections").getCount());
    assertEquals(1L, source.getMetricRegistry().meter("incomplete-collections").getCount());
    assertEquals(1L, source.getMetricRegistry().meter("failed-collections").getCount());
  }
}
