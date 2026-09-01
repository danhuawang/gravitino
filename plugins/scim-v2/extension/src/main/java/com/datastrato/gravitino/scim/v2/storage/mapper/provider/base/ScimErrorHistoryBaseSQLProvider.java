/*
 * Copyright 2026 Datastrato Inc.
 */

package com.datastrato.gravitino.scim.v2.storage.mapper.provider.base;

import com.datastrato.gravitino.scim.v2.storage.mapper.ScimErrorHistoryMapper;
import com.datastrato.gravitino.scim.v2.storage.po.ScimErrorHistoryPO;
import org.apache.ibatis.annotations.Param;

/** Base SQL provider for SCIM v2 error history statements. */
public class ScimErrorHistoryBaseSQLProvider {
  public String insert(@Param("errorHistory") ScimErrorHistoryPO errorHistory) {
    return "INSERT INTO "
        + ScimErrorHistoryMapper.TABLE_NAME
        + " (error_id, http_method, request_path, http_status, scim_type, error_detail, principal, created_at)"
        + " VALUES (#{errorHistory.errorId}, #{errorHistory.httpMethod}, #{errorHistory.requestPath},"
        + " #{errorHistory.httpStatus}, #{errorHistory.scimType}, #{errorHistory.errorDetail},"
        + " #{errorHistory.principal}, #{errorHistory.createdAt})";
  }

  public String countAll() {
    return "SELECT COUNT(1) FROM " + ScimErrorHistoryMapper.TABLE_NAME;
  }

  public String deleteByCreatedAtBefore(
      @Param("legacyTimeline") Long legacyTimeline, @Param("limit") int limit) {
    return "DELETE FROM "
        + ScimErrorHistoryMapper.TABLE_NAME
        + " WHERE created_at < #{legacyTimeline} LIMIT #{limit}";
  }
}
