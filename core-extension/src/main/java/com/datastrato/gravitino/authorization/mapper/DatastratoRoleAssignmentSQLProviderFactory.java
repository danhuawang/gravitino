/*
 * Copyright 2026 Datastrato Pvt Ltd.
 */
package com.datastrato.gravitino.authorization.mapper;

import com.datastrato.gravitino.authorization.mapper.provider.base.DatastratoRoleAssignmentBaseSQLProvider;
import com.google.common.collect.ImmutableMap;
import java.util.Map;
import org.apache.gravitino.storage.relational.JDBCBackend.JDBCBackendType;
import org.apache.gravitino.storage.relational.session.SqlSessionFactoryHelper;
import org.apache.ibatis.annotations.Param;

/** SQL provider factory for enterprise principal-role assignment queries. */
public class DatastratoRoleAssignmentSQLProviderFactory {

  private static final Map<JDBCBackendType, DatastratoRoleAssignmentBaseSQLProvider> PROVIDERS =
      ImmutableMap.of(
          JDBCBackendType.MYSQL, new DatastratoRoleAssignmentMySQLProvider(),
          JDBCBackendType.H2, new DatastratoRoleAssignmentH2Provider(),
          JDBCBackendType.POSTGRESQL, new DatastratoRoleAssignmentPostgreSQLProvider());

  private DatastratoRoleAssignmentSQLProviderFactory() {}

  /**
   * Lists role assignments for a user.
   *
   * @param metalake The metalake name.
   * @param user The user name.
   * @return The list user role assignments SQL.
   */
  public static String listRoleAssignmentsByUser(
      @Param("metalake") String metalake, @Param("principal") String user) {
    return getProvider().listRoleAssignmentsByUser(metalake, user);
  }

  /**
   * Lists role assignments for a group.
   *
   * @param metalake The metalake name.
   * @param group The group name.
   * @return The list group role assignments SQL.
   */
  public static String listRoleAssignmentsByGroup(
      @Param("metalake") String metalake, @Param("principal") String group) {
    return getProvider().listRoleAssignmentsByGroup(metalake, group);
  }

  private static DatastratoRoleAssignmentBaseSQLProvider getProvider() {
    String databaseId =
        SqlSessionFactoryHelper.getInstance()
            .getSqlSessionFactory()
            .getConfiguration()
            .getDatabaseId();
    return PROVIDERS.get(JDBCBackendType.fromString(databaseId));
  }

  static class DatastratoRoleAssignmentMySQLProvider
      extends DatastratoRoleAssignmentBaseSQLProvider {}

  static class DatastratoRoleAssignmentH2Provider extends DatastratoRoleAssignmentBaseSQLProvider {}

  static class DatastratoRoleAssignmentPostgreSQLProvider
      extends DatastratoRoleAssignmentBaseSQLProvider {}
}
