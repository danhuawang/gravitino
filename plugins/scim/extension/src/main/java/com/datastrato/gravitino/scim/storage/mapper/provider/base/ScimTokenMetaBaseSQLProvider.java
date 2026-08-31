/*
 * Copyright 2026 Datastrato Inc.
 */

package com.datastrato.gravitino.scim.storage.mapper.provider.base;

import com.datastrato.gravitino.scim.storage.mapper.ScimTokenMetaMapper;
import com.datastrato.gravitino.scim.storage.po.ScimTokenMetaPO;
import java.util.List;
import org.apache.gravitino.storage.relational.mapper.MetalakeMetaMapper;
import org.apache.ibatis.annotations.Param;

/** Base SQL provider for SCIM token metadata statements (MySQL default). */
public class ScimTokenMetaBaseSQLProvider {

  private static final String SELECT_COLUMNS =
      " token_id as tokenId, metalake_id as metalakeId, token_name as tokenName,"
          + " token_hash as tokenHash, expires_at as expiresAt, audit_info as auditInfo,"
          + " deleted_at as deletedAt, updated_at as updatedAt, last_used_at as lastUsedAt";

  public String insert(@Param("tokenMeta") ScimTokenMetaPO tokenMeta) {
    return "INSERT INTO "
        + ScimTokenMetaMapper.TABLE_NAME
        + " (token_id, metalake_id, token_name, token_hash, expires_at, audit_info, deleted_at,"
        + " updated_at, last_used_at)"
        + " VALUES ("
        + " #{tokenMeta.tokenId},"
        + " #{tokenMeta.metalakeId},"
        + " #{tokenMeta.tokenName},"
        + " #{tokenMeta.tokenHash},"
        + " #{tokenMeta.expiresAt},"
        + " #{tokenMeta.auditInfo},"
        + " #{tokenMeta.deletedAt},"
        + " #{tokenMeta.updatedAt},"
        + " #{tokenMeta.lastUsedAt}"
        + " )";
  }

  public String selectByTokenHash(@Param("tokenHash") String tokenHash) {
    return "SELECT"
        + SELECT_COLUMNS
        + " FROM "
        + ScimTokenMetaMapper.TABLE_NAME
        + " WHERE token_hash = #{tokenHash}"
        + " AND deleted_at = 0";
  }

  public String selectByMetalakeAndName(
      @Param("metalakeName") String metalakeName, @Param("tokenName") String tokenName) {
    return "SELECT"
        + SELECT_COLUMNS
        + " FROM "
        + ScimTokenMetaMapper.TABLE_NAME
        + " stm WHERE "
        + activeMetalakeIdInClause("stm")
        + " AND stm.token_name = #{tokenName}"
        + " AND stm.deleted_at = 0";
  }

  /**
   * Lists SCIM provisioning stats for the given metalake ids, including zero-token metalakes.
   *
   * @param metalakeIds metalake ids to include
   * @return SQL statement
   */
  public String listProvisioningStatsByMetalakeIds(@Param("metalakeIds") List<Long> metalakeIds) {
    return "<script>"
        + "SELECT mm.metalake_name as metalakeName,"
        + " COUNT(stm.token_id) as tokenCount,"
        + " COALESCE(MAX(stm.last_used_at), 0) as lastUsedAt"
        + " FROM "
        + MetalakeMetaMapper.TABLE_NAME
        + " mm LEFT JOIN "
        + ScimTokenMetaMapper.TABLE_NAME
        + " stm ON mm.metalake_id = stm.metalake_id AND stm.deleted_at = 0"
        + " WHERE mm.deleted_at = 0"
        + " AND mm.metalake_id IN"
        + " <foreach collection='metalakeIds' item='metalakeId' open='(' close=')' separator=','>"
        + " #{metalakeId}"
        + " </foreach>"
        + " GROUP BY mm.metalake_id, mm.metalake_name"
        + " ORDER BY mm.metalake_name ASC"
        + "</script>";
  }

