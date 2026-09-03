/*
 * Copyright 2026 Datastrato Inc.
 */

package com.datastrato.gravitino.scim.storage.mapper;

import com.datastrato.gravitino.scim.storage.mapper.provider.base.ScimGroupMetaBaseSQLProvider;
import com.datastrato.gravitino.scim.storage.mapper.provider.h2.ScimGroupMetaH2Provider;
import com.datastrato.gravitino.scim.storage.mapper.provider.postgresql.ScimGroupMetaPostgreSQLProvider;
import com.google.common.collect.ImmutableMap;
import java.util.Map;
import org.apache.gravitino.storage.relational.JDBCBackend.JDBCBackendType;
import org.apache.ibatis.annotations.Param;

/** Factory that selects the SCIM group metadata SQL provider for the active JDBC backend. */
public class ScimGroupMetaSQLProviderFactory {
  private static final ScimGroupMetaBaseSQLProvider MYSQL_PROVIDER =
      new ScimGroupMetaBaseSQLProvider();
  private static final ScimGroupMetaBaseSQLProvider H2_PROVIDER = new ScimGroupMetaH2Provider();
  private static final ScimGroupMetaBaseSQLProvider POSTGRESQL_PROVIDER =
      new ScimGroupMetaPostgreSQLProvider();
  private static final Map<JDBCBackendType, ScimGroupMetaBaseSQLProvider> PROVIDER_MAP =
      ImmutableMap.of(
          JDBCBackendType.MYSQL, MYSQL_PROVIDER,
          JDBCBackendType.H2, H2_PROVIDER,
          JDBCBackendType.POSTGRESQL, POSTGRESQL_PROVIDER);

  private ScimGroupMetaSQLProviderFactory() {}

  public static String insert(
      @Param("groupMeta") com.datastrato.gravitino.scim.storage.po.ScimGroupMetaPO groupMeta) {
    return currentProvider().insert(groupMeta);
  }

  public static String selectByExternalId(@Param("externalId") String externalId) {
    return currentProvider().selectByExternalId(externalId);
  }

  public static String selectByGroupName(@Param("groupName") String groupName) {
    return currentProvider().selectByGroupName(groupName);
  }

  /**
   * Delegates case-insensitive group-name lookup SQL to the active backend provider.
   *
   * @param groupName group name to match ignoring case
   * @return SELECT SQL
   */
  public static String selectByGroupNameIgnoreCase(@Param("groupName") String groupName) {
    return currentProvider().selectByGroupNameIgnoreCase(groupName);
  }

  public static String selectByGroupId(@Param("groupId") long groupId) {
    return currentProvider().selectByGroupId(groupId);
  }

  public static String listGroups(@Param("offset") int offset, @Param("limit") int limit) {
    return currentProvider().listGroups(offset, limit);
  }

  public static String countGroups() {
    return currentProvider().countGroups();
  }

  public static String updateExternalId(
      @Param("groupId") long groupId, @Param("externalId") String externalId) {
    return currentProvider().updateExternalId(groupId, externalId);
  }

  public static String softDeleteByGroupId(@Param("groupId") long groupId) {
    return currentProvider().softDeleteByGroupId(groupId);
  }

  public static String softDeleteByExternalId(@Param("externalId") String externalId) {
    return currentProvider().softDeleteByExternalId(externalId);
  }

  public static String deleteByLegacyTimeline(
      @Param("legacyTimeline") Long legacyTimeline, @Param("limit") int limit) {
    return currentProvider().deleteByLegacyTimeline(legacyTimeline, limit);
  }

  static ScimGroupMetaBaseSQLProvider getProvider(String databaseId) {
    return SQLProviderFactoryHelper.getProvider(
        databaseId, PROVIDER_MAP, ScimGroupMetaSQLProviderFactory.class);
  }

  private static ScimGroupMetaBaseSQLProvider currentProvider() {
    return SQLProviderFactoryHelper.currentProvider(
        PROVIDER_MAP, ScimGroupMetaSQLProviderFactory.class);
  }
}
