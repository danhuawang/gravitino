/*
 * Copyright 2026 Datastrato Pvt Ltd.
 * This software is licensed under the Apache License version 2.
 */

package com.datastrato.gravitino.scim;

import com.datastrato.gravitino.scim.basic.token.ScimTokenGenerator;
import com.datastrato.gravitino.scim.basic.token.ScimTokenGenerator.GeneratedToken;
import com.datastrato.gravitino.scim.model.CreatedScimToken;
import com.datastrato.gravitino.scim.model.ScimToken;
import com.datastrato.gravitino.scim.storage.po.ScimTokenMetaPO;
import com.datastrato.gravitino.scim.storage.relational.ScimGarbageCollector;
import com.datastrato.gravitino.scim.storage.relational.ScimRelationalStorage;
import com.datastrato.gravitino.scim.storage.relational.utils.ScimExceptionUtils;
import com.datastrato.gravitino.scim.storage.service.ScimTokenMetaService;
import com.google.common.base.Preconditions;
import java.io.Closeable;
import java.io.IOException;
import java.time.Instant;
import javax.annotation.Nullable;
import org.apache.commons.lang3.StringUtils;
import org.apache.gravitino.Config;
import org.apache.gravitino.Entity;
import org.apache.gravitino.EntityStore;
import org.apache.gravitino.NameIdentifier;
import org.apache.gravitino.exceptions.AlreadyExistsException;
import org.apache.gravitino.exceptions.MetalakeNotInUseException;
import org.apache.gravitino.exceptions.NoSuchEntityException;
import org.apache.gravitino.exceptions.NoSuchMetalakeException;
import org.apache.gravitino.exceptions.NotFoundException;
import org.apache.gravitino.exceptions.TokenExpiredException;
import org.apache.gravitino.exceptions.UnauthorizedException;
import org.apache.gravitino.json.JsonUtils;
import org.apache.gravitino.meta.AuditInfo;
import org.apache.gravitino.meta.BaseMetalake;
import org.apache.gravitino.metalake.MetalakeManager;
import org.apache.gravitino.storage.IdGenerator;
import org.apache.gravitino.utils.NameIdentifierUtil;
import org.apache.gravitino.utils.PrincipalUtils;

/** Manager for SCIM token lifecycle, bearer authentication, and relational storage bootstrap. */
public class ScimTokenManager implements Closeable {

  private static final ScimTokenMetaService TOKEN_META_SERVICE = ScimTokenMetaService.getInstance();
  private static final int MAX_HASH_COLLISION_RETRIES = 3;
  private static final long MILLIS_PER_DAY = 86_400_000L;

  private static final class InstanceHolder {
    private static final ScimTokenManager INSTANCE = new ScimTokenManager();
  }

  private EntityStore entityStore;
  private ScimRelationalStorage relationalStorage;
  private ScimGarbageCollector garbageCollector;
  private IdGenerator idGenerator;

  /** Returns the shared SCIM token manager for the server process. */
  public static ScimTokenManager getInstance() {
    return InstanceHolder.INSTANCE;
  }

  ScimTokenManager() {}

  /**
   * Initializes relational storage, background cleanup, and metalake resolution dependencies.
   *
   * @param config the server configuration
   * @param entityStore the entity store for metalake resolution
   * @param idGenerator the id generator
   */
  public synchronized void initialize(
      Config config, EntityStore entityStore, IdGenerator idGenerator) {
    Preconditions.checkNotNull(config, "config must not be null");
    Preconditions.checkNotNull(entityStore, "entityStore must not be null");
    Preconditions.checkNotNull(idGenerator, "idGenerator must not be null");
    Preconditions.checkState(this.entityStore == null, "ScimTokenManager is already initialized");

    this.entityStore = entityStore;
    this.idGenerator = idGenerator;
    this.relationalStorage = new ScimRelationalStorage(config);
    this.garbageCollector = new ScimGarbageCollector(config);
    this.garbageCollector.start();
  }

  ScimTokenManager(Config config, EntityStore entityStore, IdGenerator idGenerator) {
    this.entityStore = entityStore;
    this.relationalStorage = new ScimRelationalStorage(config);
    this.idGenerator = idGenerator;
    this.garbageCollector = new ScimGarbageCollector(config);
    this.garbageCollector.start();
  }

