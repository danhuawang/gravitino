/*
 * Copyright 2026 Datastrato Inc.
 */

package com.datastrato.gravitino.scim;

import com.datastrato.gravitino.scim.basic.token.ScimTokenGenerator;
import com.datastrato.gravitino.scim.basic.token.ScimTokenGenerator.GeneratedToken;
import com.datastrato.gravitino.scim.model.CreatedScimToken;
import com.datastrato.gravitino.scim.model.ScimProvisioningSummary;
import com.datastrato.gravitino.scim.model.ScimToken;
import com.datastrato.gravitino.scim.model.ScimTokenOverview;
import com.datastrato.gravitino.scim.model.ScimTokenSummary;
import com.datastrato.gravitino.scim.storage.po.ScimTokenMetaPO;
import com.datastrato.gravitino.scim.storage.relational.ScimGarbageCollector;
import com.datastrato.gravitino.scim.storage.relational.ScimRelationalStorage;
import com.datastrato.gravitino.scim.storage.relational.utils.ScimExceptionUtils;
import com.datastrato.gravitino.scim.storage.relational.utils.ScimPOConverters;
import com.datastrato.gravitino.scim.storage.service.ScimTokenMetaService;
import com.google.common.base.Preconditions;
import java.io.Closeable;
import java.io.IOException;
import java.time.Instant;
import java.util.List;
import java.util.stream.Collectors;
import javax.annotation.Nullable;
import org.apache.commons.lang3.StringUtils;
import org.apache.gravitino.Config;
import org.apache.gravitino.exceptions.AlreadyExistsException;
import org.apache.gravitino.exceptions.NotFoundException;
import org.apache.gravitino.exceptions.TokenExpiredException;
import org.apache.gravitino.exceptions.UnauthorizedException;
import org.apache.gravitino.meta.AuditInfo;
import org.apache.gravitino.storage.IdGenerator;
import org.apache.gravitino.utils.PrincipalUtils;

/** Manager for SCIM token lifecycle and bearer authentication. */
public class ScimTokenManager implements Closeable {

  private static final ScimTokenMetaService TOKEN_META_SERVICE = ScimTokenMetaService.getInstance();
  private static final int MAX_HASH_COLLISION_RETRIES = 3;
  private static final long MILLIS_PER_DAY = 86_400_000L;

  private static final class InstanceHolder {
    private static final ScimTokenManager INSTANCE = new ScimTokenManager();
  }

  private ScimRelationalStorage relationalStorage;
  private ScimGarbageCollector garbageCollector;
  private IdGenerator idGenerator;

  /** Returns the shared SCIM token manager for the server process. */
  public static ScimTokenManager getInstance() {
    return InstanceHolder.INSTANCE;
  }

  ScimTokenManager() {}

  /**
   * Initializes relational storage, background cleanup, and id generation dependencies.
   *
   * @param config the server configuration
   * @param idGenerator the id generator
   */
  public synchronized void initialize(Config config, IdGenerator idGenerator) {
    Preconditions.checkNotNull(config, "config must not be null");
    Preconditions.checkNotNull(idGenerator, "idGenerator must not be null");
    Preconditions.checkState(this.idGenerator == null, "ScimTokenManager is already initialized");
    // Initialize dependents before marking this manager ready. Setting idGenerator first left a
    // half-initialized singleton when ScimRelationalStorage failed (for example default jdbc:h2),
    // so later callers treated TokenManager as ready while User/Group managers stayed null.
    this.relationalStorage = new ScimRelationalStorage(config);
    this.garbageCollector = new ScimGarbageCollector(config);
    this.garbageCollector.start();
    ScimErrorHistoryManager.getInstance().initialize(idGenerator);
    ScimUserManager.getInstance().initialize(config, idGenerator);
    ScimGroupManager.getInstance().initialize(config, idGenerator);
    this.idGenerator = idGenerator;
  }

