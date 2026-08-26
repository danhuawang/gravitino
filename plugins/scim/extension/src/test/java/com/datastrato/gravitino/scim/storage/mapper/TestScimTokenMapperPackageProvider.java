/*
 * Copyright 2026 Datastrato Pvt Ltd.
 */

package com.datastrato.gravitino.scim.storage.mapper;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.datastrato.gravitino.scim.storage.mapper.provider.ScimTokenMapperPackageProvider;
import com.datastrato.gravitino.scim.storage.mapper.provider.base.ScimTokenMetaBaseSQLProvider;
import com.datastrato.gravitino.scim.storage.mapper.provider.h2.ScimTokenMetaH2Provider;
import com.datastrato.gravitino.scim.storage.mapper.provider.postgresql.ScimTokenMetaPostgreSQLProvider;
import com.datastrato.gravitino.scim.storage.po.ScimTokenMetaPO;
import java.util.List;
import java.util.ServiceLoader;
import org.apache.gravitino.storage.relational.mapper.provider.MapperPackageProvider;
import org.junit.jupiter.api.Test;

public class TestScimTokenMapperPackageProvider {

  private static final String MILLISECOND_TIMESTAMP_COMPONENT =
      "EXTRACT(MICROSECOND FROM CURRENT_TIMESTAMP(3)) / 1000";

  @Test
  public void testMapperClasses() {
    MapperPackageProvider provider = new ScimTokenMapperPackageProvider();
    List<Class<?>> mapperClasses = provider.getMapperClasses();

    assertEquals(1, mapperClasses.size());
    assertTrue(mapperClasses.contains(ScimTokenMetaMapper.class));
  }

  @Test
  public void testServiceLoader() {
    List<MapperPackageProvider> providers =
        ServiceLoader.load(MapperPackageProvider.class).stream()
            .map(ServiceLoader.Provider::get)
            .filter(provider -> provider instanceof ScimTokenMapperPackageProvider)
            .toList();

    assertEquals(1, providers.size());
    assertTrue(providers.get(0) instanceof ScimTokenMapperPackageProvider);
  }

  @Test
  void testSoftDeleteByMetalakeMs() {
    String sql =
        new ScimTokenMetaBaseSQLProvider().softDeleteByMetalakeAndName("test_metalake", "token-a");

    assertUsesMillisecondTimestamp(sql);
  }

  @Test
  void testPgSoftDeleteByMetalakeNoAlias() {
    String sql =
        new ScimTokenMetaPostgreSQLProvider()
            .softDeleteByMetalakeAndName("test_metalake", "token-a");

    assertTrue(sql.startsWith("UPDATE scim_token_meta SET deleted_at = "), sql);
    assertFalse(sql.contains(" stm SET "), sql);
    assertUsesPostgreSQLMillisecondTimestamp(sql);
  }

  @Test
  void testSoftDeleteExpiredMs() {
    String sql = new ScimTokenMetaBaseSQLProvider().softDeleteByExpiration();

    assertUsesMillisecondTimestamp(sql);
  }

  @Test
  void testSoftDeleteUnavailableMetalakeSql() {
    String sql = new ScimTokenMetaBaseSQLProvider().softDeleteByUnavailableMetalake();

    assertUsesMillisecondTimestamp(sql);
    assertTrue(sql.contains("NOT EXISTS"));
    assertTrue(sql.contains("metalake_meta"));
    assertTrue(sql.contains("m.deleted_at = 0"));
  }

  @Test
  void testPgSoftDeleteUnavailableMetalake() {
    String sql = new ScimTokenMetaPostgreSQLProvider().softDeleteByUnavailableMetalake();

    assertTrue(sql.contains("NOT EXISTS"));
    assertTrue(sql.contains("metalake_meta"));
    assertUsesPostgreSQLMillisecondTimestamp(sql);
    assertTrue(sql.contains(" stm SET deleted_at = "), sql);
  }

  @Test
  void testH2SoftDeleteExpiredMs() {
    String sql = new ScimTokenMetaH2Provider().softDeleteByExpiration();

    assertUsesMillisecondTimestamp(sql);
  }

  @Test
  void testPgSoftDeleteExpiredMs() {
    String sql = new ScimTokenMetaPostgreSQLProvider().softDeleteByExpiration();

    assertUsesPostgreSQLMillisecondTimestamp(sql);
  }

