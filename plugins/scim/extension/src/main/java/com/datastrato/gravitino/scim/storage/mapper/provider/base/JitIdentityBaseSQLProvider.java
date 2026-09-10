/*
 * Copyright 2026 Datastrato Inc.
 */

package com.datastrato.gravitino.scim.storage.mapper.provider.base;

import com.datastrato.gravitino.scim.storage.mapper.JitIdentityMapper;
import com.datastrato.gravitino.scim.storage.po.JitGroupPO;
import com.datastrato.gravitino.scim.storage.po.JitUserGroupRelPO;
import com.datastrato.gravitino.scim.storage.po.JitUserPO;
import java.util.List;
import org.apache.ibatis.annotations.Param;

/** Portable SQL provider for global JWT-discovered identities. */
public class JitIdentityBaseSQLProvider {

  private static final String JIT_USER_COLUMNS =
      " user_id as userId, user_name as userName, audit_info as auditInfo,"
          + " current_version as currentVersion, last_version as lastVersion,"
          + " deleted_at as deletedAt";
  private static final String JIT_GROUP_COLUMNS =
      " group_id as groupId, group_name as groupName, audit_info as auditInfo,"
          + " current_version as currentVersion, last_version as lastVersion,"
          + " deleted_at as deletedAt";

  /** Returns SQL that finds an active SCIM user case-insensitively. */
  public String selectScimUserIdByName(@Param("userName") String userName) {
    return "SELECT user_id FROM scim_user_meta"
        + " WHERE LOWER(user_name) = LOWER(#{userName}) AND deleted_at = 0";
  }

  /** Returns SQL that finds active SCIM group names case-insensitively. */
  public String selectScimGroupNamesByNames(@Param("groupNames") List<String> groupNames) {
    return "<script>SELECT group_name FROM scim_group_meta WHERE deleted_at = 0"
        + " AND LOWER(group_name) IN "
        + lowerNameInClause("groupNames", "groupName")
        + "</script>";
  }

  /** Returns SQL that loads an active JIT user by name. */
  public String selectUserByName(@Param("userName") String userName) {
    return "SELECT"
        + JIT_USER_COLUMNS
        + " FROM "
        + JitIdentityMapper.JIT_USER_TABLE_NAME
        + " WHERE LOWER(user_name) = LOWER(#{userName}) AND deleted_at = 0";
  }

  /** Returns SQL that inserts a JIT user. */
  public String insertUser(@Param("user") JitUserPO user) {
    return "INSERT INTO "
        + JitIdentityMapper.JIT_USER_TABLE_NAME
        + " (user_id, user_name, audit_info, current_version, last_version, deleted_at) VALUES (#{user.userId}, #{user.userName}, #{user.auditInfo},"
        + " #{user.currentVersion}, #{user.lastVersion}, #{user.deletedAt})";
  }

  /** Returns SQL that loads active JIT groups by name. */
  public String selectGroupsByNames(@Param("groupNames") List<String> groupNames) {
    return "<script>SELECT"
        + JIT_GROUP_COLUMNS
        + " FROM "
        + JitIdentityMapper.JIT_GROUP_TABLE_NAME
        + " WHERE deleted_at = 0 AND LOWER(group_name) IN "
        + lowerNameInClause("groupNames", "groupName")
        + "</script>";
  }

  /** Returns SQL that batch-inserts JIT groups. */
  public String insertGroups(@Param("groups") List<JitGroupPO> groups) {
    return "<script>INSERT INTO "
        + JitIdentityMapper.JIT_GROUP_TABLE_NAME
        + " (group_id, group_name, audit_info, current_version, last_version, deleted_at) VALUES "
        + "<foreach collection='groups' item='group' separator=','>"
        + "(#{group.groupId}, #{group.groupName}, #{group.auditInfo}, #{group.currentVersion},"
        + " #{group.lastVersion}, #{group.deletedAt})"
        + "</foreach></script>";
  }

  /** Returns SQL that lists a JIT user's active group ids. */
  public String selectGroupIdsForUser(@Param("userId") long userId) {
    return "SELECT group_id FROM "
        + JitIdentityMapper.JIT_USER_GROUP_REL_TABLE_NAME
        + " WHERE user_id = #{userId} AND deleted_at = 0";
  }

  /** Returns SQL that soft-deletes memberships absent from the latest JWT snapshot. */
  public String softDeleteMissingMemberships(
      @Param("userId") long userId,
      @Param("groupIds") List<Long> groupIds,
      @Param("deletedAt") long deletedAt) {
    return "<script>UPDATE "
        + JitIdentityMapper.JIT_USER_GROUP_REL_TABLE_NAME
        + " SET deleted_at = #{deletedAt}, last_version = current_version,"
        + " current_version = current_version + 1"
        + " WHERE user_id = #{userId} AND deleted_at = 0"
        + "<if test='groupIds != null and groupIds.size() > 0'> AND group_id NOT IN "
        + idInClause("groupIds", "groupId")
        + "</if></script>";
  }

