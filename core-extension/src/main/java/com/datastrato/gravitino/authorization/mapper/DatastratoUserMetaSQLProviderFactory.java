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

/** SQL provider factory for enterprise user_meta reads/updates and IdP origin checks. */
public class DatastratoUserMetaSQLProviderFactory {

  private static final Map<JDBCBackendType, DatastratoUserMetaBaseSQLProvider> PROVIDERS =
      ImmutableMap.of(
          JDBCBackendType.MYSQL, new DatastratoUserMetaMySQLProvider(),
          JDBCBackendType.H2, new DatastratoUserMetaH2Provider(),
          JDBCBackendType.POSTGRESQL, new DatastratoUserMetaPostgreSQLProvider());

  private DatastratoUserMetaSQLProviderFactory() {}

  public static String listUserMetasByMetalakeNameAndNames(
      @Param("metalakeName") String metalakeName, @Param("userNames") List<String> userNames) {
    return getProvider().listUserMetasByMetalakeNameAndNames(metalakeName, userNames);
  }

  public static String batchUpdateEnabledByMetalakeNameAndNames(
      @Param("metalakeName") String metalakeName,
      @Param("userNames") List<String> userNames,
      @Param("enabled") boolean enabled) {
    return getProvider().batchUpdateEnabledByMetalakeNameAndNames(metalakeName, userNames, enabled);
  }

  public static String listUsersWithMetalakeStatus(@Param("metalakeName") String metalakeName) {
    return getProvider().listUsersWithMetalakeStatus(metalakeName);
  }

  public static String getUserByMetalakeWithOrigin(
      @Param("metalakeName") String metalakeName, @Param("userName") String userName) {
    return getProvider().getUserByMetalakeWithOrigin(metalakeName, userName);
  }

  public static String listUsersByMetalakeWithOrigin(@Param("metalakeName") String metalakeName) {
    return getProvider().listUsersByMetalakeWithOrigin(metalakeName);
  }

  public static String listUsersByMetalakeAndNamesWithOrigin(
      @Param("metalakeName") String metalakeName, @Param("userNames") List<String> userNames) {
    return getProvider().listUsersByMetalakeAndNamesWithOrigin(metalakeName, userNames);
  }

  public static String listUsersForMetalakeGroupWithOrigin(
      @Param("metalakeName") String metalakeName, @Param("groupName") String groupName) {
    return getProvider().listUsersForMetalakeGroupWithOrigin(metalakeName, groupName);
  }

  public static String countUsersByEnabledByMetalake(@Param("metalakeName") String metalakeName) {
    return getProvider().countUsersByEnabledByMetalake(metalakeName);
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

  static class DatastratoUserMetaPostgreSQLProvider extends DatastratoUserMetaBaseSQLProvider {
    @Override
    protected String jsonArrayAgg(String expr) {
      return "JSON_AGG(" + expr + ")";
    }
  }
}
