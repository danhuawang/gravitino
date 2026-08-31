/*
 * Copyright 2024 Datastrato Pvt Ltd.
 * This software is licensed under the Apache License version 2.
 */

package com.datastrato.gravitino.metrics.storage.relational.mapper.provider.base;

import static com.datastrato.gravitino.metrics.MetricsCollector.MOCK_USER_ID_FOR_DISABLE_AUTHZ;
import static com.datastrato.gravitino.metrics.MetricsCollector.MOCK_USER_ID_FOR_METALAKE_OWNER;
import static com.datastrato.gravitino.metrics.storage.relational.mapper.MetricDataMapper.CURRENT_METRICS_TABLE_NAME;
import static com.datastrato.gravitino.metrics.storage.relational.mapper.MetricDataMapper.DIRTY_METRICS_TABLE_NAME;
import static com.datastrato.gravitino.metrics.storage.relational.mapper.MetricDataMapper.METRICS_TABLE_NAME;

import com.datastrato.gravitino.metrics.storage.relational.MetricPO;
import java.sql.Timestamp;
import java.util.List;
import java.util.Set;
import org.apache.gravitino.storage.relational.mapper.GroupMetaMapper;
import org.apache.gravitino.storage.relational.mapper.MetalakeMetaMapper;
import org.apache.gravitino.storage.relational.mapper.OwnerMetaMapper;
import org.apache.gravitino.storage.relational.mapper.PolicyMetaMapper;
import org.apache.gravitino.storage.relational.mapper.PolicyMetadataObjectRelMapper;
import org.apache.gravitino.storage.relational.mapper.PolicyVersionMapper;
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
        + "SELECT metric_name as metricName, created_time as createdTime,"
        + " metric_value as metricValue, metric_state as metricState,"
        + " metric_message as metricMessage"
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
        + " ORDER BY dm.metric_name, dm.created_time"
        + "</script>";
  }

  /**
   * Builds the current metric query for MySQL-compatible backends.
   *
   * @param metalakeId metalake ID
   * @param userId persisted user ID
   * @param metricNames optional metric-name filter
   * @param startTimestamp inclusive start time
   * @param endTimestamp inclusive end time
   * @return current metric query
   */
  public String getCurrentMetricPOsByUserIdMetricNamesAndTimestamp(
      @Param("metalakeId") long metalakeId,
      @Param("userId") long userId,
      @Param("metricNames") String[] metricNames,
      @Param("startTimestamp") Timestamp startTimestamp,
      @Param("endTimestamp") Timestamp endTimestamp) {
    return "<script>"
        + "SELECT metric_name as metricName, updated_time as createdTime,"
        + " metric_value as metricValue, metric_state as metricState,"
        + " metric_message as metricMessage"
        + " FROM "
        + CURRENT_METRICS_TABLE_NAME
        + " dmc WHERE dmc.metalake_id = #{metalakeId}"
        + " AND dmc.user_id = #{userId}"
        + " AND dmc.updated_time &gt;= #{startTimestamp, jdbcType=TIMESTAMP}"
        + " AND dmc.updated_time &lt;= #{endTimestamp, jdbcType=TIMESTAMP}"
        + " <if test='metricNames != null and metricNames.length > 0'>"
        + " AND dmc.metric_name IN "
        + "<foreach item='name' collection='metricNames' open='(' separator=',' close=')'>"
        + "#{name}"
        + "</foreach>"
        + "</if>"
        + " ORDER BY dmc.metric_name, dmc.updated_time"
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

  /**
   * Builds the query for metadata objects covered by a current, enabled policy.
   *
   * @param metalakeId metalake ID
   * @return enabled policy relation query
   */
  public String listEnabledPolicyMetadataObjectIdsByMetalakeId(
      @Param("metalakeId") long metalakeId) {
    return "SELECT DISTINCT prm.metadata_object_id"
        + " FROM "
        + PolicyMetadataObjectRelMapper.POLICY_METADATA_OBJECT_RELATION_TABLE_NAME
        + " prm JOIN "
        + PolicyMetaMapper.POLICY_META_TABLE_NAME
        + " pm ON prm.policy_id = pm.policy_id JOIN "
        + PolicyVersionMapper.POLICY_VERSION_TABLE_NAME
        + " pvi ON pm.policy_id = pvi.policy_id AND pm.current_version = pvi.version"
        + " WHERE pm.metalake_id = #{metalakeId} AND prm.deleted_at = 0"
        + " AND pm.deleted_at = 0 AND pvi.deleted_at = 0 AND pvi.enabled = TRUE";
  }

  public String insertMetricsData(
      @Param("metalakeId") long metalakeId,
      @Param("userId") long userId,
      @Param("metrics") List<MetricPO> metrics) {
    return "<script>"
        + "INSERT INTO "
        + METRICS_TABLE_NAME
        + " (metalake_id, user_id, metric_name, metric_value, metric_state, metric_message,"
        + " created_time)"
        + " VALUES "
        + "<foreach item='metric' collection='metrics' separator=','>"
        + "("
        + "#{metalakeId}, "
        + "#{userId}, "
        + "#{metric.metricName}, "
        + "#{metric.metricValue, jdbcType=DOUBLE}, "
        + "#{metric.metricState, jdbcType=VARCHAR}, "
        + "#{metric.metricMessage, jdbcType=VARCHAR}, "
        + "#{metric.createdTime, jdbcType=TIMESTAMP}"
        + ")"
        + "</foreach>"
        + "</script>";
  }

  /**
   * Builds a batch insert for complete history metric rows.
   *
   * @param metrics history metric rows
   * @return batch insert statement
   */
  public String insertMetricsDataBatch(@Param("metrics") List<MetricPO> metrics) {
    return batchInsertMetrics(METRICS_TABLE_NAME, "created_time");
  }

  /**
   * Builds the statement that removes every current metric for one metalake.
   *
   * @param metalakeId metalake ID
   * @return current metric delete statement
   */
  public String deleteCurrentMetrics(@Param("metalakeId") long metalakeId) {
    return "DELETE FROM " + CURRENT_METRICS_TABLE_NAME + " WHERE metalake_id = #{metalakeId}";
  }

  /**
   * Builds a batch insert for current metric rows.
   *
   * @param metrics current metric rows
   * @return batch insert statement
   */
  public String insertCurrentMetrics(@Param("metrics") List<MetricPO> metrics) {
    return batchInsertMetrics(CURRENT_METRICS_TABLE_NAME, "updated_time");
  }

  /**
   * Builds the MySQL-compatible atomic dirty-marker upsert.
   *
   * @param metalakeId metalake ID
   * @param eventTime metadata event time
   * @return dirty-marker upsert statement
   */
  public String markMetalakeDirty(
      @Param("metalakeId") long metalakeId, @Param("eventTime") Timestamp eventTime) {
    return "INSERT INTO "
        + DIRTY_METRICS_TABLE_NAME
        + " (metalake_id, revision, first_dirty_at, last_event_at, retry_count, retry_after, last_error)"
        + " VALUES (#{metalakeId}, 1, #{eventTime, jdbcType=TIMESTAMP},"
        + " #{eventTime, jdbcType=TIMESTAMP}, 0, NULL, NULL)"
        + " ON DUPLICATE KEY UPDATE revision = revision + 1,"
        + " first_dirty_at = LEAST(first_dirty_at, VALUES(first_dirty_at)),"
        + " last_event_at = GREATEST(last_event_at, VALUES(last_event_at)), retry_count = 0,"
        + " retry_after = NULL, last_error = NULL";
  }

  /**
   * Builds the query for dirty markers whose debounce or retry deadline is due.
   *
   * @param quietCutoff inclusive quiet-period cutoff
   * @param maxDebounceCutoff inclusive maximum-debounce cutoff
   * @param now current time
   * @return due dirty-marker query
   */
  public String listDueDirtyMetalakes(
      @Param("quietCutoff") Timestamp quietCutoff,
      @Param("maxDebounceCutoff") Timestamp maxDebounceCutoff,
      @Param("now") Timestamp now) {
    return dirtySelectColumns()
        + " WHERE (retry_after IS NOT NULL AND retry_after <= #{now, jdbcType=TIMESTAMP})"
        + " OR (retry_after IS NULL AND (last_event_at <= #{quietCutoff, jdbcType=TIMESTAMP}"
        + " OR first_dirty_at <= #{maxDebounceCutoff, jdbcType=TIMESTAMP}))"
        + " ORDER BY first_dirty_at";
  }

  /**
   * Builds the lookup for one metalake dirty marker.
   *
   * @param metalakeId metalake ID
   * @return dirty-marker lookup query
   */
  public String getDirtyMetalake(@Param("metalakeId") long metalakeId) {
    return dirtySelectColumns() + " WHERE metalake_id = #{metalakeId}";
  }

  /**
   * Builds the compare-and-delete statement for a dirty revision.
   *
   * @param metalakeId metalake ID
   * @param revision expected revision
   * @return compare-and-delete statement
   */
  public String deleteDirtyIfRevision(
      @Param("metalakeId") long metalakeId, @Param("revision") long revision) {
    return "DELETE FROM "
        + DIRTY_METRICS_TABLE_NAME
        + " WHERE metalake_id = #{metalakeId} AND revision = #{revision}";
  }

  /**
   * Builds the compare-and-update statement for retry state.
   *
   * @param metalakeId metalake ID
   * @param revision expected revision
   * @param retryCount consecutive failure count
   * @param retryAfter earliest retry time
   * @param lastError truncated failure message
   * @return compare-and-update statement
   */
  public String markRetryIfRevision(
      @Param("metalakeId") long metalakeId,
      @Param("revision") long revision,
      @Param("retryCount") int retryCount,
      @Param("retryAfter") Timestamp retryAfter,
      @Param("lastError") String lastError) {
    return "UPDATE "
        + DIRTY_METRICS_TABLE_NAME
        + " SET retry_count = #{retryCount}, retry_after = #{retryAfter, jdbcType=TIMESTAMP},"
        + " last_error = #{lastError, jdbcType=VARCHAR}"
        + " WHERE metalake_id = #{metalakeId} AND revision = #{revision}";
  }

  public String cleanInvalidMetrics() {
    return cleanInvalidMetricsFromTable(METRICS_TABLE_NAME);
  }

  /**
   * Builds cleanup SQL for invalid current metrics.
   *
   * @return invalid current metric cleanup statement
   */
  public String cleanInvalidCurrentMetrics() {
    return cleanInvalidMetricsFromTable(CURRENT_METRICS_TABLE_NAME);
  }

  /**
   * Builds cleanup SQL for dirty markers whose metalake is invalid.
   *
   * @return invalid dirty-marker cleanup statement
   */
  public String cleanInvalidDirtyMetrics() {
    return "DELETE FROM "
        + DIRTY_METRICS_TABLE_NAME
        + " WHERE metalake_id NOT IN (SELECT metalake_id FROM "
        + MetalakeMetaMapper.TABLE_NAME
        + " WHERE deleted_at = 0)";
  }

  private String batchInsertMetrics(String tableName, String timestampColumn) {
    return "<script>"
        + "INSERT INTO "
        + tableName
        + " (metalake_id, user_id, metric_name, metric_value, metric_state, metric_message, "
        + timestampColumn
        + ") VALUES "
        + "<foreach item='metric' collection='metrics' separator=','>"
        + "(#{metric.metalakeId}, #{metric.userId}, #{metric.metricName},"
        + " #{metric.metricValue, jdbcType=DOUBLE}, #{metric.metricState, jdbcType=VARCHAR},"
        + " #{metric.metricMessage, jdbcType=VARCHAR},"
        + " #{metric.createdTime, jdbcType=TIMESTAMP})"
        + "</foreach>"
        + "</script>";
  }

  private String dirtySelectColumns() {
    return "SELECT metalake_id as metalakeId, revision, first_dirty_at as firstDirtyAt,"
        + " last_event_at as lastEventAt, retry_count as retryCount,"
        + " retry_after as retryAfter, last_error as lastError FROM "
        + DIRTY_METRICS_TABLE_NAME;
  }

  private String cleanInvalidMetricsFromTable(String tableName) {
    return "DELETE FROM "
        + tableName
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
