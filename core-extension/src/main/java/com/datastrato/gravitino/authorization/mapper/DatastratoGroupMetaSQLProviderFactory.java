/*
 * Copyright 2026 Datastrato Inc.
 */
package com.datastrato.gravitino.authorization.mapper;

import com.datastrato.gravitino.authorization.mapper.provider.base.DatastratoGroupMetaBaseSQLProvider;
import com.google.common.collect.ImmutableMap;
import java.util.List;
import java.util.Map;
import org.apache.gravitino.storage.relational.JDBCBackend.JDBCBackendType;
import org.apache.gravitino.storage.relational.session.SqlSessionFactoryHelper;
import org.apache.ibatis.annotations.Param;

/** SQL provider factory for enterprise group_meta reads with built-in IdP origin checks. */
public class DatastratoGroupMetaSQLProviderFactory {

  private static final Map<JDBCBackendType, DatastratoGroupMetaBaseSQLProvider> PROVIDERS =
      ImmutableMap.of(
          JDBCBackendType.MYSQL, new DatastratoGroupMetaMySQLProvider(),
          JDBCBackendType.H2, new DatastratoGroupMetaH2Provider(),
          JDBCBackendType.POSTGRESQL, new DatastratoGroupMetaPostgreSQLProvider());

  private DatastratoGroupMetaSQLProviderFactory() {}

  public static String listGroupsWithMetalakeStatus(@Param("metalakeName") String metalakeName) {
    return getProvider().listGroupsWithMetalakeStatus(metalakeName);
  }

  public static String listGroupsByMetalakeWithOrigin(@Param("metalakeName") String metalakeName) {
    return getProvider().listGroupsByMetalakeWithOrigin(metalakeName);
  }

  public static String listGroupsByMetalakeAndNamesWithOrigin(
      @Param("metalakeName") String metalakeName, @Param("groupNames") List<String> groupNames) {
    return getProvider().listGroupsByMetalakeAndNamesWithOrigin(metalakeName, groupNames);
  }

  public static String listGroupsForMetalakeUserWithOrigin(
      @Param("metalakeName") String metalakeName, @Param("userName") String userName) {
    return getProvider().listGroupsForMetalakeUserWithOrigin(metalakeName, userName);
  }

  public static String countGroupsWithEmptyByMetalake(@Param("metalakeName") String metalakeName) {
    return getProvider().countGroupsWithEmptyByMetalake(metalakeName);
  }

  private static DatastratoGroupMetaBaseSQLProvider getProvider() {
    String databaseId =
        SqlSessionFactoryHelper.getInstance()
            .getSqlSessionFactory()
            .getConfiguration()
            .getDatabaseId();
    return PROVIDERS.get(JDBCBackendType.fromString(databaseId));
  }

  static class DatastratoGroupMetaMySQLProvider extends DatastratoGroupMetaBaseSQLProvider {}

  static class DatastratoGroupMetaH2Provider extends DatastratoGroupMetaBaseSQLProvider {}

  static class DatastratoGroupMetaPostgreSQLProvider extends DatastratoGroupMetaBaseSQLProvider {
    @Override
    protected String jsonArrayAgg(String expr) {
      return "JSON_AGG(" + expr + ")";
    }
  }
}
