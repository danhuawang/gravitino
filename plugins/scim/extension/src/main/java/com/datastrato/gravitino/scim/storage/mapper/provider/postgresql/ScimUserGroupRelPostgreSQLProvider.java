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
      @Param("groupExternalId") String groupExternalId,
      @Param("userExternalIds") List<String> userExternalIds,
      @Param("auditInfo") String auditInfo,
      @Param("currentVersion") Long currentVersion,
      @Param("lastVersion") Long lastVersion) {
    return "<script>"
        + "<if test='userExternalIds != null and userExternalIds.size() > 0'>"
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
        + " g ON g.metalake_id = mm.metalake_id AND g.external_id = #{groupExternalId}"
        + " AND g.deleted_at = 0"
        + " INNER JOIN "
        + USER_TABLE_NAME
        + " u ON u.metalake_id = mm.metalake_id AND u.deleted_at = 0"
        + " WHERE mm.metalake_name = #{metalakeName}"
        + " AND u.external_id IN ("
        + "<foreach collection='userExternalIds' item='userExternalId' separator=','>"
        + "#{userExternalId}"
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
  public String softDeleteMembersByUserExternalId(
      @Param("metalakeName") String metalakeName, @Param("userExternalId") String userExternalId) {
    return "UPDATE "
        + SCIM_USER_GROUP_REL_TABLE_NAME
        + " r SET deleted_at = "
        + softDeleteTimestampExpression()
        + " WHERE r.deleted_at = 0"
        + " AND EXISTS ("
        + " SELECT 1 FROM "
        + TABLE_NAME
        + " mm INNER JOIN "
        + USER_TABLE_NAME
        + " u ON u.metalake_id = mm.metalake_id AND u.external_id = #{userExternalId}"
        + " WHERE mm.metalake_name = #{metalakeName}"
        + " AND r.metalake_id = mm.metalake_id"
        + " AND r.user_id = u.user_id"
        + " )";
  }

  @Override
  public String softDeleteMembersByGroupAndUserExternalIds(
      @Param("metalakeName") String metalakeName,
      @Param("groupExternalId") String groupExternalId,
      @Param("userExternalIds") List<String> userExternalIds) {
    return "<script>"
        + "<if test='userExternalIds != null and userExternalIds.size() > 0'>"
        + "UPDATE "
        + SCIM_USER_GROUP_REL_TABLE_NAME
        + " r SET deleted_at = "
        + softDeleteTimestampExpression()
        + " WHERE r.deleted_at = 0"
        + " AND EXISTS ("
        + " SELECT 1 FROM "
        + TABLE_NAME
        + " mm INNER JOIN "
        + GROUP_TABLE_NAME
        + " g ON g.metalake_id = mm.metalake_id AND g.external_id = #{groupExternalId}"
        + " WHERE mm.metalake_name = #{metalakeName}"
        + " AND r.metalake_id = mm.metalake_id"
        + " AND r.group_id = g.group_id"
        + " )"
        + " AND r.user_id IN ("
        + " SELECT u.user_id FROM "
        + USER_TABLE_NAME
        + " u INNER JOIN "
        + TABLE_NAME
        + " mm ON u.metalake_id = mm.metalake_id"
        + " WHERE mm.metalake_name = #{metalakeName}"
        + " AND u.external_id IN ("
        + "<foreach collection='userExternalIds' item='userExternalId' separator=','>"
        + "#{userExternalId}"
        + "</foreach>"
        + ") )"
        + "</if>"
        + "</script>";
  }

  @Override
  public String softDeleteMembersByGroupExternalId(
      @Param("metalakeName") String metalakeName,
      @Param("groupExternalId") String groupExternalId) {
    return "UPDATE "
        + SCIM_USER_GROUP_REL_TABLE_NAME
        + " r SET deleted_at = "
        + softDeleteTimestampExpression()
        + " WHERE r.deleted_at = 0"
        + " AND EXISTS ("
        + " SELECT 1 FROM "
        + TABLE_NAME
        + " mm INNER JOIN "
        + GROUP_TABLE_NAME
        + " g ON g.metalake_id = mm.metalake_id AND g.external_id = #{groupExternalId}"
        + " WHERE mm.metalake_name = #{metalakeName}"
        + " AND r.metalake_id = mm.metalake_id"
        + " AND r.group_id = g.group_id"
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
