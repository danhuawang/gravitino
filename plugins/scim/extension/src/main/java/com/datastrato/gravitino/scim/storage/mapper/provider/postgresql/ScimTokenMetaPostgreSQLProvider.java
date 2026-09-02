/*
 * Copyright 2026 Datastrato Inc.
 */

package com.datastrato.gravitino.scim.storage.mapper.provider.postgresql;

import com.datastrato.gravitino.scim.storage.mapper.ScimTokenMetaMapper;
import com.datastrato.gravitino.scim.storage.mapper.provider.base.ScimTokenMetaBaseSQLProvider;
import org.apache.ibatis.annotations.Param;

/** PostgreSQL SQL provider for SCIM token metadata. */
public class ScimTokenMetaPostgreSQLProvider extends ScimTokenMetaBaseSQLProvider {
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
