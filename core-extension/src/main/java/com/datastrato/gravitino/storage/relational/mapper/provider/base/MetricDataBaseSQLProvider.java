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

package com.datastrato.gravitino.storage.relational.mapper.provider.base;

import static com.datastrato.gravitino.storage.relational.mapper.MetricDataMapper.METRICS_TABLE_NAME;

import com.datastrato.gravitino.storage.relational.MetricPO;
import java.sql.Timestamp;
import java.util.List;
import org.apache.gravitino.storage.relational.mapper.MetalakeMetaMapper;
import org.apache.gravitino.storage.relational.mapper.OwnerMetaMapper;
import org.apache.gravitino.storage.relational.mapper.UserRoleRelMapper;
import org.apache.ibatis.annotations.Param;

public class MetricDataBaseSQLProvider {
  private static final String MOCK_ANONYMOUS_USER_ID = "0";
  private static final String MOCK_METALAKE_OWNER_ID = "1";

  public String getMetricPOsByNameAndTimestamp(
      @Param("metalakeName") String metalakeName,
      @Param("userName") String userName,
      @Param("metricNames") String[] metricNames,
      @Param("startTimestamp") Timestamp startTimestamp,
      @Param("endTimestamp") Timestamp endTimestamp,
      @Param("enableAuthorization") boolean enableAuthorization,
      @Param("forMetalakeOwner") boolean forMetalakeOwner) {
    return "<script>"
        + "SELECT metricName, createdTime, metricValue FROM ("
        + "SELECT dm.metric_name as metricName, dm.created_time as createdTime, dm.metric_value as metricValue, "
        // only select the latest metric for each metric_name and date
        + "ROW_NUMBER() OVER (PARTITION BY dm.metric_name, DATE(dm.created_time) ORDER BY dm.created_time DESC) as rn "
        + "FROM "
        + METRICS_TABLE_NAME
        + " dm JOIN "
        + MetalakeMetaMapper.TABLE_NAME
        + " mm ON dm.metalake_id = mm.metalake_id "
        + "<choose>"
        + "<when test='forMetalakeOwner == true'>"
        + "WHERE mm.metalake_name = #{metalakeName} "
        + "AND dm.user_id = "
        + MOCK_METALAKE_OWNER_ID
        + "</when>"
        + "<when test='enableAuthorization == true'>"
        + "JOIN "
        + UserRoleRelMapper.USER_TABLE_NAME
        + " um ON dm.user_id = um.user_id "
        + "WHERE mm.metalake_name = #{metalakeName} "
        + "AND um.user_name = #{userName}"
        + "</when>"
        + "<otherwise>"
        + "WHERE mm.metalake_name = #{metalakeName} "
        + " AND dm.user_id = "
        + MOCK_ANONYMOUS_USER_ID
        + "</otherwise>"
        + "</choose>"
        + " AND dm.created_time &gt;= #{startTimestamp, jdbcType=TIMESTAMP}"
        + " AND dm.created_time &lt;= #{endTimestamp, jdbcType=TIMESTAMP}"
        + " <if test='metricNames != null and metricNames.length > 0'>"
        + " AND dm.metric_name IN "
        + "<foreach item='name' collection='metricNames' open='(' separator=',' close=')'>"
        + "#{name}"
        + "</foreach>"
        + "</if>"
        + ") AS ranked_metrics WHERE rn = 1"
        + "</script>";
  }

  public String getAssetWithOwnerCount(@Param("metalakeName") String metalakeName) {
    return "SELECT COUNT(1) FROM "
        + OwnerMetaMapper.OWNER_TABLE_NAME
        + " o JOIN "
        + MetalakeMetaMapper.TABLE_NAME
        + " m ON o.metalake_id = m.metalake_id "
        + "WHERE o.deleted_at = 0 "
        + "AND m.deleted_at = 0 "
        + "AND m.metalake_name = #{metalakeName} "
        + "AND o.metadata_object_type IN "
        + "('CATALOG', 'SCHEMA', 'TABLE', 'FILESET', 'TOPIC', 'MODEL')";
  }

  public String insertMetricsData(
      @Param("metalakeName") String metalakeName,
      @Param("userName") String userName,
      @Param("metrics") List<MetricPO> metrics,
      @Param("enableAuthorization") boolean enableAuthorization,
      @Param("forMetalakeOwner") boolean forMetalakeOwner) {
    return "<script>"
        + "INSERT INTO "
        + METRICS_TABLE_NAME
        + " (metalake_id, user_id, metric_name, metric_value, created_time)"
        + " VALUES "
        + "<foreach item='metric' collection='metrics' separator=','>"
        + "("
        + "(SELECT metalake_id FROM "
        + MetalakeMetaMapper.TABLE_NAME
        + " WHERE metalake_name = #{metalakeName} AND deleted_at = 0), "
        + "<choose>"
        + "<when test='forMetalakeOwner == true'>"
        + MOCK_METALAKE_OWNER_ID
        + ", "
        + "</when>"
        + "<when test='enableAuthorization == true'>"
        + "(SELECT user_id FROM "
        + UserRoleRelMapper.USER_TABLE_NAME
        + " WHERE user_name = #{userName} AND metalake_id = "
        + "(SELECT metalake_id FROM "
        + MetalakeMetaMapper.TABLE_NAME
        + " WHERE metalake_name = #{metalakeName} AND deleted_at = 0) "
        + "AND deleted_at = 0), "
        + "</when>"
        + "<otherwise>"
        + MOCK_ANONYMOUS_USER_ID
        + ", "
        + "</otherwise>"
        + "</choose>"
        + "#{metric.metricName}, "
        + "#{metric.metricValue}, "
        + "#{metric.createdTime, jdbcType=TIMESTAMP}"
        + ")"
        + "</foreach>"
        + "</script>";
  }

  public String cleanInvalidMetrics() {
    return "DELETE FROM "
        + METRICS_TABLE_NAME
        + " WHERE metalake_id NOT IN (SELECT metalake_id FROM "
        + MetalakeMetaMapper.TABLE_NAME
        + " WHERE deleted_at = 0) "
        + "OR (user_id != "
        + MOCK_ANONYMOUS_USER_ID
        + " AND user_id NOT IN (SELECT user_id FROM "
        + UserRoleRelMapper.USER_TABLE_NAME
        + " WHERE deleted_at = 0))";
  }

  public String cleanMetricsByTimestamp(@Param("oldestTimestamp") Timestamp oldestTimestamp) {
    return "DELETE FROM "
        + METRICS_TABLE_NAME
        + " WHERE created_time < #{oldestTimestamp, jdbcType=TIMESTAMP}";
  }
}
