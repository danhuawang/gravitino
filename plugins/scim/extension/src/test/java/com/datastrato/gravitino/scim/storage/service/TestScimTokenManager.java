/*
 * Copyright 2026 Datastrato Inc.
 */

package com.datastrato.gravitino.scim.storage.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.datastrato.gravitino.scim.ScimUtils;
import com.datastrato.gravitino.scim.basic.token.ScimTokenGenerator;
import com.datastrato.gravitino.scim.model.CreatedScimToken;
import com.datastrato.gravitino.scim.model.ScimProvisioningSummary;
import com.datastrato.gravitino.scim.model.ScimTokenOverview;
import com.datastrato.gravitino.scim.model.ScimTokenSummary;
import com.datastrato.gravitino.scim.storage.po.ScimTokenMetaPO;
import java.util.List;
import org.apache.gravitino.UserPrincipal;
import org.apache.gravitino.exceptions.AlreadyExistsException;
import org.apache.gravitino.exceptions.NotFoundException;
import org.apache.gravitino.exceptions.TokenExpiredException;
import org.apache.gravitino.exceptions.UnauthorizedException;
import org.apache.gravitino.utils.PrincipalUtils;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;

@Tag("gravitino-docker-test")
class TestScimTokenManager extends AbstractScimManagerTest {

  @ParameterizedTest
  @MethodSource("storageProvider")
  void testCreate(String type) throws Exception {
    initManagerTest(type);

    CreatedScimToken created =
        runManagerCallAndReturnAs("alice", () -> manager.createScimToken("prod", 30));
    assertEquals("prod", created.getTokenName());
    assertTrue(created.getTokenValue().startsWith(ScimTokenGenerator.TOKEN_PREFIX));
    assertTrue(created.getExpiresAt() > System.currentTimeMillis());

    assertNull(scimTokenMetaMapper.selectByTokenHash("manual-hash"));
    ScimTokenMetaPO stored = scimTokenMetaMapper.selectByName("prod");
    assertNotNull(stored);
    assertEquals("prod", stored.getTokenName());
    assertEquals(ScimTokenGenerator.hashToken(created.getTokenValue()), stored.getTokenHash());
  }

  @ParameterizedTest
  @MethodSource("storageProvider")
  void testDuplicateName(String type) throws Exception {
    initManagerTest(type);

    runManagerCallAs("alice", () -> manager.createScimToken("prod", null));
    assertThrows(
        AlreadyExistsException.class,
        () ->
            PrincipalUtils.doAs(
                new UserPrincipal("alice"), () -> manager.createScimToken("prod", null)));
  }

  @ParameterizedTest
  @MethodSource("storageProvider")
  void testRotateAndDelete(String type) throws Exception {
    initManagerTest(type);

    CreatedScimToken created =
        runManagerCallAndReturnAs("alice", () -> manager.createScimToken("prod", null));
    String oldHash = ScimTokenGenerator.hashToken(created.getTokenValue());

    CreatedScimToken rotated =
        runManagerCallAndReturnAs("bob", () -> manager.rotateScimToken("prod", 7));
    assertNotEquals(created.getTokenValue(), rotated.getTokenValue());
    assertNull(scimTokenMetaMapper.selectByTokenHash(oldHash));
    assertNotNull(
        scimTokenMetaMapper.selectByTokenHash(
            ScimTokenGenerator.hashToken(rotated.getTokenValue())));

    runManagerCall(() -> assertTrue(manager.deleteScimToken("prod")));
    assertNull(
        scimTokenMetaMapper.selectByTokenHash(
            ScimTokenGenerator.hashToken(rotated.getTokenValue())));
    assertFalse(manager.deleteScimToken("prod"));
  }

  @ParameterizedTest
  @MethodSource("storageProvider")
  void testRotateKeepsLastUsed(String type) throws Exception {
    initManagerTest(type);
    runManagerCallAndReturnAs("alice", () -> manager.createScimToken("prod", null));
    ScimTokenMetaPO meta = scimTokenMetaMapper.selectByName("prod");
    closeSession();
    manager.updateScimTokenLastUsedAt(meta.getTokenId());
    refreshSession();
    long lastUsed = scimTokenMetaMapper.selectByName("prod").getLastUsedAt();

    runManagerCallAndReturnAs("bob", () -> manager.rotateScimToken("prod", 7));
    ScimTokenMetaPO rotated = scimTokenMetaMapper.selectByName("prod");
    assertEquals(lastUsed, rotated.getLastUsedAt());
    assertTrue(rotated.getUpdatedAt() > 0L);
  }