  @Test
  void testPgUpdateOnRotateMs() {
    ScimTokenMetaPO oldTokenMeta =
        ScimTokenMetaPO.builder()
            .withTokenId(1L)
            .withMetalakeId(10L)
            .withTokenName("token-a")
            .withTokenHash("hash-a")
            .withExpiresAt(1000L)
            .withAuditInfo("{}")
            .withDeletedAt(0L)
            .withUpdatedAt(0L)
            .build();
    ScimTokenMetaPO newTokenMeta =
        ScimTokenMetaPO.builder()
            .withTokenId(1L)
            .withMetalakeId(10L)
            .withTokenName("token-a")
            .withTokenHash("hash-b")
            .withExpiresAt(2000L)
            .withAuditInfo("{\"rotated\":true}")
            .withDeletedAt(0L)
            .withUpdatedAt(0L)
            .build();
    String sql =
        new ScimTokenMetaPostgreSQLProvider().updateTokenOnRotate(newTokenMeta, oldTokenMeta);

    assertUsesPostgreSQLMillisecondTimestamp(sql);
  }

  @Test
  void testProvisioningStatsSql() {
    String sql =
        new ScimTokenMetaBaseSQLProvider().listProvisioningStatsByMetalakeIds(List.of(1L, 2L));
    assertTrue(sql.contains("COUNT(stm.token_id) as tokenCount"), sql);
    assertTrue(sql.contains("COALESCE(MAX(stm.last_used_at), 0) as lastUsedAt"), sql);
    assertTrue(sql.contains("LEFT JOIN"), sql);
    assertTrue(sql.contains("mm.deleted_at = 0"), sql);
    assertTrue(sql.contains("stm.deleted_at = 0"), sql);
    assertTrue(sql.contains("ORDER BY mm.metalake_name ASC"), sql);
  }

  @Test
  void testListSql() {
    String sql = new ScimTokenMetaBaseSQLProvider().listByMetalake("my_metalake");
    assertTrue(sql.contains("ORDER BY stm.token_name ASC"), sql);
    assertTrue(sql.contains("deleted_at = 0"), sql);
  }

  @Test
  void testTouchSql() {
    String sql = new ScimTokenMetaBaseSQLProvider().updateScimTokenLastUsedAt(1L);
    assertTrue(sql.startsWith("UPDATE scim_token_meta SET last_used_at = "), sql);
    assertUsesMillisecondTimestamp(sql);
  }

  @Test
  void testPgTouchSql() {
    String sql = new ScimTokenMetaPostgreSQLProvider().updateScimTokenLastUsedAt(1L);

    assertTrue(sql.startsWith("UPDATE scim_token_meta SET last_used_at = "), sql);
    assertUsesPostgreSQLMillisecondTimestamp(sql);
  }

  @Test
  void testUpdateOnRotateMs() {
    ScimTokenMetaPO oldTokenMeta =
        ScimTokenMetaPO.builder()
            .withTokenId(1L)
            .withMetalakeId(10L)
            .withTokenName("token-a")
            .withTokenHash("hash-a")
            .withExpiresAt(1000L)
            .withAuditInfo("{}")
            .withDeletedAt(0L)
            .withUpdatedAt(0L)
            .build();
    ScimTokenMetaPO newTokenMeta =
        ScimTokenMetaPO.builder()
            .withTokenId(1L)
            .withMetalakeId(10L)
            .withTokenName("token-a")
            .withTokenHash("hash-b")
            .withExpiresAt(2000L)
            .withAuditInfo("{\"rotated\":true}")
            .withDeletedAt(0L)
            .withUpdatedAt(0L)
            .build();
    String sql = new ScimTokenMetaBaseSQLProvider().updateTokenOnRotate(newTokenMeta, oldTokenMeta);

    assertUsesMillisecondTimestamp(sql);
  }

  private static void assertUsesMillisecondTimestamp(String sql) {
    assertTrue(sql.contains("(UNIX_TIMESTAMP() * 1000.0)"), sql);
    assertTrue(sql.contains(MILLISECOND_TIMESTAMP_COMPONENT), sql);
  }

  private static void assertUsesPostgreSQLMillisecondTimestamp(String sql) {
    assertTrue(sql.contains("FLOOR(EXTRACT(EPOCH FROM CURRENT_TIMESTAMP(3)) * 1000)"), sql);
  }
}
