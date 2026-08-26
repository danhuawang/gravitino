/*
 * Copyright 2026 Datastrato Pvt Ltd.
 */

package com.datastrato.gravitino.scim.storage.mapper.provider.postgresql;

import com.datastrato.gravitino.scim.storage.mapper.ScimTokenMetaMapper;
import com.datastrato.gravitino.scim.storage.mapper.provider.base.ScimTokenMetaBaseSQLProvider;
import org.apache.gravitino.storage.relational.mapper.MetalakeMetaMapper;
import org.apache.ibatis.annotations.Param;

/** SQL provider for SCIM token metadata statements on PostgreSQL backends. */
public class ScimTokenMetaPostgreSQLProvider extends ScimTokenMetaBaseSQLProvider {

  @Override
  public String softDeleteByMetalakeAndName(
      @Param("metalakeName") String metalakeName, @Param("tokenName") String tokenName) {
    return "UPDATE "
        + ScimTokenMetaMapper.TABLE_NAME
        + " SET deleted_at = "
        + currentTimeMillisExpression()
        + " WHERE metalake_id IN ("
        + " SELECT mm.metalake_id FROM "
        + MetalakeMetaMapper.TABLE_NAME
        + " mm WHERE mm.metalake_name = #{metalakeName} AND mm.deleted_at = 0)"
        + " AND token_name = #{tokenName} AND deleted_at = 0";
  }

  @Override
  public String softDeleteByUnavailableMetalake() {
    return "UPDATE "
        + ScimTokenMetaMapper.TABLE_NAME
        + " stm SET deleted_at = "
        + currentTimeMillisExpression()
        + " WHERE stm.deleted_at = 0"
        + " AND NOT EXISTS ("
        + " SELECT 1 FROM "
        + MetalakeMetaMapper.TABLE_NAME
        + " m WHERE m.metalake_id = stm.metalake_id AND m.deleted_at = 0"
        + " )";
  }

  @Override
  public String deleteByLegacyTimeline(
      @Param("legacyTimeline") Long legacyTimeline, @Param("limit") int limit) {
    return "DELETE FROM "
        + ScimTokenMetaMapper.TABLE_NAME
        + " WHERE token_id IN (SELECT token_id FROM "
        + ScimTokenMetaMapper.TABLE_NAME
        + " WHERE deleted_at > 0 AND deleted_at < #{legacyTimeline} LIMIT #{limit})";
  }

  @Override
  protected String currentTimeMillisExpression() {
    return "CAST(FLOOR(EXTRACT(EPOCH FROM CURRENT_TIMESTAMP(3)) * 1000) AS BIGINT)";
  }
}
