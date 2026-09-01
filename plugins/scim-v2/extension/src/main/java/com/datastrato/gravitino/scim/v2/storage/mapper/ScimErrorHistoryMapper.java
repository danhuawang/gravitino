/*
 * Copyright 2026 Datastrato Inc.
 */

package com.datastrato.gravitino.scim.v2.storage.mapper;

import com.datastrato.gravitino.scim.v2.storage.po.ScimErrorHistoryPO;
import org.apache.ibatis.annotations.DeleteProvider;
import org.apache.ibatis.annotations.InsertProvider;
import org.apache.ibatis.annotations.Param;

/** MyBatis mapper for {@code v2_scim_error_history}. */
public interface ScimErrorHistoryMapper {
  String TABLE_NAME = "v2_scim_error_history";

  @InsertProvider(type = ScimErrorHistorySQLProviderFactory.class, method = "insert")
  void insert(@Param("errorHistory") ScimErrorHistoryPO errorHistory);

  @org.apache.ibatis.annotations.SelectProvider(
      type = ScimErrorHistorySQLProviderFactory.class,
      method = "countAll")
  Long countAll();

  @DeleteProvider(
      type = ScimErrorHistorySQLProviderFactory.class,
      method = "deleteByCreatedAtBefore")
  Integer deleteByCreatedAtBefore(
      @Param("legacyTimeline") Long legacyTimeline, @Param("limit") int limit);
}
