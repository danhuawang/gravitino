/*
 * Copyright 2024 Datastrato Inc.
 */

package com.datastrato.gravitino.metrics.storage.relational.mapper;

import com.datastrato.gravitino.metrics.storage.relational.MetricPO;
import com.datastrato.gravitino.metrics.storage.relational.mapper.provider.base.MetricDataBaseSQLProvider;
import com.datastrato.gravitino.metrics.storage.relational.mapper.provider.h2.MetricDataH2Provider;
import com.datastrato.gravitino.metrics.storage.relational.mapper.provider.postgresql.MetricDataPostgreSQLProvider;
import com.google.common.collect.ImmutableMap;
import java.sql.Timestamp;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.apache.gravitino.storage.relational.JDBCBackend.JDBCBackendType;
import org.apache.gravitino.storage.relational.session.SqlSessionFactoryHelper;
import org.apache.ibatis.annotations.Param;

public class MetricDataSQLProviderFactory {

  private static final Map<JDBCBackendType, MetricDataBaseSQLProvider>
      METRIC_DATA_SQL_PROVIDER_MAP =
          ImmutableMap.of(
              JDBCBackendType.MYSQL, new MetricDataBaseSQLProvider(),
              JDBCBackendType.H2, new MetricDataH2Provider(),
              JDBCBackendType.POSTGRESQL, new MetricDataPostgreSQLProvider());

  public static MetricDataBaseSQLProvider getProvider() {
    String databaseId =
        SqlSessionFactoryHelper.getInstance()
            .getSqlSessionFactory()
            .getConfiguration()
            .getDatabaseId();

    JDBCBackendType jdbcBackendType = JDBCBackendType.fromString(databaseId);
    return METRIC_DATA_SQL_PROVIDER_MAP.get(jdbcBackendType);
  }

  public static String getMetricPOsByUserIdMetricNamesAndTimestamp(
      @Param("metalakeId") long metalakeId,
      @Param("userId") long userId,
      @Param("metricNames") String[] metricNames,
      @Param("startTimestamp") Timestamp startTimestamp,
      @Param("endTimestamp") Timestamp endTimestamp) {
    return getProvider()
        .getMetricPOsByUserIdMetricNamesAndTimestamp(
            metalakeId, userId, metricNames, startTimestamp, endTimestamp);
  }

  /**
   * Returns the backend-specific current metric query.
   *
   * @param metalakeId metalake ID
   * @param userId persisted user ID
   * @param metricNames optional metric-name filter
   * @param startTimestamp inclusive start time
   * @param endTimestamp inclusive end time
   * @return current metric query
   */
  public static String getCurrentMetricPOsByUserIdMetricNamesAndTimestamp(
      @Param("metalakeId") long metalakeId,
      @Param("userId") long userId,
      @Param("metricNames") String[] metricNames,
      @Param("startTimestamp") Timestamp startTimestamp,
      @Param("endTimestamp") Timestamp endTimestamp) {
    return getProvider()
        .getCurrentMetricPOsByUserIdMetricNamesAndTimestamp(
            metalakeId, userId, metricNames, startTimestamp, endTimestamp);
  }

  public static String getTagCountByMetalakeId(@Param("metalakeId") long metalakeId) {
    return getProvider().getTagCountByMetalakeId(metalakeId);
  }

  public static String listUserRoleRelsByUserIds(@Param("userIds") Set<Long> userIds) {
    return getProvider().listUserRoleRelsByUserIds(userIds);
  }

  public static String listOwnerNameRelsByMetalakeId(@Param("metalakeId") long metalakeId) {
    return getProvider().listOwnerNameRelsByMetalakeId(metalakeId);
  }

  public static String listSecurableObjectsByRoleIds(@Param("roleIds") Set<Long> roleIds) {
    return getProvider().listSecurableObjectsByRoleIds(roleIds);
  }

  public static String listTagNameMetadataObjectRelsByMetalakeId(
      @Param("metalakeId") long metalakeId) {
    return getProvider().listTagNameMetadataObjectRelsByMetalakeId(metalakeId);
  }

  /**
   * Returns the query for metadata objects covered by current, enabled policies.
   *
   * @param metalakeId metalake ID
   * @return enabled policy relation query
   */
  public static String listEnabledPolicyMetadataObjectIdsByMetalakeId(
      @Param("metalakeId") long metalakeId) {
    return getProvider().listEnabledPolicyMetadataObjectIdsByMetalakeId(metalakeId);
  }

