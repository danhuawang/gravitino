/*
 * Copyright 2026 Datastrato Inc.
 */

package com.datastrato.gravitino.scim.v2.storage.mapper.provider.base;

import com.datastrato.gravitino.scim.v2.storage.mapper.ScimGroupMetaMapper;
import com.datastrato.gravitino.scim.v2.storage.mapper.ScimUserGroupRelMapper;
import com.datastrato.gravitino.scim.v2.storage.mapper.ScimUserMetaMapper;
import java.util.List;
import org.apache.ibatis.annotations.Param;

/** Base SQL provider for SCIM v2 user-group membership statements (MySQL default). */
public class ScimUserGroupRelBaseSQLProvider {

  public String selectMembersByGroupId(@Param("groupId") long groupId) {
    return "SELECT u.user_id as userId, u.user_name as userName, u.external_id as externalId"
        + " FROM "
        + ScimUserGroupRelMapper.SCIM_USER_GROUP_REL_TABLE_NAME
        + " r JOIN "
        + ScimUserMetaMapper.TABLE_NAME
        + " u ON u.user_id = r.user_id AND u.deleted_at = 0"
        + " WHERE r.deleted_at = 0 AND r.group_id = #{groupId}";
  }

  public String selectGroupNamesByUsername(@Param("username") String username) {
    return "SELECT g.group_name FROM "
        + ScimUserMetaMapper.TABLE_NAME
        + " u JOIN "
        + ScimUserGroupRelMapper.SCIM_USER_GROUP_REL_TABLE_NAME
        + " r ON r.user_id = u.user_id AND r.deleted_at = 0 JOIN "
        + ScimGroupMetaMapper.TABLE_NAME
        + " g ON g.group_id = r.group_id AND g.deleted_at = 0"
        + " WHERE u.user_name = #{username} AND u.deleted_at = 0 AND u.enabled = 1"
        + " AND r.deleted_at = 0 ORDER BY g.group_name";
  }

  public String insertMemberships(
      @Param("groupId") long groupId,
      @Param("userIds") List<Long> userIds,
      @Param("currentVersion") Long currentVersion,
      @Param("lastVersion") Long lastVersion) {
    return "<script><if test='userIds != null and userIds.size() > 0'>INSERT INTO "
        + ScimUserGroupRelMapper.SCIM_USER_GROUP_REL_TABLE_NAME
        + " (user_id, group_id, current_version, last_version, deleted_at)"
        + " SELECT u.user_id, #{groupId}, #{currentVersion}, #{lastVersion}, 0 FROM "
        + ScimUserMetaMapper.TABLE_NAME
        + " u WHERE u.deleted_at = 0 AND u.user_id IN ("
        + "<foreach collection='userIds' item='userId' separator=','>#{userId}</foreach>)"
        + " ON DUPLICATE KEY UPDATE user_id = VALUES(user_id), group_id = VALUES(group_id),"
        + " current_version = VALUES(current_version), last_version = VALUES(last_version),"
        + " deleted_at = VALUES(deleted_at)</if></script>";
  }

  public String softDeleteMembersByUserId(@Param("userId") long userId) {
    return "UPDATE "
        + ScimUserGroupRelMapper.SCIM_USER_GROUP_REL_TABLE_NAME
        + " SET deleted_at = "
        + softDeleteTimestampExpression()
        + " WHERE deleted_at = 0 AND user_id = #{userId}";
  }

  public String softDeleteMembersByGroupAndUserIds(
      @Param("groupId") long groupId, @Param("userIds") List<Long> userIds) {
    return "<script><if test='userIds != null and userIds.size() > 0'>UPDATE "
        + ScimUserGroupRelMapper.SCIM_USER_GROUP_REL_TABLE_NAME
        + " SET deleted_at = "
        + softDeleteTimestampExpression()
        + " WHERE deleted_at = 0 AND group_id = #{groupId} AND user_id IN ("
        + "<foreach collection='userIds' item='userId' separator=','>#{userId}</foreach>)"
        + "</if></script>";
  }

  public String softDeleteMembersByGroupId(@Param("groupId") long groupId) {
    return "UPDATE "
        + ScimUserGroupRelMapper.SCIM_USER_GROUP_REL_TABLE_NAME
        + " SET deleted_at = "
        + softDeleteTimestampExpression()
        + " WHERE deleted_at = 0 AND group_id = #{groupId}";
  }

  public String softDeleteOrphanMemberships() {
    return "UPDATE "
        + ScimUserGroupRelMapper.SCIM_USER_GROUP_REL_TABLE_NAME
        + " r SET deleted_at = "
        + softDeleteTimestampExpression()
        + " WHERE r.deleted_at = 0 AND (NOT EXISTS (SELECT 1 FROM "
        + ScimUserMetaMapper.TABLE_NAME
        + " u WHERE u.user_id = r.user_id AND u.deleted_at = 0)"
        + " OR NOT EXISTS (SELECT 1 FROM "
        + ScimGroupMetaMapper.TABLE_NAME
        + " g WHERE g.group_id = r.group_id AND g.deleted_at = 0))";
  }

  public String updateMemberUserId(
      @Param("groupId") long groupId,
      @Param("oldUserId") long oldUserId,
      @Param("newUserId") long newUserId,
      @Param("currentVersion") Long currentVersion,
      @Param("lastVersion") Long lastVersion) {
    return "UPDATE "
        + ScimUserGroupRelMapper.SCIM_USER_GROUP_REL_TABLE_NAME
        + " r INNER JOIN "
        + ScimUserMetaMapper.TABLE_NAME
        + " u_new ON u_new.user_id = #{newUserId} AND u_new.deleted_at = 0"
        + " SET r.user_id = #{newUserId}, r.current_version = #{currentVersion},"
        + " r.last_version = #{lastVersion} WHERE r.deleted_at = 0 AND r.group_id = #{groupId}"
        + " AND r.user_id = #{oldUserId} AND NOT EXISTS (SELECT 1 FROM "
        + ScimUserGroupRelMapper.SCIM_USER_GROUP_REL_TABLE_NAME
        + " r2 WHERE r2.group_id = r.group_id AND r2.user_id = #{newUserId} AND r2.deleted_at = 0)";
  }

  public String deleteByLegacyTimeline(
      @Param("legacyTimeline") Long legacyTimeline, @Param("limit") int limit) {
    return "DELETE FROM "
        + ScimUserGroupRelMapper.SCIM_USER_GROUP_REL_TABLE_NAME
        + " WHERE deleted_at > 0 AND deleted_at < #{legacyTimeline} LIMIT #{limit}";
  }

  protected String softDeleteTimestampExpression() {
    return "(UNIX_TIMESTAMP() * 1000.0) + EXTRACT(MICROSECOND FROM CURRENT_TIMESTAMP(3)) / 1000";
  }
}
