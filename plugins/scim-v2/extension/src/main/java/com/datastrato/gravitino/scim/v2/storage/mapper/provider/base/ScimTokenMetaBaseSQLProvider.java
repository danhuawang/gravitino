/*
 * Copyright 2026 Datastrato Inc.
 */

package com.datastrato.gravitino.scim.v2.storage.mapper.provider.base;

import com.datastrato.gravitino.scim.v2.storage.mapper.ScimTokenMetaMapper;
import com.datastrato.gravitino.scim.v2.storage.po.ScimTokenMetaPO;
import org.apache.ibatis.annotations.Param;

/** Base SQL provider for SCIM v2 token metadata statements (MySQL default). */
public class ScimTokenMetaBaseSQLProvider {

  private static final String SELECT_COLUMNS =
      " token_id as tokenId, token_name as tokenName, token_hash as tokenHash,"
          + " expires_at as expiresAt, audit_info as auditInfo, deleted_at as deletedAt,"
          + " updated_at as updatedAt, last_used_at as lastUsedAt";

  public String insert(@Param("tokenMeta") ScimTokenMetaPO tokenMeta) {
    return "INSERT INTO "
        + ScimTokenMetaMapper.TABLE_NAME
        + " (token_id, token_name, token_hash, expires_at, audit_info, deleted_at, updated_at,"
        + " last_used_at) VALUES (#{tokenMeta.tokenId}, #{tokenMeta.tokenName},"
        + " #{tokenMeta.tokenHash}, #{tokenMeta.expiresAt}, #{tokenMeta.auditInfo},"
        + " #{tokenMeta.deletedAt}, #{tokenMeta.updatedAt}, #{tokenMeta.lastUsedAt})";
  }

  public String selectByTokenHash(@Param("tokenHash") String tokenHash) {
    return "SELECT"
        + SELECT_COLUMNS
        + " FROM "
        + ScimTokenMetaMapper.TABLE_NAME
        + " WHERE token_hash = #{tokenHash} AND deleted_at = 0";
  }

  public String selectByName(@Param("tokenName") String tokenName) {
    return "SELECT"
        + SELECT_COLUMNS
        + " FROM "
        + ScimTokenMetaMapper.TABLE_NAME
        + " WHERE token_name = #{tokenName} AND deleted_at = 0";
  }

  public String listProvisioningStats() {
    return "SELECT COUNT(token_id) as tokenCount, COALESCE(MAX(last_used_at), 0) as lastUsedAt"
        + " FROM "
        + ScimTokenMetaMapper.TABLE_NAME
        + " WHERE deleted_at = 0";
  }

  public String listAll() {
    return "SELECT"
        + SELECT_COLUMNS
        + " FROM "
        + ScimTokenMetaMapper.TABLE_NAME
        + " WHERE deleted_at = 0 ORDER BY token_name ASC";
  }

  public String selectMaxLastUsedAt() {
    return "SELECT COALESCE(MAX(last_used_at), 0) FROM "
        + ScimTokenMetaMapper.TABLE_NAME
        + " WHERE deleted_at = 0";
  }

  public String softDeleteByName(@Param("tokenName") String tokenName) {
    return "UPDATE "
        + ScimTokenMetaMapper.TABLE_NAME
        + " SET deleted_at = "
        + currentTimeMillisExpression()
        + " WHERE token_name = #{tokenName} AND deleted_at = 0";
  }

  public String softDeleteByExpiration() {
    return "UPDATE "
        + ScimTokenMetaMapper.TABLE_NAME
        + " SET deleted_at = "
        + currentTimeMillisExpression()
        + " WHERE expires_at > 0 AND expires_at <= "
        + currentTimeMillisExpression()
        + " AND deleted_at = 0";
  }

  public String updateTokenOnRotate(
      @Param("newTokenMeta") ScimTokenMetaPO newTokenMeta,
      @Param("oldTokenMeta") ScimTokenMetaPO oldTokenMeta) {
    return "UPDATE "
        + ScimTokenMetaMapper.TABLE_NAME
        + " SET token_hash = #{newTokenMeta.tokenHash}, expires_at = #{newTokenMeta.expiresAt},"
        + " audit_info = #{newTokenMeta.auditInfo}, updated_at = "
        + currentTimeMillisExpression()
        + " WHERE token_id = #{oldTokenMeta.tokenId} AND token_name = #{oldTokenMeta.tokenName}"
        + " AND token_hash = #{oldTokenMeta.tokenHash} AND expires_at = #{oldTokenMeta.expiresAt}"
        + " AND audit_info = #{oldTokenMeta.auditInfo} AND deleted_at = 0";
  }

  public String updateScimTokenLastUsedAt(@Param("tokenId") Long tokenId) {
    return "UPDATE "
        + ScimTokenMetaMapper.TABLE_NAME
        + " SET last_used_at = "
        + currentTimeMillisExpression()
        + " WHERE token_id = #{tokenId} AND deleted_at = 0";
  }

  public String deleteByLegacyTimeline(
      @Param("legacyTimeline") Long legacyTimeline, @Param("limit") int limit) {
    return "DELETE FROM "
        + ScimTokenMetaMapper.TABLE_NAME
        + " WHERE deleted_at > 0 AND deleted_at < #{legacyTimeline} LIMIT #{limit}";
  }

  protected String currentTimeMillisExpression() {
    return "(UNIX_TIMESTAMP() * 1000.0) + EXTRACT(MICROSECOND FROM CURRENT_TIMESTAMP(3)) / 1000";
  }
}
