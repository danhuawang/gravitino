/*
 * Copyright 2024 Datastrato Inc.
 */
package com.datastrato.gravitino.metrics.storage.relational.mapper;

import com.datastrato.gravitino.metrics.storage.relational.MetricDirtyPO;
import com.datastrato.gravitino.metrics.storage.relational.MetricPO;
import com.datastrato.gravitino.metrics.storage.relational.OwnerNameRelPO;
import com.datastrato.gravitino.metrics.storage.relational.TagNameMetadataObjectRelPO;
import java.sql.Timestamp;
import java.util.List;
import java.util.Set;
import javax.annotation.Nullable;
import org.apache.gravitino.storage.relational.po.SecurableObjectPO;
import org.apache.gravitino.storage.relational.po.UserRoleRelPO;
import org.apache.ibatis.annotations.DeleteProvider;
import org.apache.ibatis.annotations.InsertProvider;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.SelectProvider;
import org.apache.ibatis.annotations.UpdateProvider;

public interface MetricDataMapper {
  String METRICS_TABLE_NAME = "dashboard_metrics";

  /** Current dashboard metric table name. */
  String CURRENT_METRICS_TABLE_NAME = "dashboard_metric_current";

  /** Durable dirty-marker table name. */
  String DIRTY_METRICS_TABLE_NAME = "dashboard_metric_dirty";

  @SelectProvider(
      type = MetricDataSQLProviderFactory.class,
      method = "getMetricPOsByUserIdMetricNamesAndTimestamp")
  List<MetricPO> getMetricPOsByUserIdMetricNamesAndTimestamp(
      @Param("metalakeId") long metalakeId,
      @Param("userId") long userId,
      @Param("metricNames") String[] metricNames,
      @Param("startTimestamp") Timestamp startTimestamp,
      @Param("endTimestamp") Timestamp endTimestamp);

  /**
   * Returns current metrics matching the user, metric, and time filters.
   *
   * @param metalakeId metalake ID
   * @param userId persisted user ID
   * @param metricNames optional metric-name filter
   * @param startTimestamp inclusive start time
   * @param endTimestamp inclusive end time
   * @return matching current metric rows
   */
  @SelectProvider(
      type = MetricDataSQLProviderFactory.class,
      method = "getCurrentMetricPOsByUserIdMetricNamesAndTimestamp")
  List<MetricPO> getCurrentMetricPOsByUserIdMetricNamesAndTimestamp(
      @Param("metalakeId") long metalakeId,
      @Param("userId") long userId,
      @Param("metricNames") String[] metricNames,
      @Param("startTimestamp") Timestamp startTimestamp,
      @Param("endTimestamp") Timestamp endTimestamp);

  /**
   * Returns current metrics matching the user and exact metric names without reading history.
   *
   * @param metalakeId metalake ID
   * @param userId persisted user ID
   * @param metricNames optional metric-name filter
   * @return matching current metric rows
   */
  @SelectProvider(
      type = MetricDataSQLProviderFactory.class,
      method = "getCurrentMetricPOsByUserIdAndMetricNames")
  List<MetricPO> getCurrentMetricPOsByUserIdAndMetricNames(
      @Param("metalakeId") long metalakeId,
      @Param("userId") long userId,
      @Param("metricNames") @Nullable String[] metricNames);

  @SelectProvider(type = MetricDataSQLProviderFactory.class, method = "listUserRoleRelsByUserIds")
  List<UserRoleRelPO> listUserRoleRelsByUserIds(@Param("userIds") Set<Long> userIds);

  @SelectProvider(
      type = MetricDataSQLProviderFactory.class,
      method = "listOwnerNameRelsByMetalakeId")
  List<OwnerNameRelPO> listOwnerNameRelsByMetalakeId(@Param("metalakeId") long metalakeId);

  @SelectProvider(
      type = MetricDataSQLProviderFactory.class,
      method = "listSecurableObjectsByRoleIds")
  List<SecurableObjectPO> listSecurableObjectsByRoleIds(@Param("roleIds") Set<Long> roleIds);

  @SelectProvider(
      type = MetricDataSQLProviderFactory.class,
      method = "listTagNameMetadataObjectRelsByMetalakeId")
  List<TagNameMetadataObjectRelPO> listTagNameMetadataObjectRelsByMetalakeId(
      @Param("metalakeId") long metalakeId);

  /**
   * Lists metadata object IDs with at least one current, enabled policy relation.
   *
   * @param metalakeId metalake ID
   * @return metadata object IDs covered by enabled policies
   */
  @SelectProvider(
      type = MetricDataSQLProviderFactory.class,
      method = "listEnabledPolicyMetadataObjectIdsByMetalakeId")
  Set<Long> listEnabledPolicyMetadataObjectIdsByMetalakeId(@Param("metalakeId") long metalakeId);