  /**
   * Creates a new SCIM token for the given metalake.
   *
   * @param metalakeName target metalake name
   * @param tokenName operator-readable token name
   * @param expiresInDays optional fixed lifetime in days; {@code null} means no expiry
   * @return created token metadata and one-time plaintext value
   * @throws IOException if persistence fails
   */
  public CreatedScimToken createScimToken(
      String metalakeName, String tokenName, @Nullable Integer expiresInDays) throws IOException {
    validateTokenName(tokenName);
    validateExpiresInDays(expiresInDays);
    long metalakeId = resolveMetalakeId(metalakeName);
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
              .withMetalakeId(metalakeId)
              .withTokenName(tokenName)
              .withTokenHash(generated.getTokenHash())
              .withExpiresAt(expiresAt)
              .withAuditInfo(toAuditInfoJson(auditInfo))
              .withDeletedAt(0L)
              .withUpdatedAt(0L)
              .build();
      try {
        TOKEN_META_SERVICE.insertScimToken(tokenMeta);
        return CreatedScimToken.builder()
            .withTokenName(tokenName)
            .withTokenValue(generated.getTokenValue())
            .withExpiresAt(expiresAt)
            .build();
      } catch (RuntimeException re) {
        handleInsertCollision(re, metalakeName, tokenName);
      }
    }
    throw new IllegalStateException("Failed to generate unique SCIM token hash");
  }

  /**
   * Rotates the bearer secret for an existing named token.
   *
   * @param metalakeName target metalake name
   * @param tokenName existing token name
   * @param expiresInDays optional new lifetime in days; {@code null} keeps the current expiry
   * @return rotated token metadata and one-time plaintext value
   * @throws IOException if persistence fails
   */
  public CreatedScimToken rotateScimToken(
      String metalakeName, String tokenName, @Nullable Integer expiresInDays) throws IOException {
    validateTokenName(tokenName);
    validateExpiresInDays(expiresInDays);
    resolveMetalakeId(metalakeName);
    ScimTokenMetaPO oldTokenMeta =
        TOKEN_META_SERVICE.getScimTokenMetaByMetalakeAndName(metalakeName, tokenName);
    Instant now = Instant.now();
    long expiresAt =
        expiresInDays == null
            ? oldTokenMeta.getExpiresAt()
            : computeExpiresAt(now.toEpochMilli(), expiresInDays);
    AuditInfo existingAuditInfo = fromAuditInfoJson(oldTokenMeta.getAuditInfo());
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
              .withMetalakeId(oldTokenMeta.getMetalakeId())
              .withTokenName(oldTokenMeta.getTokenName())
              .withTokenHash(generated.getTokenHash())
              .withExpiresAt(expiresAt)
              .withAuditInfo(toAuditInfoJson(auditInfo))
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

  /**
   * Soft-deletes the named SCIM token for the given metalake.
   *
   * @param metalakeName target metalake name
   * @param tokenName token name to revoke
   * @return true when an active token row was soft-deleted
   */
  public boolean deleteScimToken(String metalakeName, String tokenName) {
    validateTokenName(tokenName);
    resolveMetalakeId(metalakeName);
    return TOKEN_META_SERVICE.softDeleteScimToken(metalakeName, tokenName);
  }

  /**
   * Authenticates a presented SCIM bearer token against the URL metalake scope.
   *
   * @param bearerToken full bearer token value from the {@code Authorization} header
   * @param metalakeName metalake name parsed from the SCIM request path
   */
  public void authenticateBearerToken(String bearerToken, String metalakeName) {
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

    long urlMetalakeId;
    try {
      urlMetalakeId = resolveMetalakeId(metalakeName);
    } catch (NoSuchMetalakeException | MetalakeNotInUseException e) {
      throw new UnauthorizedException("Invalid SCIM bearer token");
    }
    if (token.getMetalakeId() != urlMetalakeId) {
      throw new UnauthorizedException("Invalid SCIM bearer token");
    }
  }

  @Override
  public void close() throws IOException {
    garbageCollector.close();
    relationalStorage.close();
  }

  private long resolveMetalakeId(String metalakeName) {
    NameIdentifier ident = NameIdentifierUtil.ofMetalake(metalakeName);
    MetalakeManager.checkMetalake(ident, entityStore);
    try {
      return entityStore.get(ident, Entity.EntityType.METALAKE, BaseMetalake.class).id();
    } catch (NoSuchEntityException e) {
      throw new NoSuchMetalakeException("Metalake %s does not exist", ident);
    } catch (IOException e) {
      throw new RuntimeException("Failed to load metalake: " + metalakeName, e);
    }
  }

  private void handleInsertCollision(RuntimeException re, String metalakeName, String tokenName)
      throws IOException {
    if (!ScimExceptionUtils.isDuplicateEntry(re)) {
      ScimExceptionUtils.checkSQLException(re, "token", tokenName);
      throw re;
    }

    try {
      TOKEN_META_SERVICE.getScimToken(metalakeName, tokenName);
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

  private static String toAuditInfoJson(AuditInfo auditInfo) {
    try {
      return JsonUtils.anyFieldMapper().writeValueAsString(auditInfo);
    } catch (IOException e) {
      throw new IllegalStateException("Failed to serialize audit info", e);
    }
  }

  private static AuditInfo fromAuditInfoJson(String auditInfo) {
    if (auditInfo == null || auditInfo.isBlank()) {
      return AuditInfo.EMPTY;
    }
    try {
      return JsonUtils.anyFieldMapper().readValue(auditInfo, AuditInfo.class);
    } catch (IOException e) {
      throw new IllegalStateException("Failed to deserialize audit info", e);
    }
  }
}
