/*
 * Copyright 2026 Datastrato Pvt Ltd.
 * This software is licensed under the Apache License version 2.
 */

package com.datastrato.gravitino.scim.storage.mapper;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.datastrato.gravitino.scim.storage.po.ScimTokenMetaPO;
import java.io.IOException;
import java.util.List;
import org.apache.gravitino.storage.relational.mapper.MetalakeMetaMapper;
import org.apache.gravitino.storage.relational.po.MetalakePO;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;

@Tag("gravitino-docker-test")
class TestScimTokenMetaStorage extends AbstractScimMetaStorageTest {
  private static final String METALAKE_NAME = "test_metalake";
  private static final long METALAKE_ID = 10L;

  private ScimTokenMetaMapper scimTokenMetaMapper;

  @Override
  protected void initializeMappers() {
    scimTokenMetaMapper = sharedSession.getMapper(ScimTokenMetaMapper.class);
  }

  @ParameterizedTest
  @MethodSource("storageProvider")
  void testInsertAndSelectByHash(String type) throws IOException {
    init(type);
    insertMetalake();
    ScimTokenMetaPO tokenMeta = createTokenMeta(1L, METALAKE_ID, "scim-token", "hash-a", 0L);
    scimTokenMetaMapper.insert(tokenMeta);

    assertEquals(tokenMeta, scimTokenMetaMapper.selectByTokenHash("hash-a"));
    assertNull(scimTokenMetaMapper.selectByTokenHash("unknown"));
  }

  @ParameterizedTest
  @MethodSource("storageProvider")
  void testSelectByName(String type) throws IOException {
    init(type);
    insertMetalake();
    ScimTokenMetaPO tokenMeta = createTokenMeta(1L, METALAKE_ID, "scim-token", "hash-a", 0L);
    scimTokenMetaMapper.insert(tokenMeta);

    assertEquals(
        tokenMeta, scimTokenMetaMapper.selectByMetalakeAndName(METALAKE_NAME, "scim-token"));
    assertNull(scimTokenMetaMapper.selectByMetalakeAndName(METALAKE_NAME, "missing"));
    assertNull(scimTokenMetaMapper.selectByMetalakeAndName("missing_metalake", "scim-token"));
  }

  @ParameterizedTest
  @MethodSource("storageProvider")
  void testSoftDeleteByMetalake(String type) throws IOException {
    init(type);
    insertMetalake();
    scimTokenMetaMapper.insert(createTokenMeta(1L, METALAKE_ID, "scim-token", "hash-a", 0L));

    assertEquals(1, scimTokenMetaMapper.softDeleteByMetalakeAndName(METALAKE_NAME, "scim-token"));
    assertNull(scimTokenMetaMapper.selectByTokenHash("hash-a"));
    assertEquals(0, scimTokenMetaMapper.softDeleteByMetalakeAndName(METALAKE_NAME, "scim-token"));
  }

  @ParameterizedTest
  @MethodSource("storageProvider")
  void testSoftDeleteUnavailableMetalake(String type) throws IOException {
    init(type);
    long deletedMetalakeId = 20L;
    long missingMetalakeId = 99L;

    insertMetalake();
    scimTokenMetaMapper.insert(createTokenMeta(1L, METALAKE_ID, "active-token", "hash-active", 0L));

    insertMetalake(deletedMetalakeId, "deleted_metalake");
    scimTokenMetaMapper.insert(
        createTokenMeta(2L, deletedMetalakeId, "deleted-metalake-token", "hash-deleted", 0L));
    softDeleteMetalake(deletedMetalakeId);

    scimTokenMetaMapper.insert(
        createTokenMeta(3L, missingMetalakeId, "missing-metalake-token", "hash-missing", 0L));

    assertEquals(2, scimTokenMetaMapper.softDeleteByUnavailableMetalake());
    assertEquals(
        "active-token", scimTokenMetaMapper.selectByTokenHash("hash-active").getTokenName());
    assertNull(scimTokenMetaMapper.selectByTokenHash("hash-deleted"));
    assertNull(scimTokenMetaMapper.selectByTokenHash("hash-missing"));
  }

