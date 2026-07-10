/*
 * Copyright 2026 Datastrato Pvt Ltd.
 * This software is licensed under the Apache License version 2.
 */

package com.datastrato.gravitino.scim.storage.mapper.provider.base;

import static com.datastrato.gravitino.scim.storage.mapper.ScimUserGroupRelMapper.SCIM_USER_GROUP_REL_TABLE_NAME;
import static org.apache.gravitino.storage.relational.mapper.GroupMetaMapper.GROUP_TABLE_NAME;
import static org.apache.gravitino.storage.relational.mapper.MetalakeMetaMapper.TABLE_NAME;
import static org.apache.gravitino.storage.relational.mapper.UserMetaMapper.USER_TABLE_NAME;

import java.util.List;
import org.apache.ibatis.annotations.Param;

/** Base SQL provider for SCIM user-group membership statements (MySQL default). */
public class ScimUserGroupRelBaseSQLProvider {

  /**
   * Returns active group members within a metalake, including SCIM id and display name fields.
   *
   * @param metalakeName target metalake name
   * @param groupExternalId SCIM group external id from the URL path
   * @return SQL statement
   */
  public String selectMembersByGroupExternalId(
      @Param("metalakeName") String metalakeName,
      @Param("groupExternalId") String groupExternalId) {
    return "SELECT u.external_id as externalId, u.user_name as userName"
        + " FROM "
        + SCIM_USER_GROUP_REL_TABLE_NAME
        + " r JOIN "
        + TABLE_NAME
        + " mm ON r.metalake_id = mm.metalake_id AND mm.deleted_at = 0"
        + " AND mm.metalake_name = #{metalakeName}"
        + " JOIN "
        + GROUP_TABLE_NAME
        + " g ON g.group_id = r.group_id AND g.metalake_id = r.metalake_id"
        + " AND g.deleted_at = 0 AND g.external_id = #{groupExternalId}"
        + " JOIN "
        + USER_TABLE_NAME
        + " u ON u.user_id = r.user_id AND u.deleted_at = 0"
        + " WHERE r.deleted_at = 0";
  }

  /**
   * Returns group names for a user within a metalake.
   *
   * @param username target username
   * @param metalakeName target metalake name
   * @return SQL statement
   */
  public String selectGroupNamesByUsername(
      @Param("username") String username, @Param("metalakeName") String metalakeName) {
    return "SELECT g.group_name"
        + " FROM "
        + SCIM_USER_GROUP_REL_TABLE_NAME
        + " r JOIN "
        + TABLE_NAME
        + " mm ON r.metalake_id = mm.metalake_id AND mm.deleted_at = 0"
        + " JOIN "
        + GROUP_TABLE_NAME
        + " g ON g.group_id = r.group_id AND g.deleted_at = 0 AND g.metalake_id = r.metalake_id"
        + " JOIN "
        + USER_TABLE_NAME
        + " u ON u.user_id = r.user_id AND u.deleted_at = 0"
        + " WHERE u.user_name = #{username}"
        + " AND mm.metalake_name = #{metalakeName}"
        + " AND r.deleted_at = 0";
  }

  /**
   * Inserts active memberships for a group by resolving SCIM ids from {@code group_meta} and {@code
   * user_meta}.
   *
   * <p>Rows whose user {@code external_id} is missing or not in {@code userExternalIds} are
   * skipped. Rows that already have an active membership are upserted as a no-op via duplicate-key
   * resolution. {@code userExternalIds} must be non-empty; callers should skip the mapper when
   * there are no members to add.
   *
   * @param metalakeName target metalake name
   * @param groupExternalId SCIM group external id from the URL path
   * @param userExternalIds SCIM user external ids from PATCH {@code members[].value}
   * @param auditInfo relation audit info
   * @param currentVersion relation current version
   * @param lastVersion relation last version
   * @return SQL statement
   */
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
        + " ON DUPLICATE KEY UPDATE"
        + " metalake_id = VALUES(metalake_id),"
        + " user_id = VALUES(user_id),"
        + " group_id = VALUES(group_id),"
        + " audit_info = VALUES(audit_info),"
        + " current_version = VALUES(current_version),"
        + " last_version = VALUES(last_version),"
        + " deleted_at = VALUES(deleted_at)"
        + "</if>"
        + "</script>";
  }

  /**
   * Soft-deletes all active memberships for a user identified by SCIM {@code externalId}.
   *
   * @param metalakeName target metalake name
   * @param userExternalId SCIM user {@code externalId}
   * @return SQL statement
   */
  public String softDeleteMembersByUserExternalId(
      @Param("metalakeName") String metalakeName, @Param("userExternalId") String userExternalId) {
    return "UPDATE "
        + SCIM_USER_GROUP_REL_TABLE_NAME
        + " r SET r.deleted_at = "
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

  /**
   * Soft-deletes group memberships for users identified by SCIM {@code externalId}s.
   *
   * @param metalakeName target metalake name
   * @param groupExternalId SCIM group {@code externalId}
   * @param userExternalIds SCIM member ids from PATCH {@code members[].value}; must be non-empty
   * @return SQL statement
   */
  public String softDeleteMembersByGroupAndUserExternalIds(
      @Param("metalakeName") String metalakeName,
      @Param("groupExternalId") String groupExternalId,
      @Param("userExternalIds") List<String> userExternalIds) {
    return "<script>"
        + "<if test='userExternalIds != null and userExternalIds.size() > 0'>"
        + "UPDATE "
        + SCIM_USER_GROUP_REL_TABLE_NAME
        + " r SET r.deleted_at = "
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

  /**
   * Soft-deletes active membership rows whose metalake is missing or already soft-deleted.
   *
   * @return SQL statement
   */
  public String softDeleteMembersByUnavailableMetalake() {
    return "UPDATE "
        + SCIM_USER_GROUP_REL_TABLE_NAME
        + " r SET deleted_at = "
        + softDeleteTimestampExpression()
        + " WHERE r.deleted_at = 0"
        + " AND NOT EXISTS ("
        + " SELECT 1 FROM "
        + TABLE_NAME
        + " m WHERE m.metalake_id = r.metalake_id AND m.deleted_at = 0"
        + " )";
  }

  /**
   * Soft-deletes all active memberships for a group identified by SCIM {@code externalId}.
   *
   * @param metalakeName target metalake name
   * @param groupExternalId SCIM group {@code externalId}
   * @return SQL statement
   */
  public String softDeleteMembersByGroupExternalId(
      @Param("metalakeName") String metalakeName,
      @Param("groupExternalId") String groupExternalId) {
    return "UPDATE "
        + SCIM_USER_GROUP_REL_TABLE_NAME
        + " r SET r.deleted_at = "
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

  public String deleteByLegacyTimeline(
      @Param("legacyTimeline") Long legacyTimeline, @Param("limit") int limit) {
    return "DELETE FROM "
        + SCIM_USER_GROUP_REL_TABLE_NAME
        + " WHERE deleted_at > 0 AND deleted_at < #{legacyTimeline} LIMIT #{limit}";
  }

  protected String softDeleteTimestampExpression() {
    return "(UNIX_TIMESTAMP() * 1000.0) + EXTRACT(MICROSECOND FROM CURRENT_TIMESTAMP(3)) / 1000";
  }
}
