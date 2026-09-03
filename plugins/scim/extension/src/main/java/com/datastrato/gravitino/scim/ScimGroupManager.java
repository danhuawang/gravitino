/*
 * Copyright 2026 Datastrato Inc.
 */

package com.datastrato.gravitino.scim;

import com.datastrato.gravitino.scim.model.ScimGroupMeta;
import com.datastrato.gravitino.scim.storage.po.ScimGroupMetaPO;
import com.datastrato.gravitino.scim.storage.relational.ScimRelationalStorage;
import com.datastrato.gravitino.scim.storage.relational.utils.ScimExceptionUtils;
import com.datastrato.gravitino.scim.storage.relational.utils.ScimPOConverters;
import com.datastrato.gravitino.scim.storage.service.ScimGroupMetaService;
import com.datastrato.gravitino.scim.storage.service.ScimUserGroupRelMetaService;
import com.google.common.base.Preconditions;
import java.io.Closeable;
import java.io.IOException;
import java.util.List;
import java.util.Objects;
import javax.annotation.Nullable;
import org.apache.commons.lang3.StringUtils;
import org.apache.gravitino.Config;
import org.apache.gravitino.authorization.PagedResult;
import org.apache.gravitino.exceptions.AlreadyExistsException;
import org.apache.gravitino.exceptions.NotFoundException;
import org.apache.gravitino.storage.IdGenerator;
import org.apache.gravitino.storage.relational.utils.POConverters;

/** Manager for SCIM group lifecycle backed by {@code scim_group_meta}. */
public class ScimGroupManager implements Closeable {

  private static final ScimGroupMetaService GROUP_META_SERVICE = ScimGroupMetaService.getInstance();
  private static final ScimUserGroupRelMetaService USER_GROUP_REL_META_SERVICE =
      ScimUserGroupRelMetaService.getInstance();

  private static final class InstanceHolder {
    private static final ScimGroupManager INSTANCE = new ScimGroupManager();
  }

  private ScimRelationalStorage relationalStorage;
  private IdGenerator idGenerator;

  /** Returns the shared SCIM group manager for the server process. */
  public static ScimGroupManager getInstance() {
    return InstanceHolder.INSTANCE;
  }

  ScimGroupManager() {}

  /**
   * Initializes relational storage dependencies.
   *
   * @param config the server configuration
   * @param idGenerator the id generator
   */
  public synchronized void initialize(Config config, IdGenerator idGenerator) {
    Preconditions.checkNotNull(config, "config must not be null");
    Preconditions.checkNotNull(idGenerator, "idGenerator must not be null");
    Preconditions.checkState(this.idGenerator == null, "ScimGroupManager is already initialized");
    this.idGenerator = idGenerator;
    this.relationalStorage = new ScimRelationalStorage(config);
  }

  ScimGroupManager(Config config, IdGenerator idGenerator) {
    this.idGenerator = idGenerator;
    this.relationalStorage = new ScimRelationalStorage(config);
  }

  /**
   * Creates a SCIM group.
   *
   * @param groupName SCIM displayName
   * @param externalId optional client externalId; not used as SCIM resource id
   * @return created group metadata
   * @throws IOException if persistence fails
   */
  public ScimGroupMeta createGroup(String groupName, @Nullable String externalId)
      throws IOException {
    validateGroupName(groupName);
    String resolvedExternalId = ScimUtils.blankToNull(externalId);
    if (GROUP_META_SERVICE.getScimGroupByGroupNameIgnoreCase(groupName) != null) {
      throw new AlreadyExistsException("SCIM group %s already exists", groupName);
    }
    if (resolvedExternalId != null
        && GROUP_META_SERVICE.getScimGroupByExternalId(resolvedExternalId) != null) {
      throw new AlreadyExistsException(
          "SCIM group externalId %s already exists", resolvedExternalId);
    }
    ScimGroupMetaPO groupMeta =
        ScimGroupMetaPO.builder()
            .withGroupId(idGenerator.nextId())
            .withGroupName(groupName)
            .withGroupComment("")
            .withExternalId(resolvedExternalId)
            .withCurrentVersion(POConverters.INIT_VERSION)
            .withLastVersion(POConverters.INIT_VERSION)
            .withDeletedAt(0L)
            .build();
    try {
      GROUP_META_SERVICE.insertScimGroup(groupMeta);
    } catch (RuntimeException re) {
      if (ScimExceptionUtils.isDuplicateEntry(re)) {
        throw new AlreadyExistsException("SCIM group %s already exists", groupName);
      }
      throw re;
    }
    return ScimPOConverters.fromGroupPO(groupMeta);
  }

