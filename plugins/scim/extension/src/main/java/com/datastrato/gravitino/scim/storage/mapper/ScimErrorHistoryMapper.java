/*
 * Copyright 2026 Datastrato Pvt Ltd.
 * This software is licensed under the Apache License version 2.
 */

package com.datastrato.gravitino.scim.storage.mapper;

import com.datastrato.gravitino.scim.storage.po.ScimErrorHistoryPO;
import org.apache.ibatis.annotations.DeleteProvider;
import org.apache.ibatis.annotations.InsertProvider;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.SelectProvider;

/**
 * A MyBatis mapper for SCIM protocol error history operations.
 *
 * <p>This interface defines the SQL statements MyBatis executes for the SCIM error history store.
 * The SQLs are provided through {@code *Provider} annotations on this mapper interface.
 */
public interface ScimErrorHistoryMapper {
  String TABLE_NAME = "scim_error_history";

  @InsertProvider(type = ScimErrorHistorySQLProviderFactory.class, method = "insert")
  void insert(@Param("errorHistory") ScimErrorHistoryPO errorHistory);

  @SelectProvider(type = ScimErrorHistorySQLProviderFactory.class, method = "selectByErrorId")
  ScimErrorHistoryPO selectByErrorId(@Param("errorId") Long errorId);

  /**
   * Counts error history rows for an active metalake.
   *
   * @param metalakeName target metalake name
   * @return row count
   */
  @SelectProvider(type = ScimErrorHistorySQLProviderFactory.class, method = "countByMetalake")
  Long countByMetalake(@Param("metalakeName") String metalakeName);

  @DeleteProvider(
      type = ScimErrorHistorySQLProviderFactory.class,
      method = "deleteByCreatedAtBefore")
  Integer deleteByCreatedAtBefore(
      @Param("legacyTimeline") Long legacyTimeline, @Param("limit") int limit);
}
