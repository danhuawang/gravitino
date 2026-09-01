/*
 * Copyright 2026 Datastrato Inc.
 */

package com.datastrato.gravitino.scim.v2.storage.mapper;

import com.datastrato.gravitino.scim.v2.storage.mapper.provider.base.ScimUserMetaBaseSQLProvider;
import com.datastrato.gravitino.scim.v2.storage.mapper.provider.h2.ScimUserMetaH2Provider;
import com.datastrato.gravitino.scim.v2.storage.mapper.provider.postgresql.ScimUserMetaPostgreSQLProvider;
import com.google.common.collect.ImmutableMap;
import java.util.Map;
import org.apache.gravitino.storage.relational.JDBCBackend.JDBCBackendType;
import org.apache.ibatis.annotations.Param;

/** Factory that selects the SCIM v2 user metadata SQL provider for the active JDBC backend. */
public class ScimUserMetaSQLProviderFactory {
  private static final ScimUserMetaBaseSQLProvider MYSQL_PROVIDER =
      new ScimUserMetaBaseSQLProvider();
  private static final ScimUserMetaBaseSQLProvider H2_PROVIDER = new ScimUserMetaH2Provider();
  private static final ScimUserMetaBaseSQLProvider POSTGRESQL_PROVIDER =
      new ScimUserMetaPostgreSQLProvider();
  private static final Map<JDBCBackendType, ScimUserMetaBaseSQLProvider> PROVIDER_MAP =
      ImmutableMap.of(
          JDBCBackendType.MYSQL, MYSQL_PROVIDER,
          JDBCBackendType.H2, H2_PROVIDER,
          JDBCBackendType.POSTGRESQL, POSTGRESQL_PROVIDER);

  private ScimUserMetaSQLProviderFactory() {}

  public static String insert(
      @Param("userMeta") com.datastrato.gravitino.scim.v2.storage.po.ScimUserMetaPO userMeta) {
    return currentProvider().insert(userMeta);
  }

  public static String selectByExternalId(@Param("externalId") String externalId) {
    return currentProvider().selectByExternalId(externalId);
  }

  public static String selectByUserName(@Param("userName") String userName) {
    return currentProvider().selectByUserName(userName);
  }

  public static String selectByUserId(@Param("userId") long userId) {
    return currentProvider().selectByUserId(userId);
  }

  public static String listUsers(@Param("offset") int offset, @Param("limit") int limit) {
    return currentProvider().listUsers(offset, limit);
  }

  public static String countUsers() {
    return currentProvider().countUsers();
  }

  public static String updateEnabled(
      @Param("externalId") String externalId, @Param("enabled") boolean enabled) {
    return currentProvider().updateEnabled(externalId, enabled);
  }

  public static String softDeleteByExternalId(@Param("externalId") String externalId) {
    return currentProvider().softDeleteByExternalId(externalId);
  }

  public static String deleteByLegacyTimeline(
      @Param("legacyTimeline") Long legacyTimeline, @Param("limit") int limit) {
    return currentProvider().deleteByLegacyTimeline(legacyTimeline, limit);
  }

  static ScimUserMetaBaseSQLProvider getProvider(String databaseId) {
    return SQLProviderFactoryHelper.getProvider(
        databaseId, PROVIDER_MAP, ScimUserMetaSQLProviderFactory.class);
  }

  private static ScimUserMetaBaseSQLProvider currentProvider() {
    return SQLProviderFactoryHelper.currentProvider(
        PROVIDER_MAP, ScimUserMetaSQLProviderFactory.class);
  }
}
