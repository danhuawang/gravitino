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
import org.apache.commons.lang3.StringUtils;
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
   * Lists active usernames for a group in the given metalake.
   *
   * @param metalakeName target metalake name
   * @param groupExternalId SCIM group {@code externalId}
   * @return usernames sorted alphabetically
   */
  public List<String> listUsernamesForGroup(String metalakeName, String groupExternalId) {
    validateGroupExternalId(groupExternalId);
    return USER_GROUP_REL_META_SERVICE
        .listMembersByGroupExternalId(metalakeName, groupExternalId)
        .stream()
        .map(ScimGroupMemberPO::getUserName)
        .sorted()
        .collect(Collectors.toList());
  }

  /**
   * Adds users to a group without removing existing members.
   *
   * @param metalakeName target metalake name
   * @param groupExternalId SCIM group {@code externalId}
   * @param userExternalIds SCIM member ids from PATCH {@code members[].value}
   * @throws IOException if persistence fails
   */
  public void addUsersToGroup(
      String metalakeName, String groupExternalId, List<String> userExternalIds)
      throws IOException {
    validateGroupExternalId(groupExternalId);
    if (userExternalIds == null || userExternalIds.isEmpty()) {
      return;
    }

    AuditInfo auditInfo =
        AuditInfo.builder()
            .withCreator(PrincipalUtils.getCurrentPrincipal().getName())
            .withCreateTime(Instant.now())
            .build();
    USER_GROUP_REL_META_SERVICE.insertMemberships(
        metalakeName,
        groupExternalId,
        userExternalIds,
        ScimPOConverters.serializeAuditInfo(auditInfo),
        POConverters.INIT_VERSION,
        POConverters.INIT_VERSION);
  }

  /**
   * Removes users from a group.
   *
   * @param metalakeName target metalake name
   * @param groupExternalId SCIM group {@code externalId}
   * @param userExternalIds SCIM member ids from PATCH {@code members[].value}
   */
  public void removeUsersFromGroup(
      String metalakeName, String groupExternalId, List<String> userExternalIds) {
    validateGroupExternalId(groupExternalId);
    if (userExternalIds == null || userExternalIds.isEmpty()) {
      return;
    }

    USER_GROUP_REL_META_SERVICE.softDeleteMembersByGroupAndUserExternalIds(
        metalakeName, groupExternalId, userExternalIds);
  }

  /**
   * Replaces the full membership set for a group.
   *
   * @param metalakeName target metalake name
   * @param groupExternalId SCIM group {@code externalId}
   * @param userExternalIds SCIM member ids from PATCH Replace
   * @throws IOException if persistence fails
   */
  public void replaceUsersInGroup(
      String metalakeName, String groupExternalId, List<String> userExternalIds)
      throws IOException {
    validateGroupExternalId(groupExternalId);
    AuditInfo auditInfo =
        AuditInfo.builder()
            .withCreator(PrincipalUtils.getCurrentPrincipal().getName())
            .withCreateTime(Instant.now())
            .build();
    USER_GROUP_REL_META_SERVICE.replaceMembersByGroupExternalId(
        metalakeName,
        groupExternalId,
        userExternalIds == null ? List.of() : userExternalIds,
        ScimPOConverters.serializeAuditInfo(auditInfo),
        POConverters.INIT_VERSION,
        POConverters.INIT_VERSION);
  }

  @Override
  public void close() throws IOException {
    relationalStorage.close();
  }

  private static void validateGroupExternalId(String groupExternalId) {
    Preconditions.checkArgument(
        StringUtils.isNotBlank(groupExternalId), "groupExternalId is required");
  }
}
