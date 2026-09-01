/*
 * Copyright 2026 Datastrato Inc.
 */

package com.datastrato.gravitino.scim.v2.storage.mapper.provider.base;

import com.datastrato.gravitino.scim.v2.storage.mapper.ScimUserMetaMapper;
import com.datastrato.gravitino.scim.v2.storage.po.ScimUserMetaPO;
import org.apache.ibatis.annotations.Param;

/** Base SQL provider for SCIM v2 user metadata statements (MySQL default). */
public class ScimUserMetaBaseSQLProvider {

  private static final String SELECT_COLUMNS =
      " user_id as userId, user_name as userName, external_id as externalId,"
          + " enabled as enabled, current_version as currentVersion,"
          + " last_version as lastVersion, deleted_at as deletedAt";

  public String insert(@Param("userMeta") ScimUserMetaPO userMeta) {
    return "INSERT INTO "
        + ScimUserMetaMapper.TABLE_NAME
        + " (user_id, user_name, external_id, enabled, current_version, last_version, deleted_at)"
        + " VALUES (#{userMeta.userId}, #{userMeta.userName}, #{userMeta.externalId},"
        + " #{userMeta.enabled}, #{userMeta.currentVersion}, #{userMeta.lastVersion},"
        + " #{userMeta.deletedAt})";
  }

  public String selectByExternalId(@Param("externalId") String externalId) {
    return "SELECT"
        + SELECT_COLUMNS
        + " FROM "
        + ScimUserMetaMapper.TABLE_NAME
        + " WHERE external_id = #{externalId} AND deleted_at = 0";
  }

  public String selectByUserName(@Param("userName") String userName) {
    return "SELECT"
        + SELECT_COLUMNS
        + " FROM "
        + ScimUserMetaMapper.TABLE_NAME
        + " WHERE user_name = #{userName} AND deleted_at = 0";
  }

  public String selectByUserId(@Param("userId") long userId) {
    return "SELECT"
        + SELECT_COLUMNS
        + " FROM "
        + ScimUserMetaMapper.TABLE_NAME
        + " WHERE user_id = #{userId} AND deleted_at = 0";
  }

  public String listUsers(@Param("offset") int offset, @Param("limit") int limit) {
    return "SELECT"
        + SELECT_COLUMNS
        + " FROM "
        + ScimUserMetaMapper.TABLE_NAME
        + " WHERE deleted_at = 0 ORDER BY user_name ASC LIMIT #{limit} OFFSET #{offset}";
  }

  public String countUsers() {
    return "SELECT COUNT(1) FROM " + ScimUserMetaMapper.TABLE_NAME + " WHERE deleted_at = 0";
  }

  public String updateEnabled(
      @Param("externalId") String externalId, @Param("enabled") boolean enabled) {
    return "UPDATE "
        + ScimUserMetaMapper.TABLE_NAME
        + " SET enabled = #{enabled}, last_version = current_version,"
        + " current_version = current_version + 1"
        + " WHERE external_id = #{externalId} AND deleted_at = 0";
  }

  public String softDeleteByExternalId(@Param("externalId") String externalId) {
    return "UPDATE "
        + ScimUserMetaMapper.TABLE_NAME
        + " SET deleted_at = "
        + currentTimeMillisExpression()
        + " WHERE external_id = #{externalId} AND deleted_at = 0";
  }

  public String deleteByLegacyTimeline(
      @Param("legacyTimeline") Long legacyTimeline, @Param("limit") int limit) {
    return "DELETE FROM "
        + ScimUserMetaMapper.TABLE_NAME
        + " WHERE deleted_at > 0 AND deleted_at < #{legacyTimeline} LIMIT #{limit}";
  }

  protected String currentTimeMillisExpression() {
    return "(UNIX_TIMESTAMP() * 1000.0) + EXTRACT(MICROSECOND FROM CURRENT_TIMESTAMP(3)) / 1000";
  }
}
