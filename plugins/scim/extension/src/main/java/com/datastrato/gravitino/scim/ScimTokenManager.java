/*
 * Copyright 2026 Datastrato Inc.
 */

package com.datastrato.gravitino.scim;

import com.datastrato.gravitino.scim.basic.token.ScimTokenGenerator;
import com.datastrato.gravitino.scim.basic.token.ScimTokenGenerator.GeneratedToken;
import com.datastrato.gravitino.scim.model.CreatedScimToken;
import com.datastrato.gravitino.scim.model.ScimProvisioningSummary;
import com.datastrato.gravitino.scim.model.ScimToken;
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
import java.util.Arrays;
import java.util.List;
import java.util.stream.Collectors;
import javax.annotation.Nullable;
import org.apache.commons.lang3.StringUtils;
import org.apache.gravitino.Config;
import org.apache.gravitino.Entity;
import org.apache.gravitino.EntityStore;
import org.apache.gravitino.Metalake;
import org.apache.gravitino.NameIdentifier;
import org.apache.gravitino.exceptions.AlreadyExistsException;
import org.apache.gravitino.exceptions.MetalakeNotInUseException;
import org.apache.gravitino.exceptions.NoSuchEntityException;
import org.apache.gravitino.exceptions.NoSuchMetalakeException;
import org.apache.gravitino.exceptions.NotFoundException;
import org.apache.gravitino.exceptions.TokenExpiredException;
import org.apache.gravitino.exceptions.UnauthorizedException;
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
    ScimErrorHistoryManager.getInstance().initialize(idGenerator);
  }

  ScimTokenManager(Config config, EntityStore entityStore, IdGenerator idGenerator) {
    this.entityStore = entityStore;
    this.relationalStorage = new ScimRelationalStorage(config);
    this.idGenerator = idGenerator;
    this.garbageCollector = new ScimGarbageCollector(config);
    this.garbageCollector.start();
    ScimErrorHistoryManager.getInstance().initialize(idGenerator);
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
              .withMetalakeId(oldTokenMeta.getMetalakeId())
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
   * Lists active SCIM tokens for the given metalake.
   *
   * <p>Rows past {@code expiresAt} but not yet processed by the expiry task appear with {@code
   * status = expired}. Soft-deleted rows are omitted.
   *
   * @param metalakeName target metalake name
   * @return token rows sorted by token name
   */
  public List<ScimTokenSummary> listScimTokens(String metalakeName) {
    resolveMetalakeId(metalakeName);
    long nowMillis = System.currentTimeMillis();
    return TOKEN_META_SERVICE.listScimTokensByMetalake(metalakeName).stream()
        .map(tokenMeta -> ScimTokenSummary.from(tokenMeta, nowMillis))
        .collect(Collectors.toList());
  }

  /**
   * Returns the latest {@code last_used_at} among active SCIM tokens for a metalake.
   *
   * @param metalakeName target metalake name
   * @return max last used epoch millis, or {@code 0} when none
   */
  public long getMaxScimTokenLastUsedAt(String metalakeName) {
    resolveMetalakeId(metalakeName);
    return TOKEN_META_SERVICE.getMaxScimTokenLastUsedAt(metalakeName);
  }

  /**
   * Builds SCIM Provisioning overview rows for the given metalakes.
   *
   * <p>Metalakes with no active tokens appear with {@code tokenCount = 0} and {@code lastUsedAt =
   * 0}.
   *
   * @param metalakes metalakes to include (already auth-filtered)
   * @return overview rows sorted by metalake name
   */
  public List<ScimProvisioningSummary> listProvisioningSummaries(Metalake[] metalakes) {
    if (metalakes.length == 0) {
      return List.of();
    }

    List<Long> metalakeIds =
        Arrays.stream(metalakes)
            .map(
                metalake ->
                    metalake instanceof BaseMetalake
                        ? ((BaseMetalake) metalake).id()
                        : resolveMetalakeId(metalake.name()))
            .collect(Collectors.toList());

    return TOKEN_META_SERVICE.listProvisioningStatsByMetalakeIds(metalakeIds).stream()
        .map(ScimProvisioningSummary::from)
        .collect(Collectors.toList());
  }

  /**
   * Authenticates a presented SCIM bearer token against the URL metalake scope.
   *
   * @param bearerToken full bearer token value from the {@code Authorization} header
   * @param metalakeName metalake name parsed from the SCIM request path
   * @return authenticated token metadata used as the request principal identity
   */
  public ScimToken authenticateBearerToken(String bearerToken, String metalakeName) {
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
    return token;
  }

  /**
   * Updates {@code last_used_at} after bearer auth succeeds for a metalake-scoped SCIM request.
   *
   * <p>Called whether the resource later returns 2xx or 4xx/5xx.
   *
   * @param tokenId SCIM token id
   */
  public void updateScimTokenLastUsedAt(long tokenId) {
    TOKEN_META_SERVICE.updateScimTokenLastUsedAt(tokenId);
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
}
