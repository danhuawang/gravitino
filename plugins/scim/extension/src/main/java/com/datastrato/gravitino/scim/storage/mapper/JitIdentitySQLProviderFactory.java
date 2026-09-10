/*
 * Copyright 2026 Datastrato Inc.
 */

package com.datastrato.gravitino.scim.storage.mapper;

import com.datastrato.gravitino.scim.storage.mapper.provider.base.JitIdentityBaseSQLProvider;
import com.datastrato.gravitino.scim.storage.mapper.provider.postgresql.JitIdentityPostgreSQLProvider;
import com.google.common.collect.ImmutableMap;
import java.util.Map;
import org.apache.gravitino.storage.relational.JDBCBackend.JDBCBackendType;
import org.apache.ibatis.annotations.Param;

/** Selects backend-specific SQL for global JWT-discovered identity metadata. */
public class JitIdentitySQLProviderFactory {
  private static final JitIdentityBaseSQLProvider MYSQL_PROVIDER = new JitIdentityBaseSQLProvider();
  private static final JitIdentityBaseSQLProvider H2_PROVIDER = new JitIdentityBaseSQLProvider();
  private static final JitIdentityBaseSQLProvider POSTGRESQL_PROVIDER =
      new JitIdentityPostgreSQLProvider();
  private static final Map<JDBCBackendType, JitIdentityBaseSQLProvider> PROVIDER_MAP =
      ImmutableMap.of(
          JDBCBackendType.MYSQL, MYSQL_PROVIDER,
          JDBCBackendType.H2, H2_PROVIDER,
          JDBCBackendType.POSTGRESQL, POSTGRESQL_PROVIDER);

  private JitIdentitySQLProviderFactory() {}

  /** Returns SQL that physically deletes legacy JIT memberships. */
  public static String deleteMembershipsByLegacyTimeline(
      @Param("legacyTimeline") Long legacyTimeline, @Param("limit") int limit) {
    return currentProvider().deleteMembershipsByLegacyTimeline(legacyTimeline, limit);
  }

  /** Returns SQL that physically deletes legacy JIT users. */
  public static String deleteUsersByLegacyTimeline(
      @Param("legacyTimeline") Long legacyTimeline, @Param("limit") int limit) {
    return currentProvider().deleteUsersByLegacyTimeline(legacyTimeline, limit);
  }

  /** Returns SQL that physically deletes legacy JIT groups. */
  public static String deleteGroupsByLegacyTimeline(
      @Param("legacyTimeline") Long legacyTimeline, @Param("limit") int limit) {
    return currentProvider().deleteGroupsByLegacyTimeline(legacyTimeline, limit);
  }

  private static JitIdentityBaseSQLProvider currentProvider() {
    return SQLProviderFactoryHelper.currentProvider(
        PROVIDER_MAP, JitIdentitySQLProviderFactory.class);
  }
}