  ScimTokenManager(Config config, IdGenerator idGenerator) {
    this.idGenerator = idGenerator;
    this.relationalStorage = new ScimRelationalStorage(config);
    this.garbageCollector = new ScimGarbageCollector(config);
    this.garbageCollector.start();
    ScimErrorHistoryManager.getInstance().initialize(idGenerator);
    ScimUserManager.getInstance().initialize(config, idGenerator);
    ScimGroupManager.getInstance().initialize(config, idGenerator);
  }

  /** Creates a new SCIM token. */
  public CreatedScimToken createScimToken(String tokenName, @Nullable Integer expiresInDays)
      throws IOException {
    validateTokenName(tokenName);
    validateExpiresInDays(expiresInDays);
    Instant now = Instant.now();
    long expiresAt = computeExpiresAt(now.toEpochMilli(), expiresInDays);
    AuditInfo auditInfo =
        AuditInfo.builder()
            .withCreator(PrincipalUtils.getCurrentPrincipal().getName())
            .withCreateTime(now)
            .build();
    for (int attempt = 0; attempt < MAX_HASH_COLLISION_RETRIES; attempt++) {
      GeneratedToken generated = ScimTokenGenerator.generate();
      ScimTokenMetaPO tokenMeta =
          ScimTokenMetaPO.builder()
              .withTokenId(idGenerator.nextId())
              .withTokenName(tokenName)
              .withTokenHash(generated.getTokenHash())
              .withExpiresAt(expiresAt)
              .withAuditInfo(ScimPOConverters.serializeAuditInfo(auditInfo))
              .withDeletedAt(0L)
              .withUpdatedAt(0L)
              .withLastUsedAt(0L)
              .build();
      try {
        TOKEN_META_SERVICE.insertScimToken(tokenMeta);
        return CreatedScimToken.builder()
            .withTokenName(tokenName)
            .withTokenValue(generated.getTokenValue())
            .withExpiresAt(expiresAt)
            .build();
      } catch (RuntimeException re) {
        handleInsertCollision(re, tokenName);
      }
    }
    throw new IllegalStateException("Failed to generate unique SCIM token hash");
  }

  /** Rotates the bearer secret for an existing named token. */
  public CreatedScimToken rotateScimToken(String tokenName, @Nullable Integer expiresInDays)
      throws IOException {
    validateTokenName(tokenName);
    validateExpiresInDays(expiresInDays);
    ScimTokenMetaPO oldTokenMeta = TOKEN_META_SERVICE.getScimTokenMetaByName(tokenName);
    Instant now = Instant.now();
    long expiresAt =
        expiresInDays == null
            ? oldTokenMeta.getExpiresAt()
            : computeExpiresAt(now.toEpochMilli(), expiresInDays);
    AuditInfo existingAuditInfo =
        ScimPOConverters.deserializeAuditInfo(oldTokenMeta.getAuditInfo());
    AuditInfo auditInfo =
        AuditInfo.builder()
            .withCreator(existingAuditInfo.creator())
            .withCreateTime(existingAuditInfo.createTime())
            .withLastModifier(PrincipalUtils.getCurrentPrincipal().getName())
            .withLastModifiedTime(now)
            .build();
    for (int attempt = 0; attempt < MAX_HASH_COLLISION_RETRIES; attempt++) {
      GeneratedToken generated = ScimTokenGenerator.generate();
      ScimTokenMetaPO newTokenMeta =
          ScimTokenMetaPO.builder()
              .withTokenId(oldTokenMeta.getTokenId())
              .withTokenName(oldTokenMeta.getTokenName())
              .withTokenHash(generated.getTokenHash())
              .withExpiresAt(expiresAt)
              .withAuditInfo(ScimPOConverters.serializeAuditInfo(auditInfo))
              .withDeletedAt(0L)
              .withUpdatedAt(0L)
              .build();
      try {
        if (!TOKEN_META_SERVICE.updateScimTokenOnRotate(newTokenMeta, oldTokenMeta)) {
          throw new NotFoundException("SCIM token not found: %s", tokenName);
        }
        return CreatedScimToken.builder()
            .withTokenName(tokenName)
            .withTokenValue(generated.getTokenValue())
            .withExpiresAt(expiresAt)
            .build();
      } catch (RuntimeException re) {
        if (ScimExceptionUtils.isDuplicateEntry(re)) {
          continue;
        }
        throw re;
      }
    }
    throw new IllegalStateException("Failed to generate unique SCIM token hash during rotation");
  }

