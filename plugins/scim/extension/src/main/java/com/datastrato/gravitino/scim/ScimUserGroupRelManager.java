/*
 * Copyright 2026 Datastrato Pvt Ltd.
 * This software is licensed under the Apache License version 2.
 */

package com.datastrato.gravitino.scim;

import com.datastrato.gravitino.scim.storage.po.ScimGroupMemberPO;
import com.datastrato.gravitino.scim.storage.relational.ScimRelationalStorage;
import com.datastrato.gravitino.scim.storage.relational.utils.ScimPOConverters;
import com.datastrato.gravitino.scim.storage.service.ScimUserGroupRelMetaService;
import com.google.common.base.Preconditions;
import java.io.Closeable;
import java.io.IOException;
import java.time.Instant;
import java.util.List;
import java.util.stream.Collectors;
import org.apache.gravitino.Config;
import org.apache.gravitino.meta.AuditInfo;
import org.apache.gravitino.storage.relational.utils.POConverters;
import org.apache.gravitino.utils.PrincipalUtils;

/** Manager for SCIM user-group membership lifecycle and relational storage bootstrap. */
public class ScimUserGroupRelManager implements Closeable {

  private static final ScimUserGroupRelMetaService USER_GROUP_REL_META_SERVICE =
      ScimUserGroupRelMetaService.getInstance();

  private static final class InstanceHolder {
    private static final ScimUserGroupRelManager INSTANCE = new ScimUserGroupRelManager();
  }

  private ScimRelationalStorage relationalStorage;

  /** Returns the shared SCIM user-group membership manager for the server process. */
  public static ScimUserGroupRelManager getInstance() {
    return InstanceHolder.INSTANCE;
  }

  ScimUserGroupRelManager() {}

  /**
   * Initializes relational storage dependencies.
   *
   * @param config the server configuration
   */
  public synchronized void initialize(Config config) {
    Preconditions.checkNotNull(config, "config must not be null");
    Preconditions.checkState(
        this.relationalStorage == null, "ScimUserGroupRelManager is already initialized");

    this.relationalStorage = new ScimRelationalStorage(config);
  }

  ScimUserGroupRelManager(Config config) {
    this.relationalStorage = new ScimRelationalStorage(config);
  }

  /**
   * Lists active group names for a user in the given metalake.
   *
   * @param metalakeName target metalake name
   * @param username user name
   * @return group names sorted alphabetically
   */
  public List<String> listGroupNamesForUser(String metalakeName, String username) {
    return USER_GROUP_REL_META_SERVICE.listGroupNamesByUsername(username, metalakeName);
  }

  /**
   * Lists active members for a group in the given metalake.
   *
   * @param metalakeName target metalake name
   * @param groupId Gravitino group id
   * @return active members sorted by username
   */
  public List<ScimGroupMemberPO> listMembersForGroup(String metalakeName, long groupId) {
    return USER_GROUP_REL_META_SERVICE.listMembersByGroupId(metalakeName, groupId).stream()
        .sorted((left, right) -> left.getUserName().compareTo(right.getUserName()))
        .collect(Collectors.toList());
  }

  /**
   * Adds users to a group without removing existing members.
   *
   * @param metalakeName target metalake name
   * @param groupId Gravitino group id
   * @param userIds Gravitino user ids from PATCH {@code members[].value}
   * @throws IOException if persistence fails
   */
  public void addUsersToGroup(String metalakeName, long groupId, List<Long> userIds)
      throws IOException {
    if (userIds == null || userIds.isEmpty()) {
      return;
    }

    AuditInfo auditInfo =
        AuditInfo.builder()
            .withCreator(PrincipalUtils.getCurrentPrincipal().getName())
            .withCreateTime(Instant.now())
            .build();
    USER_GROUP_REL_META_SERVICE.insertMemberships(
        metalakeName,
        groupId,
        userIds,
        ScimPOConverters.serializeAuditInfo(auditInfo),
        POConverters.INIT_VERSION,
        POConverters.INIT_VERSION);
  }

  /**
   * Removes users from a group.
   *
   * @param metalakeName target metalake name
   * @param groupId Gravitino group id
   * @param userIds Gravitino user ids from PATCH {@code members[].value}
   */
  public void removeUsersFromGroup(String metalakeName, long groupId, List<Long> userIds) {
    if (userIds == null || userIds.isEmpty()) {
      return;
    }

    USER_GROUP_REL_META_SERVICE.softDeleteMembersByGroupAndUserIds(metalakeName, groupId, userIds);
  }

  /**
   * Replaces the full membership set for a group.
   *
   * @param metalakeName target metalake name
   * @param groupId Gravitino group id
   * @param userIds Gravitino user ids from PATCH Replace
   * @throws IOException if persistence fails
   */
  public void replaceUsersInGroup(String metalakeName, long groupId, List<Long> userIds)
      throws IOException {
    AuditInfo auditInfo =
        AuditInfo.builder()
            .withCreator(PrincipalUtils.getCurrentPrincipal().getName())
            .withCreateTime(Instant.now())
            .build();
    USER_GROUP_REL_META_SERVICE.replaceMembersByGroupId(
        metalakeName,
        groupId,
        userIds == null ? List.of() : userIds,
        ScimPOConverters.serializeAuditInfo(auditInfo),
        POConverters.INIT_VERSION,
        POConverters.INIT_VERSION);
  }

  @Override
  public void close() throws IOException {
    relationalStorage.close();
  }
}
