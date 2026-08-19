/*
 * Copyright 2024 Datastrato Pvt Ltd.
 * This software is licensed under the Apache License version 2.
 */
package com.datastrato.gravitino.metrics.storage.relational.mapper.provider;

import static org.junit.jupiter.api.Assertions.assertTrue;

import com.datastrato.gravitino.metrics.storage.relational.mapper.provider.base.MetricDataBaseSQLProvider;
import com.datastrato.gravitino.metrics.storage.relational.mapper.provider.postgresql.MetricDataPostgreSQLProvider;
import org.junit.jupiter.api.Test;

class TestMetricDataSQLProviders {

  @Test
  void testDirtyUpsertUsesBackendSpecificAtomicSyntax() {
    String mysqlAndH2 = new MetricDataBaseSQLProvider().markMetalakeDirty(1L, null);
    String postgresql = new MetricDataPostgreSQLProvider().markMetalakeDirty(1L, null);

    assertTrue(mysqlAndH2.contains("ON DUPLICATE KEY UPDATE"));
    assertTrue(mysqlAndH2.contains("revision = revision + 1"));
    assertTrue(mysqlAndH2.contains("first_dirty_at = LEAST"));
    assertTrue(postgresql.contains("ON CONFLICT (metalake_id) DO UPDATE"));
    assertTrue(postgresql.contains("dashboard_metric_dirty.revision + 1"));
    assertTrue(postgresql.contains("first_dirty_at = LEAST"));
  }

  @Test
  void testHistoryAndCurrentQueriesAreOrderedAndRangeBounded() {
    MetricDataBaseSQLProvider provider = new MetricDataBaseSQLProvider();
    String history = provider.getMetricPOsByUserIdMetricNamesAndTimestamp(1L, 2L, null, null, null);
    String current =
        provider.getCurrentMetricPOsByUserIdMetricNamesAndTimestamp(1L, 2L, null, null, null);

    assertTrue(history.contains("ORDER BY dm.metric_name, dm.created_time"));
    assertTrue(current.contains("updated_time &gt;= #{startTimestamp"));
    assertTrue(current.contains("ORDER BY dmc.metric_name, dmc.updated_time"));
  }
}
