/*
 * Copyright 2026 Datastrato Inc.
 */

package com.datastrato.gravitino.scim.v2.storage.mapper.provider.postgresql;

import com.datastrato.gravitino.scim.v2.storage.mapper.ScimUserMetaMapper;
import com.datastrato.gravitino.scim.v2.storage.mapper.provider.base.ScimUserMetaBaseSQLProvider;
import org.apache.ibatis.annotations.Param;

/** PostgreSQL SQL provider for SCIM v2 metadata. */
public class ScimUserMetaPostgreSQLProvider extends ScimUserMetaBaseSQLProvider {

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
}
