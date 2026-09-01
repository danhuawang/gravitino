/*
 * Copyright 2026 Datastrato Pvt Ltd.
 */

package com.datastrato.gravitino.scim.v2.storage.mapper.provider.postgresql;

import com.datastrato.gravitino.scim.v2.storage.mapper.ScimErrorHistoryMapper;
import com.datastrato.gravitino.scim.v2.storage.mapper.provider.base.ScimErrorHistoryBaseSQLProvider;
import org.apache.ibatis.annotations.Param;

/** SQL provider for SCIM error history statements on PostgreSQL backends. */
public class ScimErrorHistoryPostgreSQLProvider extends ScimErrorHistoryBaseSQLProvider {

  @Override
  public String deleteByCreatedAtBefore(
      @Param("legacyTimeline") Long legacyTimeline, @Param("limit") int limit) {
    return "DELETE FROM "
        + ScimErrorHistoryMapper.TABLE_NAME
        + " WHERE error_id IN (SELECT error_id FROM "
        + ScimErrorHistoryMapper.TABLE_NAME
        + " WHERE created_at < #{legacyTimeline} LIMIT #{limit})";
  }
}
