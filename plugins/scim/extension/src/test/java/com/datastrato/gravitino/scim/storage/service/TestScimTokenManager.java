/*
 * Copyright 2026 Datastrato Pvt Ltd.
 * This software is licensed under the Apache License version 2.
 */

package com.datastrato.gravitino.scim.storage.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.datastrato.gravitino.scim.basic.token.ScimTokenGenerator;
import com.datastrato.gravitino.scim.model.CreatedScimToken;
import com.datastrato.gravitino.scim.storage.po.ScimTokenMetaPO;
import java.time.Instant;
import org.apache.gravitino.UserPrincipal;
import org.apache.gravitino.exceptions.AlreadyExistsException;
import org.apache.gravitino.exceptions.NotFoundException;
import org.apache.gravitino.exceptions.TokenExpiredException;
import org.apache.gravitino.exceptions.UnauthorizedException;
import org.apache.gravitino.meta.AuditInfo;
import org.apache.gravitino.meta.BaseMetalake;
import org.apache.gravitino.meta.SchemaVersion;
import org.apache.gravitino.utils.PrincipalUtils;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;

@Tag("gravitino-docker-test")
class TestScimTokenManager extends AbstractScimManagerTest {
  private static final String METALAKE_NAME = "test_metalake";
  private static final String OTHER_METALAKE_NAME = "other_metalake";
  private static final long METALAKE_ID = 10L;
  private static final long OTHER_METALAKE_ID = 11L;

  @ParameterizedTest
  @MethodSource("storageProvider")
  void testCreate(String type) throws Exception {
    initManagerTest(type);
    insertMetalakes();

    CreatedScimToken created =
        runManagerCallAndReturnAs(
            "alice", () -> manager.createScimToken(METALAKE_NAME, "prod", 30));
    assertEquals("prod", created.getTokenName());
    assertTrue(created.getTokenValue().startsWith(ScimTokenGenerator.TOKEN_PREFIX));
    assertTrue(created.getExpiresAt() > System.currentTimeMillis());

    assertNull(scimTokenMetaMapper.selectByTokenHash("manual-hash"));
    ScimTokenMetaPO stored = scimTokenMetaMapper.selectByMetalakeAndName(METALAKE_NAME, "prod");
    assertNotNull(stored);
    assertEquals("prod", stored.getTokenName());
    assertEquals(ScimTokenGenerator.hashToken(created.getTokenValue()), stored.getTokenHash());
  }

  @ParameterizedTest
  @MethodSource("storageProvider")
  void testDuplicateName(String type) throws Exception {
    initManagerTest(type);
    insertMetalakes();

    runManagerCallAs("alice", () -> manager.createScimToken(METALAKE_NAME, "prod", null));
    assertThrows(
        AlreadyExistsException.class,
        () ->
            PrincipalUtils.doAs(
                new UserPrincipal("alice"),
                () -> manager.createScimToken(METALAKE_NAME, "prod", null)));
  }

  @ParameterizedTest
  @MethodSource("storageProvider")
  void testRotateAndDelete(String type) throws Exception {
    initManagerTest(type);
    insertMetalakes();

    CreatedScimToken created =
        runManagerCallAndReturnAs(
            "alice", () -> manager.createScimToken(METALAKE_NAME, "prod", null));
    String oldHash = ScimTokenGenerator.hashToken(created.getTokenValue());

    CreatedScimToken rotated =
        runManagerCallAndReturnAs("bob", () -> manager.rotateScimToken(METALAKE_NAME, "prod", 7));
    assertNotEquals(created.getTokenValue(), rotated.getTokenValue());
    assertNull(scimTokenMetaMapper.selectByTokenHash(oldHash));
    assertNotNull(
        scimTokenMetaMapper.selectByTokenHash(
            ScimTokenGenerator.hashToken(rotated.getTokenValue())));

    runManagerCall(() -> assertTrue(manager.deleteScimToken(METALAKE_NAME, "prod")));
    assertNull(
        scimTokenMetaMapper.selectByTokenHash(
            ScimTokenGenerator.hashToken(rotated.getTokenValue())));
    assertFalse(manager.deleteScimToken(METALAKE_NAME, "prod"));
  }

