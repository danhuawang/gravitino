/*
 * Copyright 2026 Datastrato Pvt Ltd.
 * This software is licensed under the Apache License version 2.
 */

package com.datastrato.gravitino.scim.storage.mapper;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.datastrato.gravitino.scim.storage.mapper.provider.ScimUserGroupRelMapperPackageProvider;
import com.datastrato.gravitino.scim.storage.mapper.provider.base.ScimUserGroupRelBaseSQLProvider;
import com.datastrato.gravitino.scim.storage.mapper.provider.h2.ScimUserGroupRelH2Provider;
import com.datastrato.gravitino.scim.storage.mapper.provider.postgresql.ScimUserGroupRelPostgreSQLProvider;
import java.util.List;
import java.util.ServiceLoader;
import org.apache.gravitino.storage.relational.mapper.provider.MapperPackageProvider;
import org.junit.jupiter.api.Test;

class TestScimUserGroupRelMapperPackageProvider {

  private static final String MILLISECOND_TIMESTAMP_COMPONENT =
      "EXTRACT(MICROSECOND FROM CURRENT_TIMESTAMP(3)) / 1000";

  private static final String POSTGRESQL_MILLISECOND_TIMESTAMP_COMPONENT =
      "FLOOR(EXTRACT(EPOCH FROM CURRENT_TIMESTAMP(3)) * 1000)";

  @Test
  void testMapperClasses() {
    MapperPackageProvider provider = new ScimUserGroupRelMapperPackageProvider();
    List<Class<?>> mapperClasses = provider.getMapperClasses();

    assertEquals(1, mapperClasses.size());
    assertTrue(mapperClasses.contains(ScimUserGroupRelMapper.class));
  }

  @Test
  void testServiceLoader() {
    List<MapperPackageProvider> providers =
        ServiceLoader.load(MapperPackageProvider.class).stream()
            .map(ServiceLoader.Provider::get)
            .filter(provider -> provider instanceof ScimUserGroupRelMapperPackageProvider)
            .toList();

    assertEquals(1, providers.size());
    assertTrue(providers.get(0) instanceof ScimUserGroupRelMapperPackageProvider);
  }

  @Test
  void testSoftDeleteUserExtIdMs() {
    String sql =
        new ScimUserGroupRelBaseSQLProvider()
            .softDeleteMembersByUserExternalId("test_metalake", "user-ext-1");

    assertUsesMillisecondTimestamp(sql);
  }

  @Test
  void testPgSoftDeleteUserExtIdMs() {
    String sql =
        new ScimUserGroupRelPostgreSQLProvider()
            .softDeleteMembersByUserExternalId("test_metalake", "user-ext-1");

    assertUsesPostgreSQLMillisecondTimestamp(sql);
    assertTrue(sql.contains(" r SET deleted_at = "), sql);
    assertFalse(sql.contains("SET r.deleted_at"), sql);
  }

  @Test
  void testPgSoftDeleteUnavailableMs() {
    String sql = new ScimUserGroupRelPostgreSQLProvider().softDeleteMembersByUnavailableMetalake();

    assertUsesPostgreSQLMillisecondTimestamp(sql);
    assertTrue(sql.contains("NOT EXISTS"));
    assertTrue(sql.contains("metalake_meta"));
    assertTrue(sql.contains(" r SET deleted_at = "), sql);
  }

  @Test
  void testH2SoftDeleteUserExtIdMs() {
    String sql =
        new ScimUserGroupRelH2Provider()
            .softDeleteMembersByUserExternalId("test_metalake", "user-ext-1");

    assertUsesMillisecondTimestamp(sql);
  }

  @Test
  void testSoftDeleteGroupUsersMs() {
    String sql =
        new ScimUserGroupRelBaseSQLProvider()
            .softDeleteMembersByGroupAndUserExternalIds(
                "test_metalake", "group-ext-1", List.of("user-ext-1"));

    assertUsesMillisecondTimestamp(sql);
  }

  private static void assertUsesMillisecondTimestamp(String sql) {
    assertTrue(sql.contains("(UNIX_TIMESTAMP() * 1000.0)"), sql);
    assertTrue(sql.contains(MILLISECOND_TIMESTAMP_COMPONENT), sql);
  }

  private static void assertUsesPostgreSQLMillisecondTimestamp(String sql) {
    assertTrue(sql.contains(POSTGRESQL_MILLISECOND_TIMESTAMP_COMPONENT), sql);
  }
}