  @ParameterizedTest
  @MethodSource("storageProvider")
  void testAuthBearer(String type) throws Exception {
    initManagerTest(type);

    CreatedScimToken created =
        runManagerCallAndReturnAs("alice", () -> manager.createScimToken("prod", null));

    closeSession();
    manager.authenticateBearerToken(created.getTokenValue());
    refreshSession();
    ScimTokenMetaPO stored = scimTokenMetaMapper.selectByName("prod");
    assertEquals(0L, stored.getLastUsedAt());

    closeSession();
    manager.updateScimTokenLastUsedAt(stored.getTokenId());
    refreshSession();
    assertTrue(scimTokenMetaMapper.selectByName("prod").getLastUsedAt() > 0L);

    assertThrows(UnauthorizedException.class, () -> manager.authenticateBearerToken("invalid"));
  }

  @ParameterizedTest
  @MethodSource("storageProvider")
  void testListTokens(String type) throws Exception {
    initManagerTest(type);
    runManagerCallAndReturnAs("alice", () -> manager.createScimToken("prod", null));
    runManagerCallAndReturnAs("alice", () -> manager.createScimToken("staging", 30));
    ScimTokenMetaPO prod = scimTokenMetaMapper.selectByName("prod");
    closeSession();
    manager.updateScimTokenLastUsedAt(prod.getTokenId());
    refreshSession();
    scimTokenMetaMapper.insert(
        ScimTokenMetaPO.builder()
            .withTokenId(99L)
            .withTokenName("expired")
            .withTokenHash("hash-expired")
            .withExpiresAt(System.currentTimeMillis() - 60_000L)
            .withAuditInfo("{}")
            .withDeletedAt(0L)
            .withUpdatedAt(0L)
            .build());

    List<ScimTokenSummary> tokens = manager.listScimTokens();
    assertEquals(3, tokens.size());
    assertEquals("expired", tokens.get(0).getTokenName());
    assertEquals("expired", tokens.get(0).getStatus());
    assertEquals("valid", tokens.get(1).getStatus());
    assertTrue(tokens.get(1).getLastUsedAt() > 0L);
    assertTrue(tokens.get(1).getCreatedAt() > 0L);
  }

  @ParameterizedTest
  @MethodSource("storageProvider")
  void testGetScimTokenOverview(String type) throws Exception {
    initManagerTest(type);
    runManagerCallAndReturnAs("alice", () -> manager.createScimToken("prod", null));
    runManagerCallAndReturnAs("alice", () -> manager.createScimToken("staging", null));
    ScimTokenMetaPO prod = scimTokenMetaMapper.selectByName("prod");
    closeSession();
    manager.updateScimTokenLastUsedAt(prod.getTokenId());
    refreshSession();

    ScimTokenOverview overview = manager.getScimTokenOverview();
    assertEquals(2L, overview.getTokenCount());
    assertTrue(overview.getLastUsedAt() > 0L);
    assertEquals(2, overview.getTokens().size());
    assertEquals(
        overview.getLastUsedAt(),
        overview.getTokens().stream().mapToLong(ScimTokenSummary::getLastUsedAt).max().orElse(0L));
  }

  @ParameterizedTest
  @MethodSource("storageProvider")
  void testProvisioningSummary(String type) throws Exception {
    initManagerTest(type);
    runManagerCallAndReturnAs("alice", () -> manager.createScimToken("prod", null));
    runManagerCallAndReturnAs("alice", () -> manager.createScimToken("staging", null));
    closeSession();
    manager.updateScimTokenLastUsedAt(scimTokenMetaMapper.selectByName("prod").getTokenId());
    refreshSession();

    ScimProvisioningSummary summary = manager.getProvisioningSummary();
    assertEquals(ScimUtils.scimBasePath(), summary.getEndpoint());
    assertEquals(2L, summary.getTokenCount());
    assertTrue(summary.getLastUsedAt() > 0L);
  }

  @ParameterizedTest
  @MethodSource("storageProvider")
  void testAuthExpired(String type) throws Exception {
    initManagerTest(type);

    scimTokenMetaMapper.insert(
        ScimTokenMetaPO.builder()
            .withTokenId(1L)
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
        () -> manager.authenticateBearerToken("gravitino_scim_expired"));
    refreshSession();
  }

  @ParameterizedTest
  @MethodSource("storageProvider")
  void testSoftDeleteExpired(String type) throws Exception {
    initManagerTest(type);

    scimTokenMetaMapper.insert(
        ScimTokenMetaPO.builder()
            .withTokenId(1L)
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

    assertThrows(
        NotFoundException.class,
        () ->
            PrincipalUtils.doAs(
                new UserPrincipal("bob"), () -> manager.rotateScimToken("missing", null)));
  }
}
