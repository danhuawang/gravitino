/*
 * Copyright 2024 Datastrato Inc.
 */
package com.datastrato.gravitino.license.mapper;

import com.datastrato.gravitino.license.mapper.provider.base.LicenseNodeBaseSQLProvider;
import com.datastrato.gravitino.license.mapper.provider.h2.LicenseNodeH2SQLProvider;
import com.datastrato.gravitino.license.mapper.provider.mysql.LicenseNodeMySQLProvider;
import com.datastrato.gravitino.license.mapper.provider.postgresql.LicenseNodePostgreSQLProvider;
import com.google.common.collect.ImmutableMap;
import java.util.Map;
import org.apache.gravitino.storage.relational.JDBCBackend.JDBCBackendType;
import org.apache.gravitino.storage.relational.session.SqlSessionFactoryHelper;

public class LicenseNodeSQLProviderFactory {

  private static final Map<JDBCBackendType, LicenseNodeBaseSQLProvider> PROVIDERS =
      ImmutableMap.of(
          JDBCBackendType.MYSQL, new LicenseNodeMySQLProvider(),
          JDBCBackendType.H2, new LicenseNodeH2SQLProvider(),
          JDBCBackendType.POSTGRESQL, new LicenseNodePostgreSQLProvider());

  public static String upsertNode() {
    return getProvider().upsertNode();
  }

  public static String deleteNode() {
    return getProvider().deleteNode();
  }

  public static String deleteStaleNodes() {
    return getProvider().deleteStaleNodes();
  }

  public static String countActiveNodes() {
    return getProvider().countActiveNodes();
  }

  public static String rankNode() {
    return getProvider().rankNode();
  }

  private static LicenseNodeBaseSQLProvider getProvider() {
    String databaseId =
        SqlSessionFactoryHelper.getInstance()
            .getSqlSessionFactory()
            .getConfiguration()
            .getDatabaseId();
    return PROVIDERS.get(JDBCBackendType.fromString(databaseId));
  }
}
