/*
 * Copyright 2026 Datastrato Inc.
 */
package com.datastrato.gravitino.metalake.mapper;

import com.datastrato.gravitino.metalake.MetalakeSummaryCounts;
import javax.annotation.Nullable;
import org.apache.ibatis.annotations.Arg;
import org.apache.ibatis.annotations.ConstructorArgs;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.SelectProvider;

/** MyBatis mapper for Enterprise metalake summary counts. */
public interface MetalakeSummaryMapper {

  /**
   * Loads active catalog, user, and role counts for a metalake.
   *
   * @param metalakeName The metalake name.
   * @return The counts, or {@code null} when the metalake does not exist.
   */
  @ConstructorArgs({
    @Arg(column = "catalog_count", javaType = long.class),
    @Arg(column = "user_count", javaType = long.class),
    @Arg(column = "role_count", javaType = long.class)
  })
  @SelectProvider(type = MetalakeSummarySQLProvider.class, method = "loadCounts")
  @Nullable
  MetalakeSummaryCounts loadCounts(@Param("metalakeName") String metalakeName);
}
