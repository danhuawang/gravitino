/*
 * Copyright 2024 Datastrato Pvt Ltd.
 * This software is licensed under the Apache License version 2.
 */
package com.datastrato.gravitino.license.mapper;

import org.apache.ibatis.annotations.DeleteProvider;
import org.apache.ibatis.annotations.InsertProvider;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.SelectProvider;

public interface LicenseNodeMapper {

  @InsertProvider(type = LicenseNodeSQLProviderFactory.class, method = "upsertNode")
  void upsertNode(@Param("nodeId") String nodeId);

  @DeleteProvider(type = LicenseNodeSQLProviderFactory.class, method = "deleteNode")
  void deleteNode(@Param("nodeId") String nodeId);

  @DeleteProvider(type = LicenseNodeSQLProviderFactory.class, method = "deleteStaleNodes")
  void deleteStaleNodes(@Param("staleIntervalMs") long staleIntervalMs);

  @SelectProvider(type = LicenseNodeSQLProviderFactory.class, method = "countActiveNodes")
  int countActiveNodes();

  @SelectProvider(type = LicenseNodeSQLProviderFactory.class, method = "rankNode")
  int rankNode(@Param("nodeId") String nodeId);
}