  public static String insertMetricsData(
      @Param("metalakeId") long metalakeId,
      @Param("userId") long userId,
      @Param("metrics") List<MetricPO> metrics) {
    return getProvider().insertMetricsData(metalakeId, userId, metrics);
  }

  /**
   * Returns the backend-specific batch history insert.
   *
   * @param metrics history metric rows
   * @return batch insert statement
   */
  public static String insertMetricsDataBatch(@Param("metrics") List<MetricPO> metrics) {
    return getProvider().insertMetricsDataBatch(metrics);
  }

  /**
   * Returns the backend-specific current metric deletion.
   *
   * @param metalakeId metalake ID
   * @return current metric delete statement
   */
  public static String deleteCurrentMetrics(@Param("metalakeId") long metalakeId) {
    return getProvider().deleteCurrentMetrics(metalakeId);
  }

  /**
   * Returns the backend-specific current metric insert.
   *
   * @param metrics current metric rows
   * @return batch insert statement
   */
  public static String insertCurrentMetrics(@Param("metrics") List<MetricPO> metrics) {
    return getProvider().insertCurrentMetrics(metrics);
  }

  /**
   * Returns the backend-specific atomic dirty-marker upsert.
   *
   * @param metalakeId metalake ID
   * @param eventTime metadata event time
   * @return dirty-marker upsert statement
   */
  public static String markMetalakeDirty(
      @Param("metalakeId") long metalakeId, @Param("eventTime") Timestamp eventTime) {
    return getProvider().markMetalakeDirty(metalakeId, eventTime);
  }

  /**
   * Returns the backend-specific due dirty-marker query.
   *
   * @param quietCutoff inclusive quiet-period cutoff
   * @param maxDebounceCutoff inclusive maximum-debounce cutoff
   * @param now current time
   * @return due dirty-marker query
   */
  public static String listDueDirtyMetalakes(
      @Param("quietCutoff") Timestamp quietCutoff,
      @Param("maxDebounceCutoff") Timestamp maxDebounceCutoff,
      @Param("now") Timestamp now) {
    return getProvider().listDueDirtyMetalakes(quietCutoff, maxDebounceCutoff, now);
  }

  /**
   * Returns the backend-specific dirty-marker lookup.
   *
   * @param metalakeId metalake ID
   * @return dirty-marker lookup query
   */
  public static String getDirtyMetalake(@Param("metalakeId") long metalakeId) {
    return getProvider().getDirtyMetalake(metalakeId);
  }

  /**
   * Returns the backend-specific dirty-marker compare-and-delete statement.
   *
   * @param metalakeId metalake ID
   * @param revision expected revision
   * @return compare-and-delete statement
   */
  public static String deleteDirtyIfRevision(
      @Param("metalakeId") long metalakeId, @Param("revision") long revision) {
    return getProvider().deleteDirtyIfRevision(metalakeId, revision);
  }

  /**
   * Returns the backend-specific retry compare-and-update statement.
   *
   * @param metalakeId metalake ID
   * @param revision expected revision
   * @param retryCount consecutive failure count
   * @param retryAfter earliest retry time
   * @param lastError truncated failure message
   * @return compare-and-update statement
   */
  public static String markRetryIfRevision(
      @Param("metalakeId") long metalakeId,
      @Param("revision") long revision,
      @Param("retryCount") int retryCount,
      @Param("retryAfter") Timestamp retryAfter,
      @Param("lastError") String lastError) {
    return getProvider()
        .markRetryIfRevision(metalakeId, revision, retryCount, retryAfter, lastError);
  }

  public static String cleanInvalidMetrics() {
    return getProvider().cleanInvalidMetrics();
  }

  /**
   * Returns the backend-specific invalid current metric cleanup.
   *
   * @return invalid current metric cleanup statement
   */
  public static String cleanInvalidCurrentMetrics() {
    return getProvider().cleanInvalidCurrentMetrics();
  }

  /**
   * Returns the backend-specific invalid dirty-marker cleanup.
   *
   * @return invalid dirty-marker cleanup statement
   */
  public static String cleanInvalidDirtyMetrics() {
    return getProvider().cleanInvalidDirtyMetrics();
  }

  public static String cleanMetricsByTimestamp(
      @Param("oldestTimestamp") Timestamp oldestTimestamp) {
    return getProvider().cleanMetricsByTimestamp(oldestTimestamp);
  }
}
