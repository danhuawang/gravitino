/*
 * Copyright 2026 Datastrato Inc.
 */

package com.datastrato.gravitino.scim.storage.mapper;

import com.datastrato.gravitino.scim.storage.mapper.provider.base.ScimUserMetaBaseSQLProvider;
import com.datastrato.gravitino.scim.storage.mapper.provider.h2.ScimUserMetaH2Provider;
import com.datastrato.gravitino.scim.storage.mapper.provider.postgresql.ScimUserMetaPostgreSQLProvider;
import com.google.common.collect.ImmutableMap;
import java.util.List;
import java.util.Map;
import org.apache.gravitino.storage.relational.JDBCBackend.JDBCBackendType;
import org.apache.ibatis.annotations.Param;

/** Factory that selects the SCIM user metadata SQL provider for the active JDBC backend. */
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
      @Param("userMeta") com.datastrato.gravitino.scim.storage.po.ScimUserMetaPO userMeta) {
    return currentProvider().insert(userMeta);
  }

  public static String selectByExternalId(@Param("externalId") String externalId) {
    return currentProvider().selectByExternalId(externalId);
  }

  public static String selectByUserName(@Param("userName") String userName) {
    return currentProvider().selectByUserName(userName);
  }

  /**
   * Delegates case-insensitive user-name lookup SQL to the active backend provider.
   *
   * @param userName user name to match ignoring case
   * @return SELECT SQL
   */
  public static String selectByUserNameIgnoreCase(@Param("userName") String userName) {
    return currentProvider().selectByUserNameIgnoreCase(userName);
  }

  /**
   * Delegates batch external-id lookup SQL to the active backend provider.
   *
   * @param externalIds SCIM resource ids
   * @return SELECT SQL script
   */
  public static String selectByExternalIds(@Param("externalIds") List<String> externalIds) {
    return currentProvider().selectByExternalIds(externalIds);
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

  public static String updateEnabledByUserId(
      @Param("userId") long userId, @Param("enabled") boolean enabled) {
    return currentProvider().updateEnabledByUserId(userId, enabled);
  }

  public static String updateExternalId(
      @Param("userId") long userId, @Param("externalId") String externalId) {
    return currentProvider().updateExternalId(userId, externalId);
  }

  public static String softDeleteByUserId(@Param("userId") long userId) {
    return currentProvider().softDeleteByUserId(userId);
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
