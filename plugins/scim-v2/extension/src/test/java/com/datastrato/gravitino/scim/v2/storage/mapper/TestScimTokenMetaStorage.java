/*
 * Copyright 2026 Datastrato Pvt Ltd.
 */

package com.datastrato.gravitino.scim.v2.storage.mapper;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.datastrato.gravitino.scim.v2.storage.po.ScimTokenMetaPO;
import java.io.IOException;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;

@Tag("gravitino-docker-test")
class TestScimTokenMetaStorage extends AbstractScimMetaStorageTest {
  private ScimTokenMetaMapper scimTokenMetaMapper;

  @Override
  protected void initializeMappers() {
    scimTokenMetaMapper = sharedSession.getMapper(ScimTokenMetaMapper.class);
  }

  @ParameterizedTest
  @MethodSource("storageProvider")
  void testInsertAndSelectByHash(String type) throws IOException {
    init(type);
    ScimTokenMetaPO tokenMeta = createTokenMeta(1L, "scim-token", "hash-a", 0L);
    scimTokenMetaMapper.insert(tokenMeta);

    assertEquals(tokenMeta, scimTokenMetaMapper.selectByTokenHash("hash-a"));
    assertNull(scimTokenMetaMapper.selectByTokenHash("unknown"));
  }

  @ParameterizedTest
  @MethodSource("storageProvider")
  void testSelectByName(String type) throws IOException {
    init(type);
    ScimTokenMetaPO tokenMeta = createTokenMeta(1L, "scim-token", "hash-a", 0L);
    scimTokenMetaMapper.insert(tokenMeta);

    assertEquals(tokenMeta, scimTokenMetaMapper.selectByName("scim-token"));
    assertNull(scimTokenMetaMapper.selectByName("missing"));
  }

  @ParameterizedTest
  @MethodSource("storageProvider")
  void testSoftDeleteByName(String type) throws IOException {
    init(type);
    scimTokenMetaMapper.insert(createTokenMeta(1L, "scim-token", "hash-a", 0L));

    assertEquals(1, scimTokenMetaMapper.softDeleteByName("scim-token"));
    assertNull(scimTokenMetaMapper.selectByTokenHash("hash-a"));
    assertEquals(0, scimTokenMetaMapper.softDeleteByName("scim-token"));
  }

  @ParameterizedTest
  @MethodSource("storageProvider")
  void testUpdateTokenOnRotate(String type) throws IOException {
    init(type);
    ScimTokenMetaPO oldTokenMeta = createTokenMeta(1L, "scim-token", "hash-a", 1000L);
    scimTokenMetaMapper.insert(oldTokenMeta);

    ScimTokenMetaPO newTokenMeta =
        ScimTokenMetaPO.builder()
            .withTokenId(1L)
            .withTokenName("scim-token")
            .withTokenHash("hash-b")
            .withExpiresAt(2000L)
            .withAuditInfo("{\"rotated\":true}")
            .withDeletedAt(0L)
            .withUpdatedAt(0L)
            .build();

    assertEquals(1, scimTokenMetaMapper.updateTokenOnRotate(newTokenMeta, oldTokenMeta));

    ScimTokenMetaPO updated = scimTokenMetaMapper.selectByTokenHash("hash-b");
    assertEquals("hash-b", updated.getTokenHash());
    assertEquals(2000L, updated.getExpiresAt());
    assertEquals("{\"rotated\":true}", updated.getAuditInfo());
    assertTrue(updated.getUpdatedAt() > 0L);
    assertEquals(0L, updated.getLastUsedAt());
    assertNull(scimTokenMetaMapper.selectByTokenHash("hash-a"));
  }

  @ParameterizedTest
  @MethodSource("storageProvider")
  void testListAll(String type) throws IOException {
    init(type);
    scimTokenMetaMapper.insert(createTokenMeta(1L, "token-b", "hash-b", 0L));
    scimTokenMetaMapper.insert(createTokenMeta(2L, "token-a", "hash-a", 0L));

    var tokens = scimTokenMetaMapper.listAll();
    assertEquals(2, tokens.size());
    assertEquals("token-a", tokens.get(0).getTokenName());
    assertEquals("token-b", tokens.get(1).getTokenName());
  }

  @ParameterizedTest
  @MethodSource("storageProvider")
  void testMaxLastUsedAt(String type) throws IOException {
    init(type);
    scimTokenMetaMapper.insert(tokenMeta(1L, "old-token", "hash-old", 100L, 0L));
    scimTokenMetaMapper.insert(tokenMeta(2L, "new-token", "hash-new", 500L, 0L));
    scimTokenMetaMapper.insert(tokenMeta(3L, "deleted-token", "hash-del", 999L, 1L));

    assertEquals(500L, scimTokenMetaMapper.selectMaxLastUsedAt());
  }