  /**
   * Lists active token rows for a metalake ordered by token name.
   *
   * @param metalakeName target metalake name
   * @return SQL statement
   */
  public String listByMetalake(@Param("metalakeName") String metalakeName) {
    return "SELECT"
        + SELECT_COLUMNS
        + " FROM "
        + ScimTokenMetaMapper.TABLE_NAME
        + " stm WHERE "
        + activeMetalakeIdInClause("stm")
        + " AND stm.deleted_at = 0"
        + " ORDER BY stm.token_name ASC";
  }

  /**
   * Returns the latest {@code last_used_at} among active tokens for a metalake.
   *
   * @param metalakeName target metalake name
   * @return SQL statement
   */
  public String selectMaxLastUsedAt(@Param("metalakeName") String metalakeName) {
    return "SELECT COALESCE(MAX(stm.last_used_at), 0) FROM "
        + ScimTokenMetaMapper.TABLE_NAME
        + " stm WHERE stm.deleted_at = 0 AND "
        + activeMetalakeIdInClause("stm");
  }

  public String softDeleteByMetalakeAndName(
      @Param("metalakeName") String metalakeName, @Param("tokenName") String tokenName) {
    return "UPDATE "
        + ScimTokenMetaMapper.TABLE_NAME
        + " stm SET stm.deleted_at = "
        + currentTimeMillisExpression()
        + " WHERE "
        + activeMetalakeIdInClause("stm")
        + " AND stm.token_name = #{tokenName}"
        + " AND stm.deleted_at = 0";
  }

  public String softDeleteByExpiration() {
    return "UPDATE "
        + ScimTokenMetaMapper.TABLE_NAME
        + " SET deleted_at = "
        + currentTimeMillisExpression()
        + " WHERE expires_at > 0"
        + " AND expires_at <= "
        + currentTimeMillisExpression()
        + " AND deleted_at = 0";
  }

  /**
   * Soft-deletes active token rows whose metalake is missing or already soft-deleted.
   *
   * @return SQL statement
   */
  public String softDeleteByUnavailableMetalake() {
    return "UPDATE "
        + ScimTokenMetaMapper.TABLE_NAME
        + " stm SET stm.deleted_at = "
        + currentTimeMillisExpression()
        + " WHERE stm.deleted_at = 0"
        + " AND NOT EXISTS ("
        + " SELECT 1 FROM "
        + MetalakeMetaMapper.TABLE_NAME
        + " m WHERE m.metalake_id = stm.metalake_id AND m.deleted_at = 0"
        + " )";
  }

  public String updateTokenOnRotate(
      @Param("newTokenMeta") ScimTokenMetaPO newTokenMeta,
      @Param("oldTokenMeta") ScimTokenMetaPO oldTokenMeta) {
    return "UPDATE "
        + ScimTokenMetaMapper.TABLE_NAME
        + " SET token_hash = #{newTokenMeta.tokenHash},"
        + " expires_at = #{newTokenMeta.expiresAt},"
        + " audit_info = #{newTokenMeta.auditInfo},"
        + " updated_at = "
        + currentTimeMillisExpression()
        + " WHERE token_id = #{oldTokenMeta.tokenId}"
        + " AND metalake_id = #{oldTokenMeta.metalakeId}"
        + " AND token_name = #{oldTokenMeta.tokenName}"
        + " AND token_hash = #{oldTokenMeta.tokenHash}"
        + " AND expires_at = #{oldTokenMeta.expiresAt}"
        + " AND audit_info = #{oldTokenMeta.auditInfo}"
        + " AND deleted_at = 0";
  }

  /**
   * Updates {@code last_used_at} for an active token after authenticated SCIM access.
   *
   * @param tokenId token id
   * @return SQL statement
   */
  public String updateScimTokenLastUsedAt(@Param("tokenId") Long tokenId) {
    return "UPDATE "
        + ScimTokenMetaMapper.TABLE_NAME
        + " SET last_used_at = "
        + currentTimeMillisExpression()
        + " WHERE token_id = #{tokenId}"
        + " AND deleted_at = 0";
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

  protected String activeMetalakeIdInClause(String tableAlias) {
    return tableAlias
        + ".metalake_id IN ("
        + " SELECT mm.metalake_id FROM "
        + MetalakeMetaMapper.TABLE_NAME
        + " mm WHERE mm.metalake_name = #{metalakeName} AND mm.deleted_at = 0)";
  }
}
