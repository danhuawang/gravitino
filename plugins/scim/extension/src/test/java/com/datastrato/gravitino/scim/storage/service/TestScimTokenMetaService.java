/*
 * Copyright 2026 Datastrato Pvt Ltd.
 * This software is licensed under the Apache License version 2.
 */

package com.datastrato.gravitino.scim.storage.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.datastrato.gravitino.scim.storage.po.ScimTokenMetaPO;
import java.io.IOException;
import java.time.Instant;
import org.apache.gravitino.exceptions.AlreadyExistsException;
import org.apache.gravitino.exceptions.NotFoundException;
import org.apache.gravitino.json.JsonUtils;
import org.apache.gravitino.meta.AuditInfo;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;

@Tag("gravitino-docker-test")
class TestScimTokenMetaService extends AbstractScimMetaServiceTest {

  @ParameterizedTest
  @MethodSource("storageProvider")
  void testGetByHash(String type) throws IOException {
    init(type);
    insertMetalake();
    insertToken(1L, METALAKE_ID, "scim-token", "hash-a", 0L);
    ScimTokenMetaService tokenMetaService = ScimTokenMetaService.getInstance();

    assertNull(tokenMetaService.getScimTokenMetaByHash("missing"));
    assertEquals("scim-token", tokenMetaService.getScimTokenMetaByHash("hash-a").getTokenName());
    assertNull(tokenMetaService.getScimTokenByHash("missing"));
    assertEquals("scim-token", tokenMetaService.getScimTokenByHash("hash-a").getTokenName());
  }

  @ParameterizedTest
  @MethodSource("storageProvider")
  void testGet(String type) throws IOException {
    init(type);
    insertMetalake();
    insertToken(1L, METALAKE_ID, "scim-token", "hash-a", 0L);
    ScimTokenMetaService tokenMetaService = ScimTokenMetaService.getInstance();

    assertEquals(
        "scim-token",
        tokenMetaService
            .getScimTokenMetaByMetalakeAndName(METALAKE_NAME, "scim-token")
            .getTokenName());
    assertEquals(
        "scim-token", tokenMetaService.getScimToken(METALAKE_NAME, "scim-token").getTokenName());
    assertThrows(
        NotFoundException.class, () -> tokenMetaService.getScimToken(METALAKE_NAME, "missing"));
  }

  @ParameterizedTest
  @MethodSource("storageProvider")
  void testInsert(String type) throws IOException {
    init(type);
    insertMetalake();
    ScimTokenMetaService tokenMetaService = ScimTokenMetaService.getInstance();
    ScimTokenMetaPO tokenMeta = createTokenMeta(1L, METALAKE_ID, "scim-token", "hash-a", 0L);

    assertThrows(
        NotFoundException.class, () -> tokenMetaService.getScimToken(METALAKE_NAME, "scim-token"));
    runServiceCall(() -> tokenMetaService.insertScimToken(tokenMeta));
    assertEquals("scim-token", tokenMetaService.getScimTokenByHash("hash-a").getTokenName());

    ScimTokenMetaPO duplicateToken = createTokenMeta(2L, METALAKE_ID, "scim-token", "hash-b", 0L);
    assertThrows(
        AlreadyExistsException.class, () -> tokenMetaService.insertScimToken(duplicateToken));
  }

  @ParameterizedTest
  @MethodSource("storageProvider")
  void testUpdateOnRotate(String type) throws IOException {
    init(type);
    insertMetalake();
    ScimTokenMetaPO oldTokenMeta = createTokenMeta(1L, METALAKE_ID, "scim-token", "hash-a", 1000L);
    insertToken(1L, METALAKE_ID, "scim-token", "hash-a", 1000L);
    ScimTokenMetaService tokenMetaService = ScimTokenMetaService.getInstance();

    AuditInfo updatedAuditInfo =
        AuditInfo.builder()
            .withCreator("creator")
            .withCreateTime(Instant.parse("2026-01-01T00:00:00Z"))
            .withLastModifier("rotator")
            .withLastModifiedTime(Instant.parse("2026-01-02T00:00:00Z"))
            .build();
    ScimTokenMetaPO newTokenMeta =
        ScimTokenMetaPO.builder()
            .withTokenId(1L)
            .withMetalakeId(METALAKE_ID)
            .withTokenName("scim-token")
            .withTokenHash("hash-b")
            .withExpiresAt(2000L)
            .withAuditInfo(JsonUtils.anyFieldMapper().writeValueAsString(updatedAuditInfo))
            .withDeletedAt(0L)
            .withUpdatedAt(0L)
            .build();

    runServiceCall(
        () -> assertTrue(tokenMetaService.updateScimTokenOnRotate(newTokenMeta, oldTokenMeta)));
    assertEquals("scim-token", tokenMetaService.getScimTokenByHash("hash-b").getTokenName());
    assertNull(scimTokenMetaMapper.selectByTokenHash("hash-a"));

    ScimTokenMetaPO staleOldTokenMeta = oldTokenMeta;
    runServiceCall(
        () ->
            assertFalse(tokenMetaService.updateScimTokenOnRotate(newTokenMeta, staleOldTokenMeta)));
  }

