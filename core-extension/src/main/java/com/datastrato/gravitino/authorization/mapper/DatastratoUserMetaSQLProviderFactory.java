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

  public static String selectJitUserNamesByNames(@Param("userNames") List<String> userNames) {
    return getProvider().selectJitUserNamesByNames(userNames);
  }

  /**
   * Builds SQL that selects requested usernames from all identity stores.
   *
   * @param userNames Usernames to check.
   * @return MyBatis SQL for the configured database.
   */
  public static String selectIdentityUserNamesByNames(@Param("userNames") List<String> userNames) {
    return getProvider().selectIdentityUserNamesByNames(userNames);
  }

  /**
   * Lists identity-store origin codes for a username.
   *
   * @param userName Username.
   * @return MyBatis SQL selecting {@code originCode}.
   */
  public static String selectUserOriginCodes(@Param("userName") String userName) {
    return getProvider().selectUserOriginCodes(userName);
  }

  /**
   * Lists metalake membership and direct role names for a username.
   *
   * @param userName Username.
   * @return MyBatis SQL selecting {@code metalakeName} and {@code roleName}.
   */
  public static String listUserRolesByMetalake(@Param("userName") String userName) {
    return getProvider().listUserRolesByMetalake(userName);
  }

  /**
   * Lists metalake names owned by a username.
   *
   * @param userName Username.
   * @return MyBatis SQL selecting metalake names.
   */
  public static String listOwnedMetalakeNames(@Param("userName") String userName) {
    return getProvider().listOwnedMetalakeNames(userName);
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
      @Param("userNames") List<String> userNames,
      @Param("enabled") boolean enabled,
      @Param("lastModifier") String lastModifier,
      @Param("lastModifiedTime") String lastModifiedTime) {
    return getProvider()
        .batchUpdateIdpUserEnabledByUserNames(userNames, enabled, lastModifier, lastModifiedTime);
  }

  public static String selectIdpGroupIdsByNames(@Param("groupNames") List<String> groupNames) {
    return getProvider().selectIdpGroupIdsByNames(groupNames);
  }

  public static String insertIdpUser(
      @Param("userId") long userId,
      @Param("userName") String userName,
      @Param("passwordHash") String passwordHash,
      @Param("enabled") boolean enabled,
      @Param("auditInfo") String auditInfo) {
    return getProvider().insertIdpUser(userId, userName, passwordHash, enabled, auditInfo);
  }

  public static String batchInsertIdpUserGroupRels(
      @Param("relations") List<IdpUserGroupRelInsertPO> relations) {
    return getProvider().batchInsertIdpUserGroupRels(relations);
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

  public static String getDirectoryUser(
      @Param("userName") String userName, @Param("originCode") int originCode) {
    return getProvider().getDirectoryUser(userName, originCode);
  }

  public static String listMetalakeOnlyDirectoryUserAuditInfos(@Param("userName") String userName) {
    return getProvider().listMetalakeOnlyDirectoryUserAuditInfos(userName);
  }

  public static String listDirectoryUsersForGroup(
      @Param("groupName") String groupName, @Param("originCode") int originCode) {
    return getProvider().listDirectoryUsersForGroup(groupName, originCode);
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

  static class DatastratoUserMetaH2Provider extends DatastratoUserMetaBaseSQLProvider {
    /**
     * H2 2.x has no {@code JSON_SET}; use the Java merge alias registered by {@link
     * DatastratoUserMetaMapper#createH2MergeIdpUserLastModifiedAuditAlias()}.
     */
    @Override
    protected String mergeIdpUserLastModifiedAuditInfoExpression() {
      return "MERGE_IDP_USER_LAST_MODIFIED_AUDIT(audit_info, #{lastModifier}, #{lastModifiedTime})";
    }
  }

  static class DatastratoUserMetaPostgreSQLProvider extends DatastratoUserMetaBaseSQLProvider {
    /**
     * Merge last-modifier fields with {@code jsonb_set}, preserving other audit keys when present.
     */
    @Override
    protected String mergeIdpUserLastModifiedAuditInfoExpression() {
      return "(CASE"
          + " WHEN audit_info IS NULL OR btrim(audit_info) = '' THEN"
          + " jsonb_build_object('lastModifier', #{lastModifier}, 'lastModifiedTime',"
          + " #{lastModifiedTime})"
          + " ELSE jsonb_set("
          + " jsonb_set(audit_info::jsonb, '{lastModifier}', to_jsonb(#{lastModifier}::text), true),"
          + " '{lastModifiedTime}', to_jsonb(#{lastModifiedTime}::text), true)"
          + " END)::text";
    }

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

    /**
     * PostgreSQL {@code scim_user_meta.enabled} is {@code SMALLINT}, so binding the Java {@code
     * boolean} parameter directly fails with {@code 42804: column "enabled" is of type smallint but
     * expression is of type boolean}. Map it to the stored integer encoding instead.
     */
    @Override
    protected String scimUserEnabledBindValue(String enabledParamRef) {
      return "CASE WHEN " + enabledParamRef + " THEN 1 ELSE 0 END";
    }
    /** Cast metalake-only {@code originCode} so UNION ALL matches integer origin literals on PG. */
    @Override
    protected String nullOriginCodeLiteral() {
      return "CAST(NULL AS INTEGER)";
    }
  }
}
