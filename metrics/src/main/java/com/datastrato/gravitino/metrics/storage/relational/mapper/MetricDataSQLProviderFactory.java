/*
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *
 *  http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing,
 * software distributed under the License is distributed on an
 * "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
 * KIND, either express or implied.  See the License for the
 * specific language governing permissions and limitations
 * under the License.
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

  public static String insertMetricsData(
      @Param("metalakeId") long metalakeId,
      @Param("userId") long userId,
      @Param("metrics") List<MetricPO> metrics) {
    return getProvider().insertMetricsData(metalakeId, userId, metrics);
  }

  public static String cleanInvalidMetrics() {
    return getProvider().cleanInvalidMetrics();
  }

  public static String cleanMetricsByTimestamp(
      @Param("oldestTimestamp") Timestamp oldestTimestamp) {
    return getProvider().cleanMetricsByTimestamp(oldestTimestamp);
  }
}