  @ParameterizedTest
  @MethodSource("storageProvider")
  void testSoftDelete(String type) throws IOException {
    init(type);
    insertMetalake();
    insertToken(1L, METALAKE_ID, "scim-token", "hash-a", 0L);
    ScimTokenMetaService tokenMetaService = ScimTokenMetaService.getInstance();

    runServiceCall(
        () -> assertTrue(tokenMetaService.softDeleteScimToken(METALAKE_NAME, "scim-token")));
    assertNull(scimTokenMetaMapper.selectByTokenHash("hash-a"));
    assertEquals(1, countTokens());

    runServiceCall(
        () -> assertFalse(tokenMetaService.softDeleteScimToken(METALAKE_NAME, "scim-token")));
  }

  @ParameterizedTest
  @MethodSource("storageProvider")
  void testSoftDeleteExpired(String type) throws IOException {
    init(type);
    insertMetalake();
    scimTokenMetaMapper.insert(
        ScimTokenMetaPO.builder()
            .withTokenId(1L)
            .withMetalakeId(METALAKE_ID)
            .withTokenName("expired-token")
            .withTokenHash("hash-expired")
            .withExpiresAt(System.currentTimeMillis() - 60_000L)
            .withAuditInfo("{}")
            .withDeletedAt(0L)
            .withUpdatedAt(0L)
            .build());
    insertToken(2L, METALAKE_ID, "active-token", "hash-active", 0L);
    ScimTokenMetaService tokenMetaService = ScimTokenMetaService.getInstance();

    closeSession();
    assertEquals(1, tokenMetaService.softDeleteExpiredScimTokens());
    refreshSession();
    assertNull(scimTokenMetaMapper.selectByTokenHash("hash-expired"));
    assertEquals(
        "active-token", scimTokenMetaMapper.selectByTokenHash("hash-active").getTokenName());
  }

  @ParameterizedTest
  @MethodSource("storageProvider")
  void testSoftDeleteUnavailableMetalake(String type) throws IOException {
    init(type);
    long deletedMetalakeId = 20L;
    long missingMetalakeId = 99L;

    insertMetalake();
    insertToken(1L, METALAKE_ID, "active-token", "hash-active", 0L);

    insertMetalake(deletedMetalakeId, "deleted_metalake");
    insertToken(2L, deletedMetalakeId, "deleted-metalake-token", "hash-deleted", 0L);
    softDeleteMetalake(deletedMetalakeId);

    insertToken(3L, missingMetalakeId, "missing-metalake-token", "hash-missing", 0L);
    ScimTokenMetaService tokenMetaService = ScimTokenMetaService.getInstance();

    closeSession();
    assertEquals(2, tokenMetaService.softDeleteScimTokensByUnavailableMetalake());
    refreshSession();
    assertEquals(
        "active-token", scimTokenMetaMapper.selectByTokenHash("hash-active").getTokenName());
    assertNull(scimTokenMetaMapper.selectByTokenHash("hash-deleted"));
    assertNull(scimTokenMetaMapper.selectByTokenHash("hash-missing"));
  }

  @ParameterizedTest
  @MethodSource("storageProvider")
  void testDeleteLegacyTimeline(String type) throws IOException {
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
    insertToken(3L, METALAKE_ID, "active-token", "hash-c", 0L);
    ScimTokenMetaService tokenMetaService = ScimTokenMetaService.getInstance();

    closeSession();
    assertEquals(1, tokenMetaService.deleteTokenMetasByLegacyTimeline(20L, 10));
    assertEquals(0, tokenMetaService.deleteTokenMetasByLegacyTimeline(20L, 10));
    assertEquals(1, tokenMetaService.deleteTokenMetasByLegacyTimeline(40L, 10));
    assertEquals(0, tokenMetaService.deleteTokenMetasByLegacyTimeline(Long.MAX_VALUE, 10));
    refreshSession();
    assertEquals("active-token", scimTokenMetaMapper.selectByTokenHash("hash-c").getTokenName());
    assertNull(scimTokenMetaMapper.selectByTokenHash("hash-a"));
    assertNull(scimTokenMetaMapper.selectByTokenHash("hash-b"));
  }
}
