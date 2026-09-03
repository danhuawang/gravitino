/*
 * Copyright 2026 Datastrato Inc.
 */
package com.datastrato.gravitino.metrics;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.datastrato.gravitino.metrics.storage.relational.service.MetricDataService;
import org.junit.jupiter.api.Test;

class TestDashboardMetricNames {

  @Test
  void testBuildAndParseCatalogMetricName() {
    String name = DashboardMetricNames.forCatalog("sales", "jdbc-postgresql", "asset_count");

    assertEquals("by_catalog::sales::jdbc-postgresql::asset_count", name);
    DashboardMetricNames.CatalogMetric parsed = DashboardMetricNames.parseCatalog(name);
    assertEquals("sales", parsed.catalogName());
    assertEquals("jdbc-postgresql", parsed.provider());
    assertEquals("asset_count", parsed.metricName());
  }

  @Test
  void testCatalogMetricComponentsCannotContainSeparator() {
    assertThrows(
        IllegalArgumentException.class,
        () -> DashboardMetricNames.forCatalog("sales::archive", "hive", "asset_count"));
    assertThrows(
        IllegalArgumentException.class,
        () -> DashboardMetricNames.forCatalog("sales", "vendor::custom", "asset_count"));
    assertThrows(
        IllegalArgumentException.class,
        () -> DashboardMetricNames.forCatalog("sales", "hive", "asset::count"));
    assertThrows(
        IllegalArgumentException.class,
        () -> DashboardMetricNames.parseCatalog("by_catalog::sales::asset_count"));
  }

  @Test
  void testCatalogMetricNameStorageBound() {
    String longestPersistedComponents =
        DashboardMetricNames.forCatalog(
            "c".repeat(128),
            "p".repeat(64),
            MetricDataService.Metric.POLICY_COVERED_ASSET_COUNT.getName());
    assertEquals(234, longestPersistedComponents.length());
    assertTrue(longestPersistedComponents.length() <= DashboardMetricNames.MAX_METRIC_NAME_LENGTH);

    String exactLimit = DashboardMetricNames.forCatalog("c".repeat(238), "p", "m");
    assertEquals(DashboardMetricNames.MAX_METRIC_NAME_LENGTH, exactLimit.length());
    assertThrows(
        IllegalArgumentException.class,
        () -> DashboardMetricNames.forCatalog("c".repeat(239), "p", "m"));
  }
}