  @ParameterizedTest
  @MethodSource("storageProvider")
  void testUpdateTokenOnRotate(String type) throws IOException {
    init(type);
    insertMetalake();
    ScimTokenMetaPO oldTokenMeta = createTokenMeta(1L, METALAKE_ID, "scim-token", "hash-a", 1000L);
    scimTokenMetaMapper.insert(oldTokenMeta);

    ScimTokenMetaPO newTokenMeta =
        ScimTokenMetaPO.builder()
            .withTokenId(1L)
            .withMetalakeId(METALAKE_ID)
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
  void testListByMl(String type) throws IOException {
    init(type);
    insertMetalake();
    insertMetalake(20L, "other_metalake");
    scimTokenMetaMapper.insert(createTokenMeta(1L, METALAKE_ID, "token-b", "hash-b", 0L));
    scimTokenMetaMapper.insert(createTokenMeta(2L, METALAKE_ID, "token-a", "hash-a", 0L));
    scimTokenMetaMapper.insert(createTokenMeta(3L, 20L, "token-c", "hash-c", 0L));

    var tokens = scimTokenMetaMapper.listByMetalake(METALAKE_NAME);
    assertEquals(2, tokens.size());
    assertEquals("token-a", tokens.get(0).getTokenName());
    assertEquals("token-b", tokens.get(1).getTokenName());
    assertEquals(1, scimTokenMetaMapper.listByMetalake("other_metalake").size());
  }

  @ParameterizedTest
  @MethodSource("storageProvider")
  void testProvisioningStatsByMetalakeIds(String type) throws IOException {
    init(type);
    insertMetalake();
    insertMetalake(20L, "other_metalake");
    insertMetalake(30L, "empty_metalake");
    scimTokenMetaMapper.insert(createTokenMeta(1L, METALAKE_ID, "token-a", "hash-a", 0L));
    scimTokenMetaMapper.insert(createTokenMeta(2L, METALAKE_ID, "token-b", "hash-b", 0L));
    scimTokenMetaMapper.insert(createTokenMeta(3L, 20L, "token-c", "hash-c", 0L));
    scimTokenMetaMapper.updateScimTokenLastUsedAt(1L);

    var stats =
        scimTokenMetaMapper.listProvisioningStatsByMetalakeIds(List.of(METALAKE_ID, 20L, 30L));
    assertEquals(3, stats.size());
    assertEquals(METALAKE_NAME, stats.get(0).getMetalakeName());
    assertEquals(2L, stats.get(0).getTokenCount());
    assertTrue(stats.get(0).getLastUsedAt() > 0L);
    assertEquals("other_metalake", stats.get(1).getMetalakeName());
    assertEquals(1L, stats.get(1).getTokenCount());
    assertEquals("empty_metalake", stats.get(2).getMetalakeName());
    assertEquals(0L, stats.get(2).getTokenCount());
    assertEquals(0L, stats.get(2).getLastUsedAt());
  }

  @ParameterizedTest
  @MethodSource("storageProvider")
  void testTouchUsed(String type) throws IOException {
    init(type);
    insertMetalake();
    scimTokenMetaMapper.insert(createTokenMeta(1L, METALAKE_ID, "scim-token", "hash-a", 0L));

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
    insertMetalake();
    scimTokenMetaMapper.insert(createTokenMeta(1L, METALAKE_ID, "scim-token", "hash-a", 0L));
    scimTokenMetaMapper.updateScimTokenLastUsedAt(1L);
    scimTokenMetaMapper.softDeleteByMetalakeAndName(METALAKE_NAME, "scim-token");
    assertEquals(0, scimTokenMetaMapper.updateScimTokenLastUsedAt(1L));
  }

  @ParameterizedTest
  @MethodSource("storageProvider")
  void testDeleteLegacy(String type) throws IOException {
    init(type);
    insertMetalake();
    scimTokenMetaMapper.insert(
        ScimTokenMetaPO.builder()
            .withTokenId(1L)
            .withMetalakeId(METALAKE_ID)
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
            .withMetalakeId(METALAKE_ID)
            .withTokenName("new-token")
            .withTokenHash("hash-b")
            .withExpiresAt(0L)
            .withAuditInfo("{}")
            .withDeletedAt(30L)
            .withUpdatedAt(0L)
            .build());
    scimTokenMetaMapper.insert(createTokenMeta(3L, METALAKE_ID, "active-token", "hash-c", 0L));

    assertEquals(1, scimTokenMetaMapper.deleteByLegacyTimeline(20L, 10));
    assertEquals(0, scimTokenMetaMapper.deleteByLegacyTimeline(20L, 10));
    assertEquals(1, scimTokenMetaMapper.deleteByLegacyTimeline(40L, 10));
    assertEquals(0, scimTokenMetaMapper.deleteByLegacyTimeline(Long.MAX_VALUE, 10));
    assertEquals("active-token", scimTokenMetaMapper.selectByTokenHash("hash-c").getTokenName());
    assertNull(scimTokenMetaMapper.selectByTokenHash("hash-a"));
    assertNull(scimTokenMetaMapper.selectByTokenHash("hash-b"));
  }

  private void insertMetalake() {
    insertMetalake(METALAKE_ID, METALAKE_NAME);
  }

  private void insertMetalake(long metalakeId, String metalakeName) {
    MetalakeMetaMapper metalakeMetaMapper = sharedSession.getMapper(MetalakeMetaMapper.class);
    metalakeMetaMapper.insertMetalakeMeta(
        MetalakePO.builder()
            .withMetalakeId(metalakeId)
            .withMetalakeName(metalakeName)
            .withAuditInfo("{}")
            .withSchemaVersion("1.0")
            .withCurrentVersion(1L)
            .withLastVersion(0L)
            .withDeletedAt(0L)
            .build());
  }

  private void softDeleteMetalake(long metalakeId) {
    MetalakeMetaMapper metalakeMetaMapper = sharedSession.getMapper(MetalakeMetaMapper.class);
    metalakeMetaMapper.softDeleteMetalakeMetaByMetalakeId(metalakeId);
  }

  private ScimTokenMetaPO createTokenMeta(
      long tokenId, long metalakeId, String tokenName, String tokenHash, long expiresAt) {
    return ScimTokenMetaPO.builder()
        .withTokenId(tokenId)
        .withMetalakeId(metalakeId)
        .withTokenName(tokenName)
        .withTokenHash(tokenHash)
        .withExpiresAt(expiresAt)
        .withAuditInfo("{}")
        .withDeletedAt(0L)
        .withUpdatedAt(0L)
        .build();
  }
}
