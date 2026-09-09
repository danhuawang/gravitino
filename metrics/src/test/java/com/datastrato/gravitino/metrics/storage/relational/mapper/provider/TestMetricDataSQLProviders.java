/*
 * Copyright 2024 Datastrato Inc.
 */
package com.datastrato.gravitino.metrics.storage.relational.mapper.provider;

import static org.junit.jupiter.api.Assertions.assertFalse;
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
    String currentOnly =
        provider.getCurrentMetricPOsByUserIdAndMetricNames(1L, 2L, new String[] {"metric"});

    assertTrue(history.contains("ORDER BY dm.metric_name, dm.created_time"));
    assertTrue(history.contains("metric_state as metricState"));
    assertTrue(history.contains("metric_message as metricMessage"));
    assertTrue(current.contains("updated_time &gt;= #{startTimestamp"));
    assertTrue(current.contains("metric_state as metricState"));
    assertTrue(current.contains("metric_message as metricMessage"));
    assertTrue(current.contains("ORDER BY dmc.metric_name, dmc.updated_time"));
    assertTrue(currentOnly.contains("FROM dashboard_metric_current"));
    assertTrue(currentOnly.contains("dmc.metric_name IN"));
    assertTrue(currentOnly.contains("ORDER BY dmc.metric_name"));
  }

  @Test
  void testEnabledPolicyQueryUsesCurrentVersionAndExcludesDeletedRows() {
    MetricDataBaseSQLProvider provider = new MetricDataBaseSQLProvider();
    String query = provider.listEnabledPolicyMetadataObjectIdsByMetalakeId(1L);

    assertTrue(query.contains("pm.current_version = pvi.version"));
    assertTrue(query.contains("prm.deleted_at = 0"));
    assertTrue(query.contains("pm.deleted_at = 0"));
    assertTrue(query.contains("pvi.deleted_at = 0"));
    assertTrue(query.contains("pvi.enabled = TRUE"));
  }

  @Test
  void testCatalogAssociationQueriesDeduplicateAndCoverSupportedObjects() {
    MetricDataBaseSQLProvider provider = new MetricDataBaseSQLProvider();
    String tagQuery = provider.listAssociatedTagCountsByCatalogId();
    String policyQuery = provider.listAssociatedPolicyCountsByCatalogId();

    assertTrue(tagQuery.contains("COUNT(DISTINCT associations.associationId)"));
    assertTrue(tagQuery.contains("metadata_object_type = 'CATALOG'"));
    assertFalse(tagQuery.contains("metadata_object_type = 'COLUMN'"));
    assertTrue(tagQuery.contains("metadata_object_type = 'FUNCTION'"));
    assertTrue(policyQuery.contains("COUNT(DISTINCT associations.associationId)"));
    assertTrue(policyQuery.contains("metadata_object_type = 'CATALOG'"));
    assertTrue(policyQuery.contains("metadata_object_type = 'MODEL'"));
    assertTrue(policyQuery.contains("INNER JOIN policy_meta metadata"));
    assertTrue(policyQuery.contains("metadata.current_version = pvi.version"));
    assertTrue(policyQuery.contains("pvi.deleted_at = 0"));
    assertFalse(policyQuery.contains("enabled = TRUE"));
  }
}
