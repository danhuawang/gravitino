/*
 * Copyright 2026 Datastrato Pvt Ltd.
 * This software is licensed under the Apache License version 2.
 */

package com.datastrato.gravitino.scim.storage.mapper;

import com.datastrato.gravitino.scim.storage.mapper.provider.base.ScimUserGroupRelBaseSQLProvider;
import com.datastrato.gravitino.scim.storage.mapper.provider.h2.ScimUserGroupRelH2Provider;
import com.datastrato.gravitino.scim.storage.mapper.provider.postgresql.ScimUserGroupRelPostgreSQLProvider;
import com.google.common.collect.ImmutableMap;
import java.util.List;
import java.util.Map;
import org.apache.gravitino.storage.relational.JDBCBackend.JDBCBackendType;
import org.apache.ibatis.annotations.Param;

/** Factory that selects the SCIM user-group membership SQL provider for the active JDBC backend. */
public class ScimUserGroupRelSQLProviderFactory {
  private static final ScimUserGroupRelBaseSQLProvider MYSQL_PROVIDER =
      new ScimUserGroupRelBaseSQLProvider();
  private static final ScimUserGroupRelBaseSQLProvider H2_PROVIDER =
      new ScimUserGroupRelH2Provider();
  private static final ScimUserGroupRelBaseSQLProvider POSTGRESQL_PROVIDER =
      new ScimUserGroupRelPostgreSQLProvider();
  private static final Map<JDBCBackendType, ScimUserGroupRelBaseSQLProvider> PROVIDER_MAP =
      ImmutableMap.of(
          JDBCBackendType.MYSQL,
          MYSQL_PROVIDER,
          JDBCBackendType.H2,
          H2_PROVIDER,
          JDBCBackendType.POSTGRESQL,
          POSTGRESQL_PROVIDER);

  private ScimUserGroupRelSQLProviderFactory() {}

  public static String selectMembersByGroupId(
      @Param("metalakeName") String metalakeName, @Param("groupId") long groupId) {
    return currentProvider().selectMembersByGroupId(metalakeName, groupId);
  }

  public static String selectGroupNamesByUsername(
      @Param("username") String username, @Param("metalakeName") String metalakeName) {
    return currentProvider().selectGroupNamesByUsername(username, metalakeName);
  }

  public static String insertMemberships(
      @Param("metalakeName") String metalakeName,
      @Param("groupId") long groupId,
      @Param("userIds") List<Long> userIds,
      @Param("auditInfo") String auditInfo,
      @Param("currentVersion") Long currentVersion,
      @Param("lastVersion") Long lastVersion) {
    return currentProvider()
        .insertMemberships(metalakeName, groupId, userIds, auditInfo, currentVersion, lastVersion);
  }

  public static String softDeleteMembersByUserId(
      @Param("metalakeName") String metalakeName, @Param("userId") long userId) {
    return currentProvider().softDeleteMembersByUserId(metalakeName, userId);
  }

  public static String softDeleteMembersByGroupAndUserIds(
      @Param("metalakeName") String metalakeName,
      @Param("groupId") long groupId,
      @Param("userIds") List<Long> userIds) {
    return currentProvider().softDeleteMembersByGroupAndUserIds(metalakeName, groupId, userIds);
  }

  public static String softDeleteMembersByUnavailableMetalake() {
    return currentProvider().softDeleteMembersByUnavailableMetalake();
  }

  public static String softDeleteMembersByGroupId(
      @Param("metalakeName") String metalakeName, @Param("groupId") long groupId) {
    return currentProvider().softDeleteMembersByGroupId(metalakeName, groupId);
  }

  public static String updateMemberUserId(
      @Param("metalakeName") String metalakeName,
      @Param("groupId") long groupId,
      @Param("oldUserId") long oldUserId,
      @Param("newUserId") long newUserId,
      @Param("auditInfo") String auditInfo,
      @Param("currentVersion") Long currentVersion,
      @Param("lastVersion") Long lastVersion) {
    return currentProvider()
        .updateMemberUserId(
            metalakeName, groupId, oldUserId, newUserId, auditInfo, currentVersion, lastVersion);
  }

  public static String deleteByLegacyTimeline(
      @Param("legacyTimeline") Long legacyTimeline, @Param("limit") int limit) {
    return currentProvider().deleteByLegacyTimeline(legacyTimeline, limit);
  }

  static ScimUserGroupRelBaseSQLProvider getProvider(String databaseId) {
    return SQLProviderFactoryHelper.getProvider(
        databaseId, PROVIDER_MAP, ScimUserGroupRelSQLProviderFactory.class);
  }

  private static ScimUserGroupRelBaseSQLProvider currentProvider() {
    return SQLProviderFactoryHelper.currentProvider(
        PROVIDER_MAP, ScimUserGroupRelSQLProviderFactory.class);
  }
}
