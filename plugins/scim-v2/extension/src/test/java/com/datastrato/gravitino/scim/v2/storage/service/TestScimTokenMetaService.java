/*
 * Copyright 2026 Datastrato Pvt Ltd.
 */

package com.datastrato.gravitino.scim.v2.storage.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.datastrato.gravitino.scim.v2.storage.po.ScimTokenMetaPO;
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
    insertToken(1L, "scim-token", "hash-a", 0L);
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
    insertToken(1L, "scim-token", "hash-a", 0L);
    ScimTokenMetaService tokenMetaService = ScimTokenMetaService.getInstance();

    assertEquals(
        "scim-token", tokenMetaService.getScimTokenMetaByName("scim-token").getTokenName());
    assertEquals("scim-token", tokenMetaService.getScimToken("scim-token").getTokenName());
    assertThrows(NotFoundException.class, () -> tokenMetaService.getScimToken("missing"));
  }

  @ParameterizedTest
  @MethodSource("storageProvider")
  void testList(String type) throws IOException {
    init(type);
    insertToken(1L, "token-a", "hash-a", 0L);
    insertToken(2L, "token-b", "hash-b", 0L);
    ScimTokenMetaService tokenMetaService = ScimTokenMetaService.getInstance();

    assertEquals(2, tokenMetaService.listScimTokens().size());
  }

  @ParameterizedTest
  @MethodSource("storageProvider")
  void testInsert(String type) throws IOException {
    init(type);
    ScimTokenMetaService tokenMetaService = ScimTokenMetaService.getInstance();
    ScimTokenMetaPO tokenMeta = createTokenMeta(1L, "scim-token", "hash-a", 0L);

    assertThrows(NotFoundException.class, () -> tokenMetaService.getScimToken("scim-token"));
    runServiceCall(() -> tokenMetaService.insertScimToken(tokenMeta));
    assertEquals("scim-token", tokenMetaService.getScimTokenByHash("hash-a").getTokenName());

    ScimTokenMetaPO duplicateToken = createTokenMeta(2L, "scim-token", "hash-b", 0L);
    assertThrows(
        AlreadyExistsException.class, () -> tokenMetaService.insertScimToken(duplicateToken));
  }

  @ParameterizedTest
  @MethodSource("storageProvider")
  void testUpdateOnRotate(String type) throws IOException {
    init(type);
    ScimTokenMetaPO oldTokenMeta = createTokenMeta(1L, "scim-token", "hash-a", 1000L);
    insertToken(1L, "scim-token", "hash-a", 1000L);
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
  void testTouchUsed(String type) throws IOException {
    init(type);
    insertToken(1L, "scim-token", "hash-a", 0L);
    ScimTokenMetaService svc = ScimTokenMetaService.getInstance();

    closeSession();
    assertTrue(svc.updateScimTokenLastUsedAt(1L));
    assertFalse(svc.updateScimTokenLastUsedAt(999L));
    refreshSession();
    assertTrue(svc.getScimTokenMetaByHash("hash-a").getLastUsedAt() > 0L);
  }

  @ParameterizedTest
  @MethodSource("storageProvider")
  void testTouchSkipDeleted(String type) throws IOException {
    init(type);
    insertToken(1L, "scim-token", "hash-a", 0L);
    ScimTokenMetaService svc = ScimTokenMetaService.getInstance();

    closeSession();
    svc.updateScimTokenLastUsedAt(1L);
    runServiceCall(() -> svc.softDeleteScimToken("scim-token"));
    assertFalse(svc.updateScimTokenLastUsedAt(1L));
  }

  @ParameterizedTest
  @MethodSource("storageProvider")
  void testSoftDelete(String type) throws IOException {
    init(type);
    insertToken(1L, "scim-token", "hash-a", 0L);
    ScimTokenMetaService tokenMetaService = ScimTokenMetaService.getInstance();

    runServiceCall(() -> assertTrue(tokenMetaService.softDeleteScimToken("scim-token")));
    assertNull(scimTokenMetaMapper.selectByTokenHash("hash-a"));
    assertEquals(1, countTokens());

    runServiceCall(() -> assertFalse(tokenMetaService.softDeleteScimToken("scim-token")));
  }

  @ParameterizedTest
  @MethodSource("storageProvider")
  void testSoftDeleteExpired(String type) throws IOException {
    init(type);
    scimTokenMetaMapper.insert(
        ScimTokenMetaPO.builder()
            .withTokenId(1L)
            .withTokenName("expired-token")
            .withTokenHash("hash-expired")
            .withExpiresAt(System.currentTimeMillis() - 60_000L)
            .withAuditInfo("{}")
            .withDeletedAt(0L)
            .withUpdatedAt(0L)
            .build());
    insertToken(2L, "active-token", "hash-active", 0L);
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
  void testDeleteLegacyTimeline(String type) throws IOException {
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
    insertToken(3L, "active-token", "hash-c", 0L);
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