  /** Returns SQL that batch-inserts JIT memberships. */
  public String insertMemberships(@Param("relations") List<JitUserGroupRelPO> relations) {
    return "<script>INSERT INTO "
        + JitIdentityMapper.JIT_USER_GROUP_REL_TABLE_NAME
        + " (id, user_id, group_id, audit_info, current_version, last_version, deleted_at) VALUES "
        + "<foreach collection='relations' item='relation' separator=','>"
        + "(#{relation.id}, #{relation.userId}, #{relation.groupId}, #{relation.auditInfo},"
        + " #{relation.currentVersion}, #{relation.lastVersion}, #{relation.deletedAt})"
        + "</foreach></script>";
  }

  /** Returns SQL that soft-deletes a JIT user superseded by SCIM. */
  public String softDeleteUserByName(
      @Param("userName") String userName, @Param("deletedAt") long deletedAt) {
    return "UPDATE "
        + JitIdentityMapper.JIT_USER_TABLE_NAME
        + " SET deleted_at = #{deletedAt}, last_version = current_version,"
        + " current_version = current_version + 1"
        + " WHERE LOWER(user_name) = LOWER(#{userName}) AND deleted_at = 0";
  }

  /** Returns SQL that soft-deletes memberships for a JIT user superseded by SCIM. */
  public String softDeleteMembershipsByUserName(
      @Param("userName") String userName, @Param("deletedAt") long deletedAt) {
    return "UPDATE "
        + JitIdentityMapper.JIT_USER_GROUP_REL_TABLE_NAME
        + " SET deleted_at = #{deletedAt}, last_version = current_version,"
        + " current_version = current_version + 1 WHERE deleted_at = 0 AND user_id IN"
        + " (SELECT user_id FROM "
        + JitIdentityMapper.JIT_USER_TABLE_NAME
        + " WHERE LOWER(user_name) = LOWER(#{userName}))";
  }

  /** Returns SQL that soft-deletes JIT groups superseded by SCIM. */
  public String softDeleteGroupsByNames(
      @Param("groupNames") List<String> groupNames, @Param("deletedAt") long deletedAt) {
    return "<script>UPDATE "
        + JitIdentityMapper.JIT_GROUP_TABLE_NAME
        + " SET deleted_at = #{deletedAt}, last_version = current_version,"
        + " current_version = current_version + 1 WHERE deleted_at = 0"
        + " AND LOWER(group_name) IN "
        + lowerNameInClause("groupNames", "groupName")
        + "</script>";
  }

  /** Returns SQL that soft-deletes memberships for groups superseded by SCIM. */
  public String softDeleteMembershipsByGroupNames(
      @Param("groupNames") List<String> groupNames, @Param("deletedAt") long deletedAt) {
    return "<script>UPDATE "
        + JitIdentityMapper.JIT_USER_GROUP_REL_TABLE_NAME
        + " SET deleted_at = #{deletedAt}, last_version = current_version,"
        + " current_version = current_version + 1 WHERE deleted_at = 0 AND group_id IN"
        + " (SELECT group_id FROM "
        + JitIdentityMapper.JIT_GROUP_TABLE_NAME
        + " WHERE LOWER(group_name) IN "
        + lowerNameInClause("groupNames", "groupName")
        + ")</script>";
  }

  /** Returns SQL that physically deletes legacy JIT memberships. */
  public String deleteMembershipsByLegacyTimeline(
      @Param("legacyTimeline") Long legacyTimeline, @Param("limit") int limit) {
    return deleteByLegacyTimeline(JitIdentityMapper.JIT_USER_GROUP_REL_TABLE_NAME);
  }

  /** Returns SQL that physically deletes legacy JIT users. */
  public String deleteUsersByLegacyTimeline(
      @Param("legacyTimeline") Long legacyTimeline, @Param("limit") int limit) {
    return deleteByLegacyTimeline(JitIdentityMapper.JIT_USER_TABLE_NAME);
  }

  /** Returns SQL that physically deletes legacy JIT groups. */
  public String deleteGroupsByLegacyTimeline(
      @Param("legacyTimeline") Long legacyTimeline, @Param("limit") int limit) {
    return deleteByLegacyTimeline(JitIdentityMapper.JIT_GROUP_TABLE_NAME);
  }

  private static String nameInClause(String collection, String item) {
    return "<foreach collection='"
        + collection
        + "' item='"
        + item
        + "' open='(' separator=',' close=')'>#{"
        + item
        + "}</foreach>";
  }

  private static String lowerNameInClause(String collection, String item) {
    return "<foreach collection='"
        + collection
        + "' item='"
        + item
        + "' open='(' separator=',' close=')'>LOWER(#{"
        + item
        + "})</foreach>";
  }

  private static String idInClause(String collection, String item) {
    return nameInClause(collection, item);
  }

  protected String deleteByLegacyTimeline(String table) {
    return "DELETE FROM "
        + table
        + " WHERE deleted_at > 0 AND deleted_at < #{legacyTimeline} LIMIT #{limit}";
  }
}
