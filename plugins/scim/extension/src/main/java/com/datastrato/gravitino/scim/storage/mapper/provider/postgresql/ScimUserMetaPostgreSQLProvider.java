/*
 * Copyright 2026 Datastrato Inc.
 */

package com.datastrato.gravitino.scim.storage.mapper.provider.postgresql;

import com.datastrato.gravitino.scim.storage.mapper.ScimUserMetaMapper;
import com.datastrato.gravitino.scim.storage.mapper.provider.base.ScimUserMetaBaseSQLProvider;
import com.datastrato.gravitino.scim.storage.po.ScimUserMetaPO;
import org.apache.ibatis.annotations.Param;

/** PostgreSQL SQL provider for SCIM metadata. */
public class ScimUserMetaPostgreSQLProvider extends ScimUserMetaBaseSQLProvider {

  @Override
  public String insert(@Param("userMeta") ScimUserMetaPO userMeta) {
    return "INSERT INTO "
        + ScimUserMetaMapper.TABLE_NAME
        + " (user_id, user_name, external_id, enabled, current_version, last_version, deleted_at)"
        + " VALUES (#{userMeta.userId}, #{userMeta.userName}, #{userMeta.externalId},"
        + " "
        + enabledSmallIntExpression("#{userMeta.enabled}")
        + ", #{userMeta.currentVersion}, #{userMeta.lastVersion},"
        + " #{userMeta.deletedAt})";
  }

  @Override
  public String updateEnabled(
      @Param("externalId") String externalId, @Param("enabled") boolean enabled) {
    return "UPDATE "
        + ScimUserMetaMapper.TABLE_NAME
        + " SET enabled = "
        + enabledSmallIntExpression("#{enabled}")
        + ", last_version = current_version,"
        + " current_version = current_version + 1"
        + " WHERE external_id = #{externalId} AND deleted_at = 0";
  }

  @Override
  public String updateEnabledByUserId(
      @Param("userId") long userId, @Param("enabled") boolean enabled) {
    return "UPDATE "
        + ScimUserMetaMapper.TABLE_NAME
        + " SET enabled = "
        + enabledSmallIntExpression("#{enabled}")
        + ", last_version = current_version,"
        + " current_version = current_version + 1"
        + " WHERE user_id = #{userId} AND deleted_at = 0";
  }

  @Override
  public String deleteByLegacyTimeline(
      @Param("legacyTimeline") Long legacyTimeline, @Param("limit") int limit) {
    String table = ScimUserMetaMapper.TABLE_NAME;
    String idCol = "user_id";
    return "DELETE FROM "
        + table
        + " WHERE "
        + idCol
        + " IN (SELECT "
        + idCol
        + " FROM "
        + table
        + " WHERE deleted_at > 0 AND deleted_at < #{legacyTimeline} LIMIT #{limit})";
  }

  @Override
  protected String currentTimeMillisExpression() {
    return "CAST(FLOOR(EXTRACT(EPOCH FROM CURRENT_TIMESTAMP(3)) * 1000) AS BIGINT)";
  }

  /**
   * Maps a Java boolean bind parameter to {@code scim_user_meta.enabled} ({@code SMALLINT}).
   *
   * <p>PostgreSQL enterprise schema stores {@code enabled} as {@code SMALLINT}, not {@code
   * BOOLEAN}.
   */
  private static String enabledSmallIntExpression(String enabledParamRef) {
    return "CASE WHEN " + enabledParamRef + " THEN 1 ELSE 0 END";
  }
}
