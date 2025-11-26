/*
 * Copyright 2024 Datastrato Pvt Ltd.
 * This software is licensed under the Apache License version 2.
 */
package com.datastrato.gravitino.metrics.storage.relational.mapper;

import com.datastrato.gravitino.metrics.storage.relational.MetricPO;
import com.datastrato.gravitino.metrics.storage.relational.OwnerNameRelPO;
import com.datastrato.gravitino.metrics.storage.relational.TagNameMetadataObjectRelPO;
import java.sql.Timestamp;
import java.util.List;
import java.util.Set;
import org.apache.gravitino.storage.relational.po.SecurableObjectPO;
import org.apache.gravitino.storage.relational.po.UserRoleRelPO;
import org.apache.ibatis.annotations.DeleteProvider;
import org.apache.ibatis.annotations.InsertProvider;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.SelectProvider;

public interface MetricDataMapper {
  String METRICS_TABLE_NAME = "dashboard_metrics";

  @SelectProvider(
      type = MetricDataSQLProviderFactory.class,
      method = "getMetricPOsByUserIdMetricNamesAndTimestamp")
  List<MetricPO> getMetricPOsByUserIdMetricNamesAndTimestamp(
      @Param("metalakeId") long metalakeId,
      @Param("userId") long userId,
      @Param("metricNames") String[] metricNames,
      @Param("startTimestamp") Timestamp startTimestamp,
      @Param("endTimestamp") Timestamp endTimestamp);

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

  @SelectProvider(type = MetricDataSQLProviderFactory.class, method = "getTagCountByMetalakeId")
  long getTagCountByMetalakeId(@Param("metalakeId") long metalakeId);

  @InsertProvider(type = MetricDataSQLProviderFactory.class, method = "insertMetricsData")
  void insertMetricsData(
      @Param("metalakeId") long metalakeId,
      @Param("userId") long userId,
      @Param("metrics") List<MetricPO> metrics);

  @DeleteProvider(type = MetricDataSQLProviderFactory.class, method = "cleanInvalidMetrics")
  void cleanInvalidMetrics();

  @DeleteProvider(type = MetricDataSQLProviderFactory.class, method = "cleanMetricsByTimestamp")
  void cleanMetricsByTimestamp(@Param("oldestTimestamp") Timestamp oldestTimestamp);
}
