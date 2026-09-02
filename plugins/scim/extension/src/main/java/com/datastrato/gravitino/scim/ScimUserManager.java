/*
 * Copyright 2026 Datastrato Inc.
 */

package com.datastrato.gravitino.scim;

import com.datastrato.gravitino.scim.model.ScimUserMeta;
import com.datastrato.gravitino.scim.storage.po.ScimUserMetaPO;
import com.datastrato.gravitino.scim.storage.relational.ScimRelationalStorage;
import com.datastrato.gravitino.scim.storage.relational.utils.ScimExceptionUtils;
import com.datastrato.gravitino.scim.storage.relational.utils.ScimPOConverters;
import com.datastrato.gravitino.scim.storage.service.ScimUserGroupRelMetaService;
import com.datastrato.gravitino.scim.storage.service.ScimUserMetaService;
import com.google.common.base.Preconditions;
import java.io.Closeable;
import java.io.IOException;
import java.util.List;
import javax.annotation.Nullable;
import org.apache.commons.lang3.StringUtils;
import org.apache.gravitino.Config;
import org.apache.gravitino.authorization.PagedResult;
import org.apache.gravitino.exceptions.AlreadyExistsException;
import org.apache.gravitino.exceptions.NotFoundException;
import org.apache.gravitino.storage.IdGenerator;
import org.apache.gravitino.storage.relational.utils.POConverters;

/** Manager for SCIM user lifecycle backed by {@code scim_user_meta}. */
public class ScimUserManager implements Closeable {

  private static final ScimUserMetaService USER_META_SERVICE = ScimUserMetaService.getInstance();
  private static final ScimUserGroupRelMetaService USER_GROUP_REL_META_SERVICE =
      ScimUserGroupRelMetaService.getInstance();

  private static final class InstanceHolder {
    private static final ScimUserManager INSTANCE = new ScimUserManager();
  }

  private ScimRelationalStorage relationalStorage;
  private IdGenerator idGenerator;

  /** Returns the shared SCIM user manager for the server process. */
  public static ScimUserManager getInstance() {
    return InstanceHolder.INSTANCE;
  }

  ScimUserManager() {}

  /**
   * Initializes relational storage dependencies.
   *
   * @param config the server configuration
   * @param idGenerator the id generator
   */
  public synchronized void initialize(Config config, IdGenerator idGenerator) {
    Preconditions.checkNotNull(config, "config must not be null");
    Preconditions.checkNotNull(idGenerator, "idGenerator must not be null");
    Preconditions.checkState(this.idGenerator == null, "ScimUserManager is already initialized");
    this.idGenerator = idGenerator;
    this.relationalStorage = new ScimRelationalStorage(config);
  }

  ScimUserManager(Config config, IdGenerator idGenerator) {
    this.idGenerator = idGenerator;
    this.relationalStorage = new ScimRelationalStorage(config);
  }

  /**
   * Creates a SCIM user.
   *
   * @param userName SCIM userName
   * @param externalId optional client externalId; not used as SCIM resource id
   * @param enabled SCIM active flag
   * @return created user metadata
   * @throws IOException if persistence fails
   */
  public ScimUserMeta createUser(String userName, @Nullable String externalId, boolean enabled)
      throws IOException {
    validateUserName(userName);
    String resolvedExternalId = ScimUtils.blankToNull(externalId);
    if (USER_META_SERVICE.getScimUserByUserNameIgnoreCase(userName) != null) {
      throw new AlreadyExistsException("SCIM user %s already exists", userName);
    }
    if (resolvedExternalId != null
        && USER_META_SERVICE.getScimUserByExternalId(resolvedExternalId) != null) {
      throw new AlreadyExistsException(
          "SCIM user externalId %s already exists", resolvedExternalId);
    }
    ScimUserMetaPO userMeta =
        ScimUserMetaPO.builder()
            .withUserId(idGenerator.nextId())
            .withUserName(userName)
            .withExternalId(resolvedExternalId)
            .withEnabled(enabled)
            .withCurrentVersion(POConverters.INIT_VERSION)
            .withLastVersion(POConverters.INIT_VERSION)
            .withDeletedAt(0L)
            .build();
    try {
      USER_META_SERVICE.insertScimUser(userMeta);
    } catch (RuntimeException re) {
      if (ScimExceptionUtils.isDuplicateEntry(re)) {
        throw new AlreadyExistsException("SCIM user %s already exists", userName);
      }
      throw re;
    }
    return ScimPOConverters.fromUserPO(userMeta);
  }

  /**
   * Returns a user by Gravitino user id (SCIM resource id).
   *
   * @param userId Gravitino {@code user_id}
   * @return user metadata
   */
  public ScimUserMeta getUser(long userId) {
    return USER_META_SERVICE.requireScimUserByUserId(userId);
  }

  /**
   * Returns a user by SCIM externalId.
   *
   * @param externalId SCIM externalId
   * @return user metadata
   */
  public ScimUserMeta getUserByExternalId(String externalId) {
    return USER_META_SERVICE.requireScimUserByExternalId(externalId);
  }

  /**
   * Returns users matching the given SCIM externalIds.
   *
   * @param externalIds SCIM externalIds; {@code null} or empty yields an empty list
   * @return matching user metadata rows
   */
  public List<ScimUserMeta> listUsersByExternalIds(@Nullable List<String> externalIds) {
    return USER_META_SERVICE.listScimUsersByExternalIds(externalIds);
  }

  /**
   * Finds a user by userName using case-insensitive matching.
   *
   * @param userName SCIM userName
   * @return matching user metadata, or {@code null} when absent
   */
  @Nullable
  public ScimUserMeta findUserByUserNameIgnoreCase(String userName) {
    if (StringUtils.isBlank(userName)) {
      return null;
    }
    return USER_META_SERVICE.getScimUserByUserNameIgnoreCase(userName);
  }

  /**
   * Lists users with pagination.
   *
   * @param offset zero-based offset
   * @param limit maximum rows
   * @return paged users
   */
  public PagedResult<ScimUserMeta> listUsers(int offset, int limit) {
    List<ScimUserMeta> users = USER_META_SERVICE.listScimUsers(offset, limit);
    return new PagedResult<>(USER_META_SERVICE.countScimUsers(), users);
  }

  /**
   * Updates the enabled flag for a user.
   *
   * @param userId Gravitino {@code user_id} (SCIM resource id)
   * @param enabled SCIM active flag
   * @return updated user metadata
   */
  public ScimUserMeta updateEnabled(long userId, boolean enabled) {
    if (!USER_META_SERVICE.updateScimUserEnabled(userId, enabled)) {
      throw new NotFoundException("SCIM user not found: %s", userId);
    }
    return USER_META_SERVICE.requireScimUserByUserId(userId);
  }

  /**
   * Soft-deletes a user and cascades membership removal.
   *
   * @param userId Gravitino {@code user_id} (SCIM resource id)
   * @return true when a user row was soft-deleted
   */
  public boolean deleteUser(long userId) {
    ScimUserMeta user = USER_META_SERVICE.getScimUserByUserId(userId);
    if (user == null) {
      return false;
    }
    USER_GROUP_REL_META_SERVICE.softDeleteMembersByUserId(user.getUserId());
    return USER_META_SERVICE.softDeleteScimUser(userId);
  }

  @Override
  public void close() throws IOException {
    if (relationalStorage != null) {
      relationalStorage.close();
    }
  }

  private static void validateUserName(String userName) {
    Preconditions.checkArgument(
        StringUtils.isNotBlank(userName), "userName must not be null or empty");
  }
}
