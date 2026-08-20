/*
 * Copyright 2026 Datastrato Pvt Ltd.
 * This software is licensed under the Apache License version 2.
 */
package com.datastrato.gravitino.authorization.mapper;

import com.datastrato.gravitino.authorization.mapper.provider.base.DatastratoUserMetaBaseSQLProvider;
import com.google.common.collect.ImmutableMap;
import java.util.List;
import java.util.Map;
import org.apache.gravitino.storage.relational.JDBCBackend.JDBCBackendType;
import org.apache.gravitino.storage.relational.session.SqlSessionFactoryHelper;
import org.apache.ibatis.annotations.Param;

/** SQL provider factory for enterprise batch user_meta updates. */
public class DatastratoUserMetaSQLProviderFactory {

  private static final Map<JDBCBackendType, DatastratoUserMetaBaseSQLProvider> PROVIDERS =
      ImmutableMap.of(
          JDBCBackendType.MYSQL, new DatastratoUserMetaMySQLProvider(),
          JDBCBackendType.H2, new DatastratoUserMetaH2Provider(),
          JDBCBackendType.POSTGRESQL, new DatastratoUserMetaPostgreSQLProvider());

  private DatastratoUserMetaSQLProviderFactory() {}

  /**
   * Builds the list-users-by-names SQL.
   *
   * @param metalakeName The metalake name.
   * @param userNames Distinct user names.
   * @return MyBatis script SQL.
   */
  public static String listUserMetasByMetalakeNameAndNames(
      @Param("metalakeName") String metalakeName, @Param("userNames") List<String> userNames) {
    return getProvider().listUserMetasByMetalakeNameAndNames(metalakeName, userNames);
  }

  /**
   * Builds the batch update enabled SQL.
   *
   * @param metalakeName The metalake name.
   * @param userNames Distinct user names.
   * @param enabled Target enabled value.
   * @return MyBatis script SQL.
   */
  public static String batchUpdateEnabledByMetalakeNameAndNames(
      @Param("metalakeName") String metalakeName,
      @Param("userNames") List<String> userNames,
      @Param("enabled") boolean enabled) {
    return getProvider().batchUpdateEnabledByMetalakeNameAndNames(metalakeName, userNames, enabled);
  }

  private static DatastratoUserMetaBaseSQLProvider getProvider() {
    String databaseId =
        SqlSessionFactoryHelper.getInstance()
            .getSqlSessionFactory()
            .getConfiguration()
            .getDatabaseId();
    return PROVIDERS.get(JDBCBackendType.fromString(databaseId));
  }

  static class DatastratoUserMetaMySQLProvider extends DatastratoUserMetaBaseSQLProvider {}

  static class DatastratoUserMetaH2Provider extends DatastratoUserMetaBaseSQLProvider {}

  static class DatastratoUserMetaPostgreSQLProvider extends DatastratoUserMetaBaseSQLProvider {}
}
