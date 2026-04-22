/*
 * Copyright 2024 Datastrato Pvt Ltd.
 * This software is licensed under the Apache License version 2.
 */
package com.datastrato.gravitino.license.mapper;

import org.apache.ibatis.annotations.DeleteProvider;
import org.apache.ibatis.annotations.InsertProvider;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.SelectProvider;
import org.apache.ibatis.annotations.UpdateProvider;

public interface LicenseNodeMapper {

  @InsertProvider(type = LicenseNodeSQLProviderFactory.class, method = "upsertNode")
  void upsertNode(
      @Param("nodeId") String nodeId,
      @Param("registeredAt") long registeredAt,
      @Param("now") long now);

  @DeleteProvider(type = LicenseNodeSQLProviderFactory.class, method = "deleteNode")
  void deleteNode(@Param("nodeId") String nodeId);

  @DeleteProvider(type = LicenseNodeSQLProviderFactory.class, method = "deleteStaleNodes")
  void deleteStaleNodes(@Param("staleThresholdMs") long staleThresholdMs);

  @UpdateProvider(type = LicenseNodeSQLProviderFactory.class, method = "updateHeartbeat")
  void updateHeartbeat(@Param("nodeId") String nodeId, @Param("now") long now);

  @SelectProvider(type = LicenseNodeSQLProviderFactory.class, method = "countActiveNodes")
  int countActiveNodes();
}
