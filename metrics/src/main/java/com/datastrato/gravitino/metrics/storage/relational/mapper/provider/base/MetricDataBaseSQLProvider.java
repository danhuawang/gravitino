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

package com.datastrato.gravitino.metrics.storage.relational.mapper.provider.base;

import static com.datastrato.gravitino.metrics.MetricsCollector.MOCK_USER_ID_FOR_DISABLE_AUTHZ;
import static com.datastrato.gravitino.metrics.MetricsCollector.MOCK_USER_ID_FOR_METALAKE_OWNER;
import static com.datastrato.gravitino.metrics.storage.relational.mapper.MetricDataMapper.METRICS_TABLE_NAME;

import com.datastrato.gravitino.metrics.storage.relational.MetricPO;
import java.sql.Timestamp;
import java.util.List;
import java.util.Set;
import org.apache.gravitino.storage.relational.mapper.GroupMetaMapper;
import org.apache.gravitino.storage.relational.mapper.MetalakeMetaMapper;
import org.apache.gravitino.storage.relational.mapper.OwnerMetaMapper;
import org.apache.gravitino.storage.relational.mapper.SecurableObjectMapper;
import org.apache.gravitino.storage.relational.mapper.TagMetaMapper;
import org.apache.gravitino.storage.relational.mapper.TagMetadataObjectRelMapper;
import org.apache.gravitino.storage.relational.mapper.UserMetaMapper;
import org.apache.gravitino.storage.relational.mapper.UserRoleRelMapper;
import org.apache.ibatis.annotations.Param;

public class MetricDataBaseSQLProvider {

  public String getMetricPOsByUserIdMetricNamesAndTimestamp(
      @Param("metalakeId") long metalakeId,
      @Param("userId") long userId,
      @Param("metricNames") String[] metricNames,
      @Param("startTimestamp") Timestamp startTimestamp,
      @Param("endTimestamp") Timestamp endTimestamp) {
    return "<script>"
        + "SELECT metric_name as metricName, created_time as createdTime, metric_value as metricValue"
        + " FROM "
        + METRICS_TABLE_NAME
        + " dm WHERE dm.metalake_id = #{metalakeId}"
        + " AND dm.user_id = #{userId}"
        + " AND dm.created_time &gt;= #{startTimestamp, jdbcType=TIMESTAMP}"
        + " AND dm.created_time &lt;= #{endTimestamp, jdbcType=TIMESTAMP}"
        + " <if test='metricNames != null and metricNames.length > 0'>"
        + " AND dm.metric_name IN "
        + "<foreach item='name' collection='metricNames' open='(' separator=',' close=')'>"
        + "#{name}"
        + "</foreach>"
        + "</if>"
        + "</script>";
  }

  public String getTagCountByMetalakeId(@Param("metalakeId") long metalakeId) {
    return "SELECT COUNT(1) FROM "
        + TagMetaMapper.TAG_TABLE_NAME
        + " WHERE metalake_id = #{metalakeId} AND deleted_at = 0";
  }

  public String listUserRoleRelsByUserIds(@Param("userIds") Set<Long> userIds) {
    return "<script>"
        + "SELECT user_id as userId, role_id as roleId, audit_info as auditInfo,"
        + " current_version as currentVersion, last_version as lastVersion, deleted_at as deletedAt"
        + " FROM "
        + UserRoleRelMapper.USER_ROLE_RELATION_TABLE_NAME
        + " WHERE deleted_at = 0"
        + " AND user_id IN "
        + "<foreach item='userId' collection='userIds' open='(' separator=',' close=')'>"
        + "#{userId}"
        + "</foreach>"
        + "</script>";
  }

  public String listOwnerNameRelsByMetalakeId(@Param("metalakeId") long metalakeId) {
    return "SELECT om.metalake_id as metalakeId, "
        + "CASE om.owner_type WHEN 'USER' THEN um.user_name WHEN 'GROUP' THEN gm.group_name END AS ownerName, "
        + "om.owner_type as ownerType, om.metadata_object_id as metadataObjectId, om.metadata_object_type as metadataObjectType"
        + " FROM "
        + OwnerMetaMapper.OWNER_TABLE_NAME
        + " om"
        + " LEFT JOIN "
        + UserMetaMapper.USER_TABLE_NAME
        + " um ON om.owner_id = um.user_id AND om.owner_type = 'USER'"
        + " LEFT JOIN "
        + GroupMetaMapper.GROUP_TABLE_NAME
        + " gm ON om.owner_id = gm.group_id AND om.owner_type = 'GROUP'"
        + " WHERE om.metalake_id = #{metalakeId} AND om.deleted_at = 0";
  }

  public String listSecurableObjectsByRoleIds(@Param("roleIds") Set<Long> roleIds) {
    return "<script>"
        + "SELECT role_id as roleId, metadata_object_id as metadataObjectId,"
        + " type, privilege_names as privilegeNames, privilege_conditions as privilegeConditions,"
        + " current_version as currentVersion, last_version as lastVersion, deleted_at as deletedAt"
        + " FROM "
        + SecurableObjectMapper.SECURABLE_OBJECT_TABLE_NAME
        + " WHERE deleted_at = 0 AND role_id IN "
        + "<foreach item='roleId' collection='roleIds' open='(' separator=',' close=')'>"
        + "#{roleId}"
        + "</foreach>"
        + "</script>";
  }

  public String listTagNameMetadataObjectRelsByMetalakeId(@Param("metalakeId") long metalakeId) {
    return "SELECT tm.tag_name as tagName, trm.metadata_object_id as objectId"
        + " FROM "
        + TagMetaMapper.TAG_TABLE_NAME
        + " tm"
        + " INNER JOIN "
        + TagMetadataObjectRelMapper.TAG_METADATA_OBJECT_RELATION_TABLE_NAME
        + " trm ON tm.tag_id = trm.tag_id"
        + " WHERE tm.metalake_id = #{metalakeId} AND tm.deleted_at = 0 AND trm.deleted_at = 0";
  }

  public String insertMetricsData(
      @Param("metalakeId") long metalakeId,
      @Param("userId") long userId,
      @Param("metrics") List<MetricPO> metrics) {
    return "<script>"
        + "INSERT INTO "
        + METRICS_TABLE_NAME
        + " (metalake_id, user_id, metric_name, metric_value, created_time)"
        + " VALUES "
        + "<foreach item='metric' collection='metrics' separator=','>"
        + "("
        + "#{metalakeId}, "
        + "#{userId}, "
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
        + "OR (user_id NOT IN ("
        + MOCK_USER_ID_FOR_DISABLE_AUTHZ
        + ", "
        + MOCK_USER_ID_FOR_METALAKE_OWNER
        + ") AND user_id NOT IN (SELECT user_id FROM "
        + UserRoleRelMapper.USER_TABLE_NAME
        + " WHERE deleted_at = 0))";
  }

  public String cleanMetricsByTimestamp(@Param("oldestTimestamp") Timestamp oldestTimestamp) {
    return "DELETE FROM "
        + METRICS_TABLE_NAME
        + " WHERE created_time < #{oldestTimestamp, jdbcType=TIMESTAMP}";
  }
}
