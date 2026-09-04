/*
 * Copyright 2026 Datastrato Inc.
 */
package com.datastrato.gravitino.metalake.mapper;

import org.apache.gravitino.storage.relational.mapper.CatalogMetaMapper;
import org.apache.gravitino.storage.relational.mapper.MetalakeMetaMapper;
import org.apache.gravitino.storage.relational.mapper.RoleMetaMapper;
import org.apache.gravitino.storage.relational.mapper.UserMetaMapper;
import org.apache.ibatis.annotations.Param;

/** SQL provider for Enterprise metalake summary counts. */
public final class MetalakeSummarySQLProvider {
  private MetalakeSummarySQLProvider() {}

  /**
   * Returns portable SQL that loads all active child-entity counts in one row.
   *
   * @param metalakeName The metalake name.
   * @return The summary count SQL.
   */
  public static String loadCounts(@Param("metalakeName") String metalakeName) {
    return "SELECT"
        + " (SELECT COUNT(*) FROM "
        + CatalogMetaMapper.TABLE_NAME
        + " cm WHERE cm.metalake_id = mm.metalake_id AND cm.deleted_at = 0)"
        + " AS catalog_count,"
        + " (SELECT COUNT(*) FROM "
        + UserMetaMapper.USER_TABLE_NAME
        + " um WHERE um.metalake_id = mm.metalake_id AND um.deleted_at = 0)"
        + " AS user_count,"
        + " (SELECT COUNT(*) FROM "
        + RoleMetaMapper.ROLE_TABLE_NAME
        + " rm WHERE rm.metalake_id = mm.metalake_id AND rm.deleted_at = 0)"
        + " AS role_count"
        + " FROM "
        + MetalakeMetaMapper.TABLE_NAME
        + " mm WHERE mm.metalake_name = #{metalakeName} AND mm.deleted_at = 0";
  }
}