  /**
   * Returns a group by Gravitino group id (SCIM resource id).
   *
   * @param groupId Gravitino {@code group_id}
   * @return group metadata
   */
  public ScimGroupMeta getGroup(long groupId) {
    return GROUP_META_SERVICE.requireScimGroupByGroupId(groupId);
  }

  /**
   * Returns a group by SCIM externalId.
   *
   * @param externalId SCIM externalId
   * @return group metadata
   */
  public ScimGroupMeta getGroupByExternalId(String externalId) {
    return GROUP_META_SERVICE.requireScimGroupByExternalId(externalId);
  }

  /**
   * Finds a group by displayName using case-insensitive matching.
   *
   * @param groupName SCIM displayName
   * @return matching group metadata, or {@code null} when absent
   */
  @Nullable
  public ScimGroupMeta findGroupByGroupNameIgnoreCase(String groupName) {
    if (StringUtils.isBlank(groupName)) {
      return null;
    }
    return GROUP_META_SERVICE.getScimGroupByGroupNameIgnoreCase(groupName);
  }

  /**
   * Lists groups with pagination.
   *
   * @param offset zero-based offset
   * @param limit maximum rows
   * @return paged groups
   */
  public PagedResult<ScimGroupMeta> listGroups(int offset, int limit) {
    List<ScimGroupMeta> groups = GROUP_META_SERVICE.listScimGroups(offset, limit);
    return new PagedResult<>(GROUP_META_SERVICE.countScimGroups(), groups);
  }

  /**
   * Updates the SCIM externalId for a group.
   *
   * @param groupId Gravitino {@code group_id} (SCIM resource id)
   * @param externalId new client externalId; blank or {@code null} clears it
   * @return updated group metadata
   */
  public ScimGroupMeta updateExternalId(long groupId, @Nullable String externalId) {
    String resolvedExternalId = ScimUtils.blankToNull(externalId);
    ScimGroupMeta existing = GROUP_META_SERVICE.requireScimGroupByGroupId(groupId);
    if (Objects.equals(resolvedExternalId, existing.getExternalId())) {
      return existing;
    }
    if (resolvedExternalId != null) {
      ScimGroupMeta conflict = GROUP_META_SERVICE.getScimGroupByExternalId(resolvedExternalId);
      if (conflict != null && conflict.getGroupId() != groupId) {
        throw new AlreadyExistsException(
            "SCIM group externalId %s already exists", resolvedExternalId);
      }
    }
    if (!GROUP_META_SERVICE.updateScimGroupExternalId(groupId, resolvedExternalId)) {
      throw new NotFoundException("SCIM group not found: %s", groupId);
    }
    return GROUP_META_SERVICE.requireScimGroupByGroupId(groupId);
  }

  /**
   * Soft-deletes a group and cascades membership removal.
   *
   * @param groupId Gravitino {@code group_id} (SCIM resource id)
   * @return true when a group row was soft-deleted
   */
  public boolean deleteGroup(long groupId) {
    ScimGroupMeta group = GROUP_META_SERVICE.getScimGroupByGroupId(groupId);
    if (group == null) {
      return false;
    }
    USER_GROUP_REL_META_SERVICE.softDeleteMembersByGroupId(group.getGroupId());
    return GROUP_META_SERVICE.softDeleteScimGroup(groupId);
  }

  @Override
  public void close() throws IOException {
    if (relationalStorage != null) {
      relationalStorage.close();
    }
  }

  private static void validateGroupName(String groupName) {
    Preconditions.checkArgument(
        StringUtils.isNotBlank(groupName), "groupName must not be null or empty");
  }
}
