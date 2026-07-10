/*
 * Copyright 2026 Datastrato Pvt Ltd.
 * This software is licensed under the Apache License version 2.
 */

package com.datastrato.gravitino.scim.storage.mapper.provider.base;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.datastrato.gravitino.scim.storage.mapper.provider.postgresql.ScimTokenMetaPostgreSQLProvider;
import com.datastrato.gravitino.scim.storage.po.ScimTokenMetaPO;
import org.junit.jupiter.api.Test;

class TestScimSoftDeleteSQLProvider {

  private static final String MILLISECOND_TIMESTAMP_COMPONENT =
      "EXTRACT(MICROSECOND FROM CURRENT_TIMESTAMP(3)) / 1000";

  @Test
  void testSoftDeleteByMetalakeAndNameDeletedAtPrecision() {
    String sql =
        new ScimTokenMetaBaseSQLProvider().softDeleteByMetalakeAndName("test_metalake", "token-a");

    assertUsesMillisecondTimestamp(sql);
  }

  @Test
  void testPostgreSQLSoftDeleteByMetalakeAndNameDoesNotUseTableAliasInUpdate() {
    String sql =
        new ScimTokenMetaPostgreSQLProvider()
            .softDeleteByMetalakeAndName("test_metalake", "token-a");

    assertTrue(sql.startsWith("UPDATE scim_token_meta SET deleted_at = "), sql);
    assertFalse(sql.contains(" stm SET "), sql);
    assertTrue(sql.contains("CAST(EXTRACT(EPOCH FROM CURRENT_TIMESTAMP) * 1000 AS BIGINT)"), sql);
  }

  @Test
  void testSoftDeleteByExpirationDeletedAtPrecision() {
    String sql = new ScimTokenMetaBaseSQLProvider().softDeleteByExpiration();

    assertUsesMillisecondTimestamp(sql);
  }

  @Test
  void testUpdateTokenOnRotateUpdatedAtPrecision() {
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
}
