/*
 * Copyright 2026 Datastrato Pvt Ltd.
 * This software is licensed under the Apache License version 2.
 */

package com.datastrato.gravitino.scim.storage.mapper;

import com.datastrato.gravitino.scim.storage.mapper.provider.base.ScimTokenMetaBaseSQLProvider;
import com.datastrato.gravitino.scim.storage.mapper.provider.h2.ScimTokenMetaH2Provider;
import com.datastrato.gravitino.scim.storage.mapper.provider.postgresql.ScimTokenMetaPostgreSQLProvider;
import com.datastrato.gravitino.scim.storage.po.ScimTokenMetaPO;
import com.google.common.collect.ImmutableMap;
import java.util.List;
import java.util.Map;
import org.apache.gravitino.storage.relational.JDBCBackend.JDBCBackendType;
import org.apache.ibatis.annotations.Param;

/** Factory that selects the SCIM token metadata SQL provider for the active JDBC backend. */
public class ScimTokenMetaSQLProviderFactory {
  private static final ScimTokenMetaBaseSQLProvider MYSQL_PROVIDER =
      new ScimTokenMetaBaseSQLProvider();
  private static final ScimTokenMetaBaseSQLProvider H2_PROVIDER = new ScimTokenMetaH2Provider();
  private static final ScimTokenMetaBaseSQLProvider POSTGRESQL_PROVIDER =
      new ScimTokenMetaPostgreSQLProvider();
  private static final Map<JDBCBackendType, ScimTokenMetaBaseSQLProvider> PROVIDER_MAP =
      ImmutableMap.of(
          JDBCBackendType.MYSQL,
          MYSQL_PROVIDER,
          JDBCBackendType.H2,
          H2_PROVIDER,
          JDBCBackendType.POSTGRESQL,
          POSTGRESQL_PROVIDER);

  private ScimTokenMetaSQLProviderFactory() {}

  public static String insert(@Param("tokenMeta") ScimTokenMetaPO tokenMeta) {
    return currentProvider().insert(tokenMeta);
  }

  public static String selectByTokenHash(@Param("tokenHash") String tokenHash) {
    return currentProvider().selectByTokenHash(tokenHash);
  }

  public static String selectByMetalakeAndName(
      @Param("metalakeName") String metalakeName, @Param("tokenName") String tokenName) {
    return currentProvider().selectByMetalakeAndName(metalakeName, tokenName);
  }

  public static String listProvisioningStatsByMetalakeIds(
      @Param("metalakeIds") List<Long> metalakeIds) {
    return currentProvider().listProvisioningStatsByMetalakeIds(metalakeIds);
  }

  public static String listByMetalake(@Param("metalakeName") String metalakeName) {
    return currentProvider().listByMetalake(metalakeName);
  }

  public static String softDeleteByMetalakeAndName(
      @Param("metalakeName") String metalakeName, @Param("tokenName") String tokenName) {
    return currentProvider().softDeleteByMetalakeAndName(metalakeName, tokenName);
  }

  public static String softDeleteByExpiration() {
    return currentProvider().softDeleteByExpiration();
  }

  public static String softDeleteByUnavailableMetalake() {
    return currentProvider().softDeleteByUnavailableMetalake();
  }

  public static String updateTokenOnRotate(
      @Param("newTokenMeta") ScimTokenMetaPO newTokenMeta,
      @Param("oldTokenMeta") ScimTokenMetaPO oldTokenMeta) {
    return currentProvider().updateTokenOnRotate(newTokenMeta, oldTokenMeta);
  }

  public static String updateScimTokenLastUsedAt(@Param("tokenId") Long tokenId) {
    return currentProvider().updateScimTokenLastUsedAt(tokenId);
  }

  public static String deleteByLegacyTimeline(
      @Param("legacyTimeline") Long legacyTimeline, @Param("limit") int limit) {
    return currentProvider().deleteByLegacyTimeline(legacyTimeline, limit);
  }

  static ScimTokenMetaBaseSQLProvider getProvider(String databaseId) {
    return SQLProviderFactoryHelper.getProvider(
        databaseId, PROVIDER_MAP, ScimTokenMetaSQLProviderFactory.class);
  }

  private static ScimTokenMetaBaseSQLProvider currentProvider() {
    return SQLProviderFactoryHelper.currentProvider(
        PROVIDER_MAP, ScimTokenMetaSQLProviderFactory.class);
  }
}