  @SelectProvider(type = MetricDataSQLProviderFactory.class, method = "getTagCountByMetalakeId")
  long getTagCountByMetalakeId(@Param("metalakeId") long metalakeId);

  @InsertProvider(type = MetricDataSQLProviderFactory.class, method = "insertMetricsData")
  void insertMetricsData(
      @Param("metalakeId") long metalakeId,
      @Param("userId") long userId,
      @Param("metrics") List<MetricPO> metrics);

  /**
   * Inserts a batch of history metrics whose rows contain complete keys.
   *
   * @param metrics history metric rows
   */
  @InsertProvider(type = MetricDataSQLProviderFactory.class, method = "insertMetricsDataBatch")
  void insertMetricsDataBatch(@Param("metrics") List<MetricPO> metrics);

  /**
   * Deletes every current metric for a metalake.
   *
   * @param metalakeId metalake ID
   * @return number of deleted rows
   */
  @DeleteProvider(type = MetricDataSQLProviderFactory.class, method = "deleteCurrentMetrics")
  int deleteCurrentMetrics(@Param("metalakeId") long metalakeId);

  /**
   * Inserts a batch of current metric rows.
   *
   * @param metrics current metric rows
   */
  @InsertProvider(type = MetricDataSQLProviderFactory.class, method = "insertCurrentMetrics")
  void insertCurrentMetrics(@Param("metrics") List<MetricPO> metrics);

  /**
   * Atomically creates or advances a metalake dirty marker.
   *
   * @param metalakeId metalake ID
   * @param eventTime metadata event time
   * @return number of affected rows
   */
  @InsertProvider(type = MetricDataSQLProviderFactory.class, method = "markMetalakeDirty")
  int markMetalakeDirty(
      @Param("metalakeId") long metalakeId, @Param("eventTime") Timestamp eventTime);

  /**
   * Lists dirty markers whose debounce or retry deadline is due.
   *
   * @param quietCutoff inclusive quiet-period cutoff
   * @param maxDebounceCutoff inclusive maximum-debounce cutoff
   * @param now current time
   * @return due dirty markers
   */
  @SelectProvider(type = MetricDataSQLProviderFactory.class, method = "listDueDirtyMetalakes")
  List<MetricDirtyPO> listDueDirtyMetalakes(
      @Param("quietCutoff") Timestamp quietCutoff,
      @Param("maxDebounceCutoff") Timestamp maxDebounceCutoff,
      @Param("now") Timestamp now);

  /**
   * Returns the current dirty marker for a metalake, if one exists.
   *
   * @param metalakeId metalake ID
   * @return dirty marker, or {@code null}
   */
  @SelectProvider(type = MetricDataSQLProviderFactory.class, method = "getDirtyMetalake")
  @Nullable
  MetricDirtyPO getDirtyMetalake(@Param("metalakeId") long metalakeId);

  /**
   * Deletes a dirty marker when its revision matches the expected value.
   *
   * @param metalakeId metalake ID
   * @param revision expected revision
   * @return number of deleted rows
   */
  @DeleteProvider(type = MetricDataSQLProviderFactory.class, method = "deleteDirtyIfRevision")
  int deleteDirtyIfRevision(@Param("metalakeId") long metalakeId, @Param("revision") long revision);

  /**
   * Stores retry state when the dirty revision still matches.
   *
   * @param metalakeId metalake ID
   * @param revision expected revision
   * @param retryCount consecutive failure count
   * @param retryAfter earliest retry time
   * @param lastError truncated failure message
   * @return number of updated rows
   */
  @UpdateProvider(type = MetricDataSQLProviderFactory.class, method = "markRetryIfRevision")
  int markRetryIfRevision(
      @Param("metalakeId") long metalakeId,
      @Param("revision") long revision,
      @Param("retryCount") int retryCount,
      @Param("retryAfter") Timestamp retryAfter,
      @Param("lastError") String lastError);

  @DeleteProvider(type = MetricDataSQLProviderFactory.class, method = "cleanInvalidMetrics")
  int cleanInvalidMetrics();

  /**
   * Deletes current metrics for invalid metalakes or users.
   *
   * @return number of deleted rows
   */
  @DeleteProvider(type = MetricDataSQLProviderFactory.class, method = "cleanInvalidCurrentMetrics")
  int cleanInvalidCurrentMetrics();

  /**
   * Deletes dirty markers for invalid metalakes.
   *
   * @return number of deleted rows
   */
  @DeleteProvider(type = MetricDataSQLProviderFactory.class, method = "cleanInvalidDirtyMetrics")
  int cleanInvalidDirtyMetrics();

  @DeleteProvider(type = MetricDataSQLProviderFactory.class, method = "cleanMetricsByTimestamp")
  void cleanMetricsByTimestamp(@Param("oldestTimestamp") Timestamp oldestTimestamp);
}
