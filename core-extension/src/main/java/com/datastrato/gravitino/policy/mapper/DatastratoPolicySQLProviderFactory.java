/*
 * Copyright 2026 Datastrato Pvt Ltd.
 * This software is licensed under the Apache License version 2.
 */
package com.datastrato.gravitino.policy.mapper;

import com.datastrato.gravitino.policy.mapper.provider.base.DatastratoPolicyBaseSQLProvider;
import com.google.common.collect.ImmutableMap;
import java.util.List;
import java.util.Map;
import org.apache.gravitino.storage.relational.JDBCBackend.JDBCBackendType;
import org.apache.gravitino.storage.relational.session.SqlSessionFactoryHelper;
import org.apache.ibatis.annotations.Param;

/** SQL provider factory for enterprise policy queries. */
public class DatastratoPolicySQLProviderFactory {

  private static final Map<JDBCBackendType, DatastratoPolicyBaseSQLProvider> PROVIDERS =
      ImmutableMap.of(
          JDBCBackendType.MYSQL, new DatastratoPolicyMySQLProvider(),
          JDBCBackendType.H2, new DatastratoPolicyH2Provider(),
          JDBCBackendType.POSTGRESQL, new DatastratoPolicyPostgreSQLProvider());

  private DatastratoPolicySQLProviderFactory() {}

  /**
   * Generates SQL to list associated metadata objects for selected policies under a metalake.
   *
   * @param metalakeName The metalake name.
   * @param policyIds The policy IDs to query.
   * @return The associated metadata objects SQL.
   */
  public static String listAssociatedMetadataObjectsForPolicies(
      @Param("metalakeName") String metalakeName, @Param("policyIds") List<Long> policyIds) {
    return getProvider().listAssociatedMetadataObjectsForPolicies(metalakeName, policyIds);
  }

  private static DatastratoPolicyBaseSQLProvider getProvider() {
    String databaseId =
        SqlSessionFactoryHelper.getInstance()
            .getSqlSessionFactory()
            .getConfiguration()
            .getDatabaseId();
    return PROVIDERS.get(JDBCBackendType.fromString(databaseId));
  }

  static class DatastratoPolicyMySQLProvider extends DatastratoPolicyBaseSQLProvider {}

  static class DatastratoPolicyH2Provider extends DatastratoPolicyBaseSQLProvider {}

  static class DatastratoPolicyPostgreSQLProvider extends DatastratoPolicyBaseSQLProvider {}
}
