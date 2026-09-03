/*
 * Copyright 2026 Datastrato Inc.
 */

package com.datastrato.gravitino.scim.storage.mapper.provider.base;

import com.datastrato.gravitino.scim.storage.mapper.ScimGroupMetaMapper;
import com.datastrato.gravitino.scim.storage.po.ScimGroupMetaPO;
import org.apache.ibatis.annotations.Param;

/** Base SQL provider for SCIM group metadata statements (MySQL default). */
public class ScimGroupMetaBaseSQLProvider {

  private static final String SELECT_COLUMNS =
      " group_id as groupId, group_name as groupName, group_comment as groupComment,"
          + " external_id as externalId, current_version as currentVersion,"
          + " last_version as lastVersion, deleted_at as deletedAt";

  public String insert(@Param("groupMeta") ScimGroupMetaPO groupMeta) {
    return "INSERT INTO "
        + ScimGroupMetaMapper.TABLE_NAME
        + " (group_id, group_name, group_comment, external_id, current_version, last_version,"
        + " deleted_at) VALUES (#{groupMeta.groupId}, #{groupMeta.groupName},"
        + " #{groupMeta.groupComment}, #{groupMeta.externalId}, #{groupMeta.currentVersion},"
        + " #{groupMeta.lastVersion}, #{groupMeta.deletedAt})";
  }

  public String selectByExternalId(@Param("externalId") String externalId) {
    return "SELECT"
        + SELECT_COLUMNS
        + " FROM "
        + ScimGroupMetaMapper.TABLE_NAME
        + " WHERE external_id = #{externalId} AND deleted_at = 0";
  }

  public String selectByGroupName(@Param("groupName") String groupName) {
    return "SELECT"
        + SELECT_COLUMNS
        + " FROM "
        + ScimGroupMetaMapper.TABLE_NAME
        + " WHERE group_name = #{groupName} AND deleted_at = 0";
  }

  /**
   * Builds a case-insensitive lookup by {@code group_name}.
   *
   * @param groupName group name to match ignoring case
   * @return SELECT SQL
   */
  public String selectByGroupNameIgnoreCase(@Param("groupName") String groupName) {
    return "SELECT"
        + SELECT_COLUMNS
        + " FROM "
        + ScimGroupMetaMapper.TABLE_NAME
        + " WHERE LOWER(group_name) = LOWER(#{groupName}) AND deleted_at = 0";
  }

  public String selectByGroupId(@Param("groupId") long groupId) {
    return "SELECT"
        + SELECT_COLUMNS
        + " FROM "
        + ScimGroupMetaMapper.TABLE_NAME
        + " WHERE group_id = #{groupId} AND deleted_at = 0";
  }

  public String listGroups(@Param("offset") int offset, @Param("limit") int limit) {
    return "SELECT"
        + SELECT_COLUMNS
        + " FROM "
        + ScimGroupMetaMapper.TABLE_NAME
        + " WHERE deleted_at = 0 ORDER BY group_name ASC LIMIT #{limit} OFFSET #{offset}";
  }

  public String countGroups() {
    return "SELECT COUNT(1) FROM " + ScimGroupMetaMapper.TABLE_NAME + " WHERE deleted_at = 0";
  }

  public String updateExternalId(
      @Param("groupId") long groupId, @Param("externalId") String externalId) {
    return "UPDATE "
        + ScimGroupMetaMapper.TABLE_NAME
        + " SET external_id = #{externalId}, last_version = current_version,"
        + " current_version = current_version + 1"
        + " WHERE group_id = #{groupId} AND deleted_at = 0";
  }

  public String softDeleteByGroupId(@Param("groupId") long groupId) {
    return "UPDATE "
        + ScimGroupMetaMapper.TABLE_NAME
        + " SET deleted_at = "
        + currentTimeMillisExpression()
        + " WHERE group_id = #{groupId} AND deleted_at = 0";
  }

  public String softDeleteByExternalId(@Param("externalId") String externalId) {
    return "UPDATE "
        + ScimGroupMetaMapper.TABLE_NAME
        + " SET deleted_at = "
        + currentTimeMillisExpression()
        + " WHERE external_id = #{externalId} AND deleted_at = 0";
  }

  public String deleteByLegacyTimeline(
      @Param("legacyTimeline") Long legacyTimeline, @Param("limit") int limit) {
    return "DELETE FROM "
        + ScimGroupMetaMapper.TABLE_NAME
        + " WHERE deleted_at > 0 AND deleted_at < #{legacyTimeline} LIMIT #{limit}";
  }

  protected String currentTimeMillisExpression() {
    return "(UNIX_TIMESTAMP() * 1000.0) + EXTRACT(MICROSECOND FROM CURRENT_TIMESTAMP(3)) / 1000";
  }
}
