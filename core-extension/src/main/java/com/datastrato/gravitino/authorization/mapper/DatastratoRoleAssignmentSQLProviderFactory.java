/*
 * Copyright 2026 Datastrato Inc.
 */
package com.datastrato.gravitino.authorization.mapper;

import com.datastrato.gravitino.authorization.mapper.provider.base.DatastratoRoleAssignmentBaseSQLProvider;
import com.google.common.collect.ImmutableMap;
import java.util.List;
import java.util.Map;
import org.apache.gravitino.storage.relational.JDBCBackend.JDBCBackendType;
import org.apache.gravitino.storage.relational.po.GroupRoleRelPO;
import org.apache.gravitino.storage.relational.po.UserRoleRelPO;
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
   * Assigns multiple roles to multiple users.
   *
   * @param assignments The user-role assignments.
   * @return The batch user-role assignment SQL.
   */
  public static String batchAssignRoleToUsers(
      @Param("assignments") List<UserRoleRelPO> assignments) {
    return getProvider().batchAssignRoleToUsers(assignments);
  }

  /**
   * Assigns multiple roles to multiple groups.
   *
   * @param assignments The group-role assignments.
   * @return The batch group-role assignment SQL.
   */
  public static String batchAssignRoleToGroups(
      @Param("assignments") List<GroupRoleRelPO> assignments) {
    return getProvider().batchAssignRoleToGroups(assignments);
  }

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

  /**
   * Lists users assigned to a role.
   *
   * @param metalake The metalake name.
   * @param role The role name.
   * @return The role user assignment SQL.
   */
  public static String listUserAssignmentsByRole(
      @Param("metalake") String metalake, @Param("role") String role) {
    return getProvider().listUserAssignmentsByRole(metalake, role);
  }

  /**
   * Lists groups assigned to a role.
   *
   * @param metalake The metalake name.
   * @param role The role name.
   * @return The role group assignment SQL.
   */
  public static String listGroupAssignmentsByRole(
      @Param("metalake") String metalake, @Param("role") String role) {
    return getProvider().listGroupAssignmentsByRole(metalake, role);
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

  static class DatastratoRoleAssignmentH2Provider extends DatastratoRoleAssignmentBaseSQLProvider {

    /** {@inheritDoc} */
    @Override
    public String batchAssignRoleToUsers(List<UserRoleRelPO> assignments) {
      return castBatchAssignmentParameters(super.batchAssignRoleToUsers(assignments), "userId");
    }

    /** {@inheritDoc} */
    @Override
    public String batchAssignRoleToGroups(List<GroupRoleRelPO> assignments) {
      return castBatchAssignmentParameters(super.batchAssignRoleToGroups(assignments), "groupId");
    }

    private String castBatchAssignmentParameters(String sql, String principalIdProperty) {
      return sql.replace(
              "#{item." + principalIdProperty + "}",
              "CAST(#{item." + principalIdProperty + "} AS BIGINT)")
          .replace("#{item.roleId}", "CAST(#{item.roleId} AS BIGINT)")
          .replace("#{item.auditInfo}", "CAST(#{item.auditInfo} AS VARCHAR)")
          .replace("#{item.currentVersion}", "CAST(#{item.currentVersion} AS BIGINT)")
          .replace("#{item.lastVersion}", "CAST(#{item.lastVersion} AS BIGINT)")
          .replace("#{item.deletedAt}", "CAST(#{item.deletedAt} AS BIGINT)");
    }
  }

  static class DatastratoRoleAssignmentPostgreSQLProvider
      extends DatastratoRoleAssignmentBaseSQLProvider {}
}
