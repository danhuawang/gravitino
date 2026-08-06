/*
 * Copyright 2026 Datastrato Pvt Ltd.
 * This software is licensed under the Apache License version 2.
 */

package com.datastrato.gravitino.scim.storage.mapper.provider.postgresql;

import static com.datastrato.gravitino.scim.storage.mapper.ScimUserGroupRelMapper.SCIM_USER_GROUP_REL_TABLE_NAME;
import static org.apache.gravitino.storage.relational.mapper.GroupMetaMapper.GROUP_TABLE_NAME;
import static org.apache.gravitino.storage.relational.mapper.MetalakeMetaMapper.TABLE_NAME;
import static org.apache.gravitino.storage.relational.mapper.UserMetaMapper.USER_TABLE_NAME;

import com.datastrato.gravitino.scim.storage.mapper.provider.base.ScimUserGroupRelBaseSQLProvider;
import java.util.List;
import org.apache.ibatis.annotations.Param;

/** SQL provider for SCIM user-group membership statements on PostgreSQL backends. */
public class ScimUserGroupRelPostgreSQLProvider extends ScimUserGroupRelBaseSQLProvider {

  @Override
  public String insertMemberships(
      @Param("metalakeName") String metalakeName,
      @Param("groupId") long groupId,
      @Param("userIds") List<Long> userIds,
      @Param("auditInfo") String auditInfo,
      @Param("currentVersion") Long currentVersion,
      @Param("lastVersion") Long lastVersion) {
    return "<script>"
        + "<if test='userIds != null and userIds.size() > 0'>"
        + "INSERT INTO "
        + SCIM_USER_GROUP_REL_TABLE_NAME
        + " (metalake_id, user_id, group_id, audit_info, current_version, last_version,"
        + " deleted_at)"
        + " SELECT mm.metalake_id, u.user_id, g.group_id, #{auditInfo}, #{currentVersion},"
        + " #{lastVersion}, 0"
        + " FROM "
        + TABLE_NAME
        + " mm INNER JOIN "
        + GROUP_TABLE_NAME
        + " g ON g.metalake_id = mm.metalake_id AND g.group_id = #{groupId}"
        + " AND g.deleted_at = 0"
        + " INNER JOIN "
        + USER_TABLE_NAME
        + " u ON u.metalake_id = mm.metalake_id AND u.deleted_at = 0"
        + " WHERE mm.metalake_name = #{metalakeName}"
        + " AND u.user_id IN ("
        + "<foreach collection='userIds' item='userId' separator=','>"
        + "#{userId}"
        + "</foreach>"
        + ") "
        + " ON CONFLICT (metalake_id, user_id, group_id, deleted_at) DO UPDATE SET"
        + " metalake_id = EXCLUDED.metalake_id,"
        + " user_id = EXCLUDED.user_id,"
        + " group_id = EXCLUDED.group_id,"
        + " audit_info = EXCLUDED.audit_info,"
        + " current_version = EXCLUDED.current_version,"
        + " last_version = EXCLUDED.last_version,"
        + " deleted_at = EXCLUDED.deleted_at"
        + "</if>"
        + "</script>";
  }

  @Override
  public String softDeleteMembersByUserId(
      @Param("metalakeName") String metalakeName, @Param("userId") long userId) {
    return "UPDATE "
        + SCIM_USER_GROUP_REL_TABLE_NAME
        + " r SET deleted_at = "
        + softDeleteTimestampExpression()
        + " WHERE r.deleted_at = 0"
        + " AND r.user_id = #{userId}"
        + " AND EXISTS ("
        + " SELECT 1 FROM "
        + TABLE_NAME
        + " mm WHERE mm.metalake_name = #{metalakeName}"
        + " AND r.metalake_id = mm.metalake_id"
        + " )";
  }

  @Override
  public String softDeleteMembersByGroupAndUserIds(
      @Param("metalakeName") String metalakeName,
      @Param("groupId") long groupId,
      @Param("userIds") List<Long> userIds) {
    return "<script>"
        + "<if test='userIds != null and userIds.size() > 0'>"
        + "UPDATE "
        + SCIM_USER_GROUP_REL_TABLE_NAME
        + " r SET deleted_at = "
        + softDeleteTimestampExpression()
        + " WHERE r.deleted_at = 0"
        + " AND r.group_id = #{groupId}"
        + " AND r.user_id IN ("
        + "<foreach collection='userIds' item='userId' separator=','>"
        + "#{userId}"
        + "</foreach>"
        + ")"
        + " AND EXISTS ("
        + " SELECT 1 FROM "
        + TABLE_NAME
        + " mm WHERE mm.metalake_name = #{metalakeName}"
        + " AND r.metalake_id = mm.metalake_id"
        + " )"
        + "</if>"
        + "</script>";
  }

  @Override
  public String softDeleteMembersByGroupId(
      @Param("metalakeName") String metalakeName, @Param("groupId") long groupId) {
    return "UPDATE "
        + SCIM_USER_GROUP_REL_TABLE_NAME
        + " r SET deleted_at = "
        + softDeleteTimestampExpression()
        + " WHERE r.deleted_at = 0"
        + " AND r.group_id = #{groupId}"
        + " AND EXISTS ("
        + " SELECT 1 FROM "
        + TABLE_NAME
        + " mm WHERE mm.metalake_name = #{metalakeName}"
        + " AND r.metalake_id = mm.metalake_id"
        + " )";
  }

  @Override
  public String deleteByLegacyTimeline(
      @Param("legacyTimeline") Long legacyTimeline, @Param("limit") int limit) {
    return "DELETE FROM "
        + SCIM_USER_GROUP_REL_TABLE_NAME
        + " WHERE id IN (SELECT id FROM "
        + SCIM_USER_GROUP_REL_TABLE_NAME
        + " WHERE deleted_at > 0 AND deleted_at < #{legacyTimeline} LIMIT #{limit})";
  }

  @Override
  protected String softDeleteTimestampExpression() {
    return "CAST(FLOOR(EXTRACT(EPOCH FROM CURRENT_TIMESTAMP(3)) * 1000) AS BIGINT)";
  }
}
