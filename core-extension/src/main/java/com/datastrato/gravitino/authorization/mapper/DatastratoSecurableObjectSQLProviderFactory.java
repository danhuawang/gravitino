/*
 * Copyright 2026 Datastrato Pvt Ltd.
 */
package com.datastrato.gravitino.authorization.mapper;

import com.datastrato.gravitino.authorization.mapper.provider.base.DatastratoSecurableObjectBaseSQLProvider;
import com.google.common.collect.ImmutableMap;
import java.util.List;
import java.util.Map;
import org.apache.gravitino.storage.relational.JDBCBackend.JDBCBackendType;
import org.apache.gravitino.storage.relational.session.SqlSessionFactoryHelper;
import org.apache.ibatis.annotations.Param;

/** SQL provider factory for enterprise authorization securable object queries. */
public class DatastratoSecurableObjectSQLProviderFactory {

  private static final Map<JDBCBackendType, DatastratoSecurableObjectBaseSQLProvider> PROVIDERS =
      ImmutableMap.of(
          JDBCBackendType.MYSQL, new DatastratoSecurableObjectMySQLProvider(),
          JDBCBackendType.H2, new DatastratoSecurableObjectH2Provider(),
          JDBCBackendType.POSTGRESQL, new DatastratoSecurableObjectPostgreSQLProvider());

  private DatastratoSecurableObjectSQLProviderFactory() {}

  /**
   * Lists securable objects by role ids.
   *
   * @param roleIds The role ids.
   * @return The list securable objects SQL.
   */
  public static String listSecurableObjectsByRoleIds(@Param("roleIds") List<Long> roleIds) {
    return getProvider().listSecurableObjectsByRoleIds(roleIds);
  }

  private static DatastratoSecurableObjectBaseSQLProvider getProvider() {
    String databaseId =
        SqlSessionFactoryHelper.getInstance()
            .getSqlSessionFactory()
            .getConfiguration()
            .getDatabaseId();
    return PROVIDERS.get(JDBCBackendType.fromString(databaseId));
  }

  static class DatastratoSecurableObjectMySQLProvider
      extends DatastratoSecurableObjectBaseSQLProvider {}

  static class DatastratoSecurableObjectH2Provider
      extends DatastratoSecurableObjectBaseSQLProvider {}

  static class DatastratoSecurableObjectPostgreSQLProvider
      extends DatastratoSecurableObjectBaseSQLProvider {}
}
