/*
 * Copyright 2026 Datastrato Inc.
 */

package com.datastrato.gravitino.scim.storage.mapper.provider.postgresql;

import com.datastrato.gravitino.scim.storage.mapper.ScimGroupMetaMapper;
import com.datastrato.gravitino.scim.storage.mapper.provider.base.ScimGroupMetaBaseSQLProvider;
import org.apache.ibatis.annotations.Param;

/** PostgreSQL SQL provider for SCIM metadata. */
public class ScimGroupMetaPostgreSQLProvider extends ScimGroupMetaBaseSQLProvider {

  @Override
  public String deleteByLegacyTimeline(
      @Param("legacyTimeline") Long legacyTimeline, @Param("limit") int limit) {
    String table = ScimGroupMetaMapper.TABLE_NAME;
    String idCol = "group_id";
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
}
