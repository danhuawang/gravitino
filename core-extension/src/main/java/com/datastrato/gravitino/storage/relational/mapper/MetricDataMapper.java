/*
 * Copyright 2024 Datastrato Pvt Ltd.
 * This software is licensed under the Apache License version 2.
 */
package com.datastrato.gravitino.storage.relational.mapper;

import com.datastrato.gravitino.storage.relational.MetricPO;
import java.util.List;
import org.apache.ibatis.annotations.DeleteProvider;
import org.apache.ibatis.annotations.InsertProvider;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.SelectProvider;

public interface MetricDataMapper {
  String METRICS_TABLE_NAME = "dashboard_metrics";

  @SelectProvider(
      type = MetricDataSQLProviderFactory.class,
      method = "getMetricDTOsByNameAndTimestamp")
  List<MetricPO> getMetricDTOsByNameAndTimestamp(
      @Param("metalakeName") String metalakeName,
      @Param("userName") String userName,
      @Param("metricNames") String[] metricNames,
      @Param("startTimestamp") long startTimestamp,
      @Param("endTimestamp") long endTimestamp,
      @Param("enableAuthorization") boolean enableAuthorization);

  @SelectProvider(type = MetricDataSQLProviderFactory.class, method = "getAssetWithOwnerCount")
  Long getAssetWithOwnerCount(@Param("metalakeName") String metalakeName);

  @InsertProvider(type = MetricDataSQLProviderFactory.class, method = "insertMetricsData")
  void insertMetricsData(
      @Param("metalakeName") String metalakeName,
      @Param("userName") String userName,
      @Param("metrics") List<MetricPO> metrics,
      @Param("enableAuthorization") boolean enableAuthorization);

  @DeleteProvider(type = MetricDataSQLProviderFactory.class, method = "cleanInvalidMetrics")
  void cleanInvalidMetrics();

  @DeleteProvider(type = MetricDataSQLProviderFactory.class, method = "cleanMetricsByTimestamp")
  void cleanMetricsByTimestamp(@Param("oldestTimestamp") long oldestTimestamp);
}