  /** Soft-deletes the named SCIM token. */
  public boolean deleteScimToken(String tokenName) {
    validateTokenName(tokenName);
    return TOKEN_META_SERVICE.softDeleteScimToken(tokenName);
  }

  /** Lists active SCIM tokens. */
  public List<ScimTokenSummary> listScimTokens() {
    long nowMillis = System.currentTimeMillis();
    return TOKEN_META_SERVICE.listScimTokens().stream()
        .map(tokenMeta -> ScimTokenSummary.from(tokenMeta, nowMillis))
        .collect(Collectors.toList());
  }

  /** Returns the latest {@code last_used_at} among active SCIM tokens. */
  public long getMaxScimTokenLastUsedAt() {
    return TOKEN_META_SERVICE.getMaxScimTokenLastUsedAt();
  }

  /**
   * Returns the SCIM token overview for the Identity Provider admin UI.
   *
   * @return overview with {@code lastUsedAt}, {@code tokenCount}, and token rows
   */
  public ScimTokenOverview getScimTokenOverview() {
    List<ScimTokenSummary> tokens = listScimTokens();
    return ScimTokenOverview.of(getMaxScimTokenLastUsedAt(), tokens);
  }

  /** Builds the SCIM provisioning overview row. */
  public ScimProvisioningSummary getProvisioningSummary() {
    return ScimProvisioningSummary.from(TOKEN_META_SERVICE.listProvisioningStats());
  }

  /** Authenticates a presented SCIM bearer token. */
  public ScimToken authenticateBearerToken(String bearerToken) {
    if (!ScimTokenGenerator.hasValidPrefix(bearerToken)) {
      throw new UnauthorizedException("Invalid SCIM bearer token");
    }
    String tokenHash = ScimTokenGenerator.hashToken(bearerToken);
    ScimToken token = TOKEN_META_SERVICE.getScimTokenByHash(tokenHash);
    if (token == null) {
      throw new UnauthorizedException("Invalid SCIM bearer token");
    }
    if (token.getExpiresAt() > 0L && System.currentTimeMillis() >= token.getExpiresAt()) {
      throw new TokenExpiredException("SCIM token has expired");
    }
    return token;
  }

  /** Updates {@code last_used_at} after bearer auth succeeds. */
  public void updateScimTokenLastUsedAt(long tokenId) {
    TOKEN_META_SERVICE.updateScimTokenLastUsedAt(tokenId);
  }

  @Override
  public void close() throws IOException {
    garbageCollector.close();
    relationalStorage.close();
  }

  private void handleInsertCollision(RuntimeException re, String tokenName) throws IOException {
    if (!ScimExceptionUtils.isDuplicateEntry(re)) {
      ScimExceptionUtils.checkSQLException(re, "token", tokenName);
      throw re;
    }
    try {
      TOKEN_META_SERVICE.getScimToken(tokenName);
      throw new AlreadyExistsException("SCIM token %s already exists", tokenName);
    } catch (NotFoundException e) {
      // Hash collision on a different token name; retry generation.
    }
  }

  private static void validateTokenName(String tokenName) {
    Preconditions.checkArgument(
        StringUtils.isNotBlank(tokenName), "tokenName must not be null or empty");
  }

  private static void validateExpiresInDays(@Nullable Integer expiresInDays) {
    if (expiresInDays != null) {
      Preconditions.checkArgument(expiresInDays > 0, "expiresInDays must be positive");
    }
  }

  private static long computeExpiresAt(long nowMillis, @Nullable Integer expiresInDays) {
    if (expiresInDays == null) {
      return 0L;
    }
    return nowMillis + expiresInDays.longValue() * MILLIS_PER_DAY;
  }
}
