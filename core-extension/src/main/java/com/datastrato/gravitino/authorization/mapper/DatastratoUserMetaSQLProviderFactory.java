/*
 * Copyright 2026 Datastrato Inc.
 */
package com.datastrato.gravitino.authorization.mapper;

import com.datastrato.gravitino.authorization.mapper.provider.base.DatastratoUserMetaBaseSQLProvider;
import com.datastrato.gravitino.authorization.po.IdpUserGroupRelInsertPO;
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

  public static String selectScimUserNamesByNames(@Param("userNames") List<String> userNames) {
    return getProvider().selectScimUserNamesByNames(userNames);
  }

  public static String batchUpdateScimUserEnabledByUserNames(
      @Param("userNames") List<String> userNames, @Param("enabled") boolean enabled) {
    return getProvider().batchUpdateScimUserEnabledByUserNames(userNames, enabled);
  }

  public static String selectIdpUserNamesByNames(@Param("userNames") List<String> userNames) {
    return getProvider().selectIdpUserNamesByNames(userNames);
  }

  /**
   * Lists all active Local IdP usernames from {@code idp_user_meta}.
   *
   * @return MyBatis SQL ordered by username.
   */
  public static String listIdpUserNames() {
    return getProvider().listIdpUserNames();
  }

  public static String batchUpdateIdpUserEnabledByUserNames(
      @Param("userNames") List<String> userNames, @Param("enabled") boolean enabled) {
    return getProvider().batchUpdateIdpUserEnabledByUserNames(userNames, enabled);
  }

  public static String selectIdpGroupIdsByNames(@Param("groupNames") List<String> groupNames) {
    return getProvider().selectIdpGroupIdsByNames(groupNames);
  }

  public static String insertIdpUser(
      @Param("userId") long userId,
      @Param("userName") String userName,
      @Param("passwordHash") String passwordHash,
      @Param("enabled") boolean enabled) {
    return getProvider().insertIdpUser(userId, userName, passwordHash, enabled);
  }

  public static String batchInsertIdpUserGroupRels(
      @Param("relations") List<IdpUserGroupRelInsertPO> relations) {
    return getProvider().batchInsertIdpUserGroupRels(relations);
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

  public static String listUserWithGroupsPOsByMetalakeName(
      @Param("metalakeName") String metalakeName) {
    return getProvider().listUserWithGroupsPOsByMetalakeName(metalakeName);
  }

  public static String listDirectoryUsers() {
    return getProvider().listDirectoryUsers();
  }

  private static DatastratoUserMetaBaseSQLProvider getProvider() {
    String databaseId =
        SqlSessionFactoryHelper.getInstance()
            .getSqlSessionFactory()
            .getConfiguration()
            .getDatabaseId();
    return PROVIDERS.get(JDBCBackendType.fromString(databaseId));
  }

  static class DatastratoUserMetaMySQLProvider extends DatastratoUserMetaBaseSQLProvider {
    @Override
    protected String nullLongLiteral() {
      return "CAST(NULL AS SIGNED)";
    }
  }

  static class DatastratoUserMetaH2Provider extends DatastratoUserMetaBaseSQLProvider {}

  static class DatastratoUserMetaPostgreSQLProvider extends DatastratoUserMetaBaseSQLProvider {
    @Override
    protected String jsonArrayAgg(String expr) {
      return "JSON_AGG(" + expr + ")";
    }

    /**
     * PostgreSQL {@code scim_user_meta.enabled} is {@code SMALLINT}; cast so UNION ALL with boolean
     * IdP / JIT {@code enabled} columns succeeds.
     */
    @Override
    protected String scimUserEnabledAsBoolean() {
      return "(su.enabled <> 0)";
    }

    /** Cast SCIM {@code SMALLINT} enabled before COALESCE with IdP boolean {@code enabled}. */
    @Override
    protected String coalescedEnabled() {
      return "COALESCE(" + scimUserEnabledAsBoolean() + ", iu.enabled)";
    }
  }
}
