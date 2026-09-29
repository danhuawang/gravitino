/*
 * Copyright 2026 Datastrato Inc.
 */
package com.datastrato.gravitino.authorization.mapper;

import com.datastrato.gravitino.authorization.mapper.provider.base.DatastratoGroupMetaBaseSQLProvider;
import com.datastrato.gravitino.authorization.po.IdpUserGroupRelInsertPO;
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

  /**
   * Lists all active Local IdP group names from {@code idp_group_meta}.
   *
   * @return MyBatis SQL ordered by group name.
   */
  public static String listIdpGroupNames() {
    return getProvider().listIdpGroupNames();
  }

  public static String listGroupsByMetalakeWithOrigin(@Param("metalakeName") String metalakeName) {
    return getProvider().listGroupsByMetalakeWithOrigin(metalakeName);
  }

  public static String getGroupByMetalakeWithOrigin(
      @Param("metalakeName") String metalakeName, @Param("groupName") String groupName) {
    return getProvider().getGroupByMetalakeWithOrigin(metalakeName, groupName);
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

  public static String listDirectoryGroups() {
    return getProvider().listDirectoryGroups();
  }

  public static String getDirectoryGroup(
      @Param("groupName") String groupName, @Param("originCode") int originCode) {
    return getProvider().getDirectoryGroup(groupName, originCode);
  }

  public static String listMetalakeOnlyDirectoryGroupAuditInfos(
      @Param("groupName") String groupName) {
    return getProvider().listMetalakeOnlyDirectoryGroupAuditInfos(groupName);
  }

  public static String listDirectoryGroupsForUser(
      @Param("userName") String userName, @Param("originCode") int originCode) {
    return getProvider().listDirectoryGroupsForUser(userName, originCode);
  }

  public static String selectIdpGroupNamesByNames(@Param("groupNames") List<String> groupNames) {
    return getProvider().selectIdpGroupNamesByNames(groupNames);
  }

  /**
   * Builds SQL that selects requested group names from all identity stores.
   *
   * @param groupNames Group names to check.
   * @return MyBatis SQL for the configured database.
   */
  public static String selectIdentityGroupNamesByNames(
      @Param("groupNames") List<String> groupNames) {
    return getProvider().selectIdentityGroupNamesByNames(groupNames);
  }

  public static String selectIdpUserIdsByNames(@Param("userNames") List<String> userNames) {
    return getProvider().selectIdpUserIdsByNames(userNames);
  }

  public static String insertIdpGroup(
      @Param("groupId") long groupId,
      @Param("groupName") String groupName,
      @Param("groupComment") String groupComment,
      @Param("auditInfo") String auditInfo) {
    return getProvider().insertIdpGroup(groupId, groupName, groupComment, auditInfo);
  }

  public static String batchInsertIdpUserGroupRels(
      @Param("relations") List<IdpUserGroupRelInsertPO> relations) {
    return getProvider().batchInsertIdpUserGroupRels(relations);
  }

  private static DatastratoGroupMetaBaseSQLProvider getProvider() {
    String databaseId =
        SqlSessionFactoryHelper.getInstance()
            .getSqlSessionFactory()
            .getConfiguration()
            .getDatabaseId();
    return PROVIDERS.get(JDBCBackendType.fromString(databaseId));
  }

  static class DatastratoGroupMetaMySQLProvider extends DatastratoGroupMetaBaseSQLProvider {
    @Override
    protected String nullLongLiteral() {
      return "CAST(NULL AS SIGNED)";
    }
  }

  static class DatastratoGroupMetaH2Provider extends DatastratoGroupMetaBaseSQLProvider {}

  static class DatastratoGroupMetaPostgreSQLProvider extends DatastratoGroupMetaBaseSQLProvider {
    @Override
    protected String jsonArrayAgg(String expr) {
      return "JSON_AGG(" + expr + ")";
    }

    /** Cast metalake-only {@code originCode} so UNION ALL matches integer origin literals on PG. */
    @Override
    protected String nullOriginCodeLiteral() {
      return "CAST(NULL AS INTEGER)";
    }
  }
}
