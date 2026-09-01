/*
 * Copyright 2026 Datastrato Inc.
 */

package com.datastrato.gravitino.scim.v2.storage.mapper;

import com.datastrato.gravitino.scim.v2.storage.mapper.provider.base.ScimUserGroupRelBaseSQLProvider;
import com.datastrato.gravitino.scim.v2.storage.mapper.provider.h2.ScimUserGroupRelH2Provider;
import com.datastrato.gravitino.scim.v2.storage.mapper.provider.postgresql.ScimUserGroupRelPostgreSQLProvider;
import com.google.common.collect.ImmutableMap;
import java.util.List;
import java.util.Map;
import org.apache.gravitino.storage.relational.JDBCBackend.JDBCBackendType;
import org.apache.ibatis.annotations.Param;

/** Factory for SCIM v2 user-group membership SQL providers. */
public class ScimUserGroupRelSQLProviderFactory {
  private static final ScimUserGroupRelBaseSQLProvider MYSQL_PROVIDER =
      new ScimUserGroupRelBaseSQLProvider();
  private static final ScimUserGroupRelBaseSQLProvider H2_PROVIDER =
      new ScimUserGroupRelH2Provider();
  private static final ScimUserGroupRelBaseSQLProvider POSTGRESQL_PROVIDER =
      new ScimUserGroupRelPostgreSQLProvider();
  private static final Map<JDBCBackendType, ScimUserGroupRelBaseSQLProvider> PROVIDER_MAP =
      ImmutableMap.of(
          JDBCBackendType.MYSQL, MYSQL_PROVIDER,
          JDBCBackendType.H2, H2_PROVIDER,
          JDBCBackendType.POSTGRESQL, POSTGRESQL_PROVIDER);

  private ScimUserGroupRelSQLProviderFactory() {}

  public static String selectMembersByGroupId(@Param("groupId") long groupId) {
    return currentProvider().selectMembersByGroupId(groupId);
  }

  public static String selectGroupNamesByUsername(@Param("username") String username) {
    return currentProvider().selectGroupNamesByUsername(username);
  }

  public static String insertMemberships(
      @Param("groupId") long groupId,
      @Param("userIds") List<Long> userIds,
      @Param("currentVersion") Long currentVersion,
      @Param("lastVersion") Long lastVersion) {
    return currentProvider().insertMemberships(groupId, userIds, currentVersion, lastVersion);
  }

  public static String softDeleteMembersByUserId(@Param("userId") long userId) {
    return currentProvider().softDeleteMembersByUserId(userId);
  }

  public static String softDeleteMembersByGroupAndUserIds(
      @Param("groupId") long groupId, @Param("userIds") List<Long> userIds) {
    return currentProvider().softDeleteMembersByGroupAndUserIds(groupId, userIds);
  }

  public static String softDeleteMembersByGroupId(@Param("groupId") long groupId) {
    return currentProvider().softDeleteMembersByGroupId(groupId);
  }

  public static String softDeleteOrphanMemberships() {
    return currentProvider().softDeleteOrphanMemberships();
  }

  public static String updateMemberUserId(
      @Param("groupId") long groupId,
      @Param("oldUserId") long oldUserId,
      @Param("newUserId") long newUserId,
      @Param("currentVersion") Long currentVersion,
      @Param("lastVersion") Long lastVersion) {
    return currentProvider()
        .updateMemberUserId(groupId, oldUserId, newUserId, currentVersion, lastVersion);
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