  @ParameterizedTest
  @MethodSource("storageProvider")
  void testProvisioningStats(String type) throws IOException {
    init(type);
    scimTokenMetaMapper.insert(createTokenMeta(1L, "token-a", "hash-a", 0L));
    scimTokenMetaMapper.insert(createTokenMeta(2L, "token-b", "hash-b", 0L));
    scimTokenMetaMapper.updateScimTokenLastUsedAt(1L);

    var stats = scimTokenMetaMapper.listProvisioningStats();
    assertEquals(2L, stats.getTokenCount());
    assertTrue(stats.getLastUsedAt() > 0L);
  }

  @ParameterizedTest
  @MethodSource("storageProvider")
  void testTouchUsed(String type) throws IOException {
    init(type);
    scimTokenMetaMapper.insert(createTokenMeta(1L, "scim-token", "hash-a", 0L));

    assertEquals(1, scimTokenMetaMapper.updateScimTokenLastUsedAt(1L));
    ScimTokenMetaPO updated = scimTokenMetaMapper.selectByTokenHash("hash-a");
    assertTrue(updated.getLastUsedAt() > 0L);
    assertEquals(0L, updated.getUpdatedAt());
    assertEquals(0, scimTokenMetaMapper.updateScimTokenLastUsedAt(999L));
  }

  @ParameterizedTest
  @MethodSource("storageProvider")
  void testTouchSkipDeleted(String type) throws IOException {
    init(type);
    scimTokenMetaMapper.insert(createTokenMeta(1L, "scim-token", "hash-a", 0L));
    scimTokenMetaMapper.updateScimTokenLastUsedAt(1L);
    scimTokenMetaMapper.softDeleteByName("scim-token");
    assertEquals(0, scimTokenMetaMapper.updateScimTokenLastUsedAt(1L));
  }

  @ParameterizedTest
  @MethodSource("storageProvider")
  void testDeleteLegacy(String type) throws IOException {
    init(type);
    scimTokenMetaMapper.insert(
        ScimTokenMetaPO.builder()
            .withTokenId(1L)
            .withTokenName("legacy-token")
            .withTokenHash("hash-a")
            .withExpiresAt(0L)
            .withAuditInfo("{}")
            .withDeletedAt(10L)
            .withUpdatedAt(0L)
            .build());
    scimTokenMetaMapper.insert(
        ScimTokenMetaPO.builder()
            .withTokenId(2L)
            .withTokenName("new-token")
            .withTokenHash("hash-b")
            .withExpiresAt(0L)
            .withAuditInfo("{}")
            .withDeletedAt(30L)
            .withUpdatedAt(0L)
            .build());
    scimTokenMetaMapper.insert(createTokenMeta(3L, "active-token", "hash-c", 0L));

    assertEquals(1, scimTokenMetaMapper.deleteByLegacyTimeline(20L, 10));
    assertEquals(0, scimTokenMetaMapper.deleteByLegacyTimeline(20L, 10));
    assertEquals(1, scimTokenMetaMapper.deleteByLegacyTimeline(40L, 10));
    assertEquals(0, scimTokenMetaMapper.deleteByLegacyTimeline(Long.MAX_VALUE, 10));
    assertEquals("active-token", scimTokenMetaMapper.selectByTokenHash("hash-c").getTokenName());
    assertNull(scimTokenMetaMapper.selectByTokenHash("hash-a"));
    assertNull(scimTokenMetaMapper.selectByTokenHash("hash-b"));
  }

  private ScimTokenMetaPO createTokenMeta(
      long tokenId, String tokenName, String tokenHash, long expiresAt) {
    return ScimTokenMetaPO.builder()
        .withTokenId(tokenId)
        .withTokenName(tokenName)
        .withTokenHash(tokenHash)
        .withExpiresAt(expiresAt)
        .withAuditInfo("{}")
        .withDeletedAt(0L)
        .withUpdatedAt(0L)
        .build();
  }

  private static ScimTokenMetaPO tokenMeta(
      long tokenId, String tokenName, String tokenHash, long lastUsedAt, long deletedAt) {
    return ScimTokenMetaPO.builder()
        .withTokenId(tokenId)
        .withTokenName(tokenName)
        .withTokenHash(tokenHash)
        .withExpiresAt(0L)
        .withAuditInfo("{}")
        .withDeletedAt(deletedAt)
        .withUpdatedAt(0L)
        .withLastUsedAt(lastUsedAt)
        .build();
  }
}
