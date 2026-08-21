/*
 * Copyright 2026 Datastrato Pvt Ltd.
 * This software is licensed under the Apache License version 2.
 */

package com.datastrato.gravitino.scim.storage.mapper.provider.base;

import com.datastrato.gravitino.scim.storage.mapper.ScimErrorHistoryMapper;
import com.datastrato.gravitino.scim.storage.po.ScimErrorHistoryPO;
import org.apache.gravitino.storage.relational.mapper.MetalakeMetaMapper;
import org.apache.ibatis.annotations.Param;

/** Base SQL provider for SCIM error history statements (MySQL default). */
public class ScimErrorHistoryBaseSQLProvider {

  private static final String SELECT_COLUMNS =
      " error_id as errorId, metalake_id as metalakeId, http_method as httpMethod,"
          + " request_path as requestPath, http_status as httpStatus, scim_type as scimType,"
          + " error_detail as errorDetail, principal, created_at as createdAt";

  public String insert(@Param("errorHistory") ScimErrorHistoryPO errorHistory) {
    return "INSERT INTO "
        + ScimErrorHistoryMapper.TABLE_NAME
        + " (error_id, metalake_id, http_method, request_path, http_status, scim_type,"
        + " error_detail, principal, created_at)"
        + " VALUES ("
        + " #{errorHistory.errorId},"
        + " COALESCE((SELECT mm.metalake_id FROM "
        + MetalakeMetaMapper.TABLE_NAME
        + " mm WHERE mm.metalake_name = #{errorHistory.metalakeName} AND mm.deleted_at = 0), 0),"
        + " #{errorHistory.httpMethod},"
        + " #{errorHistory.requestPath},"
        + " #{errorHistory.httpStatus},"
        + " #{errorHistory.scimType},"
        + " #{errorHistory.errorDetail},"
        + " #{errorHistory.principal},"
        + " #{errorHistory.createdAt}"
        + " )";
  }

  public String selectByErrorId(@Param("errorId") Long errorId) {
    return "SELECT"
        + SELECT_COLUMNS
        + " FROM "
        + ScimErrorHistoryMapper.TABLE_NAME
        + " WHERE error_id = #{errorId}";
  }

  /**
   * Counts error history rows for an active metalake.
   *
   * @param metalakeName target metalake name
   * @return SQL statement
   */
  public String countByMetalake(@Param("metalakeName") String metalakeName) {
    return "SELECT COUNT(1) FROM "
        + ScimErrorHistoryMapper.TABLE_NAME
        + " seh WHERE seh.metalake_id IN ("
        + " SELECT mm.metalake_id FROM "
        + MetalakeMetaMapper.TABLE_NAME
        + " mm WHERE mm.metalake_name = #{metalakeName} AND mm.deleted_at = 0)";
  }

  public String deleteByCreatedAtBefore(
      @Param("legacyTimeline") Long legacyTimeline, @Param("limit") int limit) {
    return "DELETE FROM "
        + ScimErrorHistoryMapper.TABLE_NAME
        + " WHERE created_at < #{legacyTimeline} LIMIT #{limit}";
  }
}