  @ParameterizedTest
  @MethodSource("storageProvider")
  void testAuthBearer(String type) throws Exception {
    initManagerTest(type);
    insertMetalakes();

    CreatedScimToken created =
        runManagerCallAndReturnAs(
            "alice", () -> manager.createScimToken(METALAKE_NAME, "prod", null));

    closeSession();
    manager.authenticateBearerToken(created.getTokenValue(), METALAKE_NAME);
    refreshSession();

    assertThrows(
        UnauthorizedException.class,
        () -> manager.authenticateBearerToken("invalid", METALAKE_NAME));
    assertThrows(
        UnauthorizedException.class,
        () -> manager.authenticateBearerToken(created.getTokenValue(), OTHER_METALAKE_NAME));
  }

  @ParameterizedTest
  @MethodSource("storageProvider")
  void testAuthExpired(String type) throws Exception {
    initManagerTest(type);
    insertMetalakes();

    scimTokenMetaMapper.insert(
        ScimTokenMetaPO.builder()
            .withTokenId(1L)
            .withMetalakeId(METALAKE_ID)
            .withTokenName("expired")
            .withTokenHash(ScimTokenGenerator.hashToken("gravitino_scim_expired"))
            .withExpiresAt(System.currentTimeMillis() - 60_000L)
            .withAuditInfo("{}")
            .withDeletedAt(0L)
            .withUpdatedAt(0L)
            .build());

    closeSession();
    assertThrows(
        TokenExpiredException.class,
        () -> manager.authenticateBearerToken("gravitino_scim_expired", METALAKE_NAME));
    refreshSession();
  }

  @ParameterizedTest
  @MethodSource("storageProvider")
  void testSoftDeleteExpired(String type) throws Exception {
    initManagerTest(type);
    insertMetalakes();

    scimTokenMetaMapper.insert(
        ScimTokenMetaPO.builder()
            .withTokenId(1L)
            .withMetalakeId(METALAKE_ID)
            .withTokenName("expired")
            .withTokenHash("hash-expired")
            .withExpiresAt(System.currentTimeMillis() - 60_000L)
            .withAuditInfo("{}")
            .withDeletedAt(0L)
            .withUpdatedAt(0L)
            .build());

    closeSession();
    assertEquals(1, ScimTokenMetaService.getInstance().softDeleteExpiredScimTokens());
    refreshSession();
    assertNull(scimTokenMetaMapper.selectByTokenHash("hash-expired"));
  }

  @ParameterizedTest
  @MethodSource("storageProvider")
  void testRotateMissingToken(String type) throws Exception {
    initManagerTest(type);
    insertMetalakes();

    assertThrows(
        NotFoundException.class,
        () ->
            PrincipalUtils.doAs(
                new UserPrincipal("bob"),
                () -> manager.rotateScimToken(METALAKE_NAME, "missing", null)));
  }

  private void insertMetalakes() throws Exception {
    insertMetalake(METALAKE_ID, METALAKE_NAME);
    insertMetalake(OTHER_METALAKE_ID, OTHER_METALAKE_NAME);
  }

  private void insertMetalake(long metalakeId, String metalakeName) throws Exception {
    PrincipalUtils.doAs(
        new UserPrincipal("test"),
        () -> {
          BaseMetalake metalake =
              BaseMetalake.builder()
                  .withId(metalakeId)
                  .withName(metalakeName)
                  .withVersion(SchemaVersion.V_0_1)
                  .withComment("test")
                  .withAuditInfo(
                      AuditInfo.builder()
                          .withCreator(PrincipalUtils.getCurrentPrincipal().getName())
                          .withCreateTime(Instant.now())
                          .build())
                  .build();
          entityStore.put(metalake, false);
          return null;
        });
  }
}
