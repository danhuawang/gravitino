/*
 * Copyright 2026 Datastrato Pvt Ltd.
 * This software is licensed under the Apache License version 2.
 */

package com.datastrato.gravitino.scim.storage.mapper;

import com.datastrato.gravitino.scim.storage.mapper.provider.base.ScimErrorHistoryBaseSQLProvider;
import com.datastrato.gravitino.scim.storage.mapper.provider.h2.ScimErrorHistoryH2Provider;
import com.datastrato.gravitino.scim.storage.mapper.provider.postgresql.ScimErrorHistoryPostgreSQLProvider;
import com.datastrato.gravitino.scim.storage.po.ScimErrorHistoryPO;
import com.google.common.collect.ImmutableMap;
import java.util.Map;
import org.apache.gravitino.storage.relational.JDBCBackend.JDBCBackendType;
import org.apache.ibatis.annotations.Param;

/** Factory that selects the SCIM error history SQL provider for the active JDBC backend. */
public class ScimErrorHistorySQLProviderFactory {
  private static final ScimErrorHistoryBaseSQLProvider MYSQL_PROVIDER =
      new ScimErrorHistoryBaseSQLProvider();
  private static final ScimErrorHistoryBaseSQLProvider H2_PROVIDER =
      new ScimErrorHistoryH2Provider();
  private static final ScimErrorHistoryBaseSQLProvider POSTGRESQL_PROVIDER =
      new ScimErrorHistoryPostgreSQLProvider();
  private static final Map<JDBCBackendType, ScimErrorHistoryBaseSQLProvider> PROVIDER_MAP =
      ImmutableMap.of(
          JDBCBackendType.MYSQL,
          MYSQL_PROVIDER,
          JDBCBackendType.H2,
          H2_PROVIDER,
          JDBCBackendType.POSTGRESQL,
          POSTGRESQL_PROVIDER);

  private ScimErrorHistorySQLProviderFactory() {}

  public static String insert(@Param("errorHistory") ScimErrorHistoryPO errorHistory) {
    return currentProvider().insert(errorHistory);
  }

  public static String selectByErrorId(@Param("errorId") Long errorId) {
    return currentProvider().selectByErrorId(errorId);
  }

  /**
   * Builds the metalake-scoped count statement for the active backend.
   *
   * @param metalakeName target metalake name
   * @return SQL statement
   */
  public static String countByMetalake(@Param("metalakeName") String metalakeName) {
    return currentProvider().countByMetalake(metalakeName);
  }

  public static String deleteByCreatedAtBefore(
      @Param("legacyTimeline") Long legacyTimeline, @Param("limit") int limit) {
    return currentProvider().deleteByCreatedAtBefore(legacyTimeline, limit);
  }

  static ScimErrorHistoryBaseSQLProvider getProvider(String databaseId) {
    return SQLProviderFactoryHelper.getProvider(
        databaseId, PROVIDER_MAP, ScimErrorHistorySQLProviderFactory.class);
  }

  private static ScimErrorHistoryBaseSQLProvider currentProvider() {
    return SQLProviderFactoryHelper.currentProvider(
        PROVIDER_MAP, ScimErrorHistorySQLProviderFactory.class);
  }
}
