/*
 * Copyright 2026 Datastrato Inc.
 */
package com.datastrato.gravitino.metrics;

import com.codahale.metrics.Histogram;
import com.codahale.metrics.Meter;
import com.codahale.metrics.Timer;
import java.util.concurrent.TimeUnit;
import org.apache.gravitino.metrics.source.MetricsSource;

/** Low-cardinality runtime metrics for dashboard metric collection and publication. */
final class DashboardMetricsCollectorMetricsSource extends MetricsSource {
  static final String SOURCE_NAME = "dashboard-metrics-collector";

  private final Timer loadDuration = getTimer("load-duration");
  private final Timer calculationDuration = getTimer("calculation-duration");
  private final Timer publishDuration = getTimer("publish-duration");
  private final Timer totalDuration = getTimer("total-duration");
  private final Histogram publishedRowCount = getHistogram("published-row-count");
  private final Histogram directChildRowCount = getHistogram("direct-child-row-count");
  private final Meter completeCollections = getMeter("complete-collections");
  private final Meter incompleteCollections = getMeter("incomplete-collections");
  private final Meter failedCollections = getMeter("failed-collections");

  DashboardMetricsCollectorMetricsSource() {
    super(SOURCE_NAME);
  }

  void recordLoadDuration(long durationNanos) {
    loadDuration.update(durationNanos, TimeUnit.NANOSECONDS);
  }

  void recordCalculationDuration(long durationNanos) {
    calculationDuration.update(durationNanos, TimeUnit.NANOSECONDS);
  }

  void recordPublishDuration(long durationNanos) {
    publishDuration.update(durationNanos, TimeUnit.NANOSECONDS);
  }

  void recordTotalDuration(long durationNanos) {
    totalDuration.update(durationNanos, TimeUnit.NANOSECONDS);
  }

  void recordPublishedRows(long rows, long directChildRows) {
    publishedRowCount.update(rows);
    directChildRowCount.update(directChildRows);
  }

  void recordOutcome(MetricsCollector.CollectionOutcome outcome) {
    if (outcome == MetricsCollector.CollectionOutcome.COMPLETE) {
      completeCollections.mark();
    } else {
      incompleteCollections.mark();
    }
  }

  void recordFailure() {
    failedCollections.mark();
  }
}
