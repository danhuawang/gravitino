/*
 * Copyright 2026 Datastrato Inc.
 */

package com.datastrato.gravitino.scim.storage.mapper.provider.base;

import com.datastrato.gravitino.scim.storage.mapper.ScimUserMetaMapper;
import com.datastrato.gravitino.scim.storage.po.ScimUserMetaPO;
import java.util.List;
import org.apache.ibatis.annotations.Param;

/** Base SQL provider for SCIM user metadata statements (MySQL default). */
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

  /**
   * Builds a case-insensitive lookup by {@code user_name}.
   *
   * @param userName user name to match ignoring case
   * @return SELECT SQL
   */
  public String selectByUserNameIgnoreCase(@Param("userName") String userName) {
    return "SELECT"
        + SELECT_COLUMNS
        + " FROM "
        + ScimUserMetaMapper.TABLE_NAME
        + " WHERE LOWER(user_name) = LOWER(#{userName}) AND deleted_at = 0";
  }

  /**
   * Builds a batch lookup by {@code external_id}.
   *
   * @param externalIds SCIM externalIds; empty or {@code null} yields an empty script
   * @return SELECT SQL script
   */
  public String selectByExternalIds(@Param("externalIds") List<String> externalIds) {
    return "<script><if test='externalIds != null and externalIds.size() > 0'>SELECT"
        + SELECT_COLUMNS
        + " FROM "
        + ScimUserMetaMapper.TABLE_NAME
        + " WHERE deleted_at = 0 AND external_id IN ("
        + "<foreach collection='externalIds' item='externalId' separator=','>#{externalId}</foreach>)"
        + "</if></script>";
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

  public String updateEnabledByUserId(
      @Param("userId") long userId, @Param("enabled") boolean enabled) {
    return "UPDATE "
        + ScimUserMetaMapper.TABLE_NAME
        + " SET enabled = #{enabled}, last_version = current_version,"
        + " current_version = current_version + 1"
        + " WHERE user_id = #{userId} AND deleted_at = 0";
  }

  public String softDeleteByUserId(@Param("userId") long userId) {
    return "UPDATE "
        + ScimUserMetaMapper.TABLE_NAME
        + " SET deleted_at = "
        + currentTimeMillisExpression()
        + " WHERE user_id = #{userId} AND deleted_at = 0";
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
