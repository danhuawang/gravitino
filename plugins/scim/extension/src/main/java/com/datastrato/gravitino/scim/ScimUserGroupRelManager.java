/*
 * Copyright 2026 Datastrato Inc.
 */

package com.datastrato.gravitino.scim;

import com.datastrato.gravitino.scim.storage.po.ScimGroupMemberPO;
import com.datastrato.gravitino.scim.storage.relational.ScimRelationalStorage;
import com.datastrato.gravitino.scim.storage.service.ScimUserGroupRelMetaService;
import com.google.common.base.Preconditions;
import java.io.Closeable;
import java.io.IOException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;
import javax.annotation.Nullable;
import org.apache.gravitino.Config;
import org.apache.gravitino.storage.relational.utils.POConverters;

/** Manager for SCIM user-group membership lifecycle. */
public class ScimUserGroupRelManager implements Closeable {

  private static final ScimUserGroupRelMetaService USER_GROUP_REL_META_SERVICE =
      ScimUserGroupRelMetaService.getInstance();

  private static final class InstanceHolder {
    private static final ScimUserGroupRelManager INSTANCE = new ScimUserGroupRelManager();
  }

  private ScimRelationalStorage relationalStorage;

  /** Returns the shared SCIM user-group membership manager. */
  public static ScimUserGroupRelManager getInstance() {
    return InstanceHolder.INSTANCE;
  }

  ScimUserGroupRelManager() {}

  /** Initializes relational storage dependencies. */
  public synchronized void initialize(Config config) {
    Preconditions.checkNotNull(config, "config must not be null");
    Preconditions.checkState(
        this.relationalStorage == null, "ScimUserGroupRelManager is already initialized");
    this.relationalStorage = new ScimRelationalStorage(config);
  }

  ScimUserGroupRelManager(Config config) {
    this.relationalStorage = new ScimRelationalStorage(config);
  }

  /** Lists active group names for a user when the user exists and is enabled. */
  public List<String> listGroupNamesForUser(String username) {
    return USER_GROUP_REL_META_SERVICE.listGroupNamesByUsername(username);
  }

  /** Lists active members for a group. */
  public List<ScimGroupMemberPO> listMembersForGroup(long groupId) {
    return USER_GROUP_REL_META_SERVICE.listMembersByGroupId(groupId).stream()
        .sorted((left, right) -> left.getUserName().compareTo(right.getUserName()))
        .collect(Collectors.toList());
  }

  /**
   * Lists active members for many groups in one query.
   *
   * @param groupIds group ids; {@code null} or empty yields an empty map
   * @return members keyed by group id; absent groups map to an empty list
   */
  public Map<Long, List<ScimGroupMemberPO>> listMembersForGroups(@Nullable List<Long> groupIds) {
    Map<Long, List<ScimGroupMemberPO>> membersByGroup = new LinkedHashMap<>();
    if (groupIds == null || groupIds.isEmpty()) {
      return membersByGroup;
    }
    for (Long groupId : groupIds) {
      if (groupId != null) {
        membersByGroup.putIfAbsent(groupId, new ArrayList<>());
      }
    }
    for (ScimGroupMemberPO member : USER_GROUP_REL_META_SERVICE.listMembersByGroupIds(groupIds)) {
      if (member.getGroupId() == null) {
        continue;
      }
      membersByGroup.computeIfAbsent(member.getGroupId(), ignored -> new ArrayList<>()).add(member);
    }
    for (Map.Entry<Long, List<ScimGroupMemberPO>> entry : membersByGroup.entrySet()) {
      entry.getValue().sort((left, right) -> left.getUserName().compareTo(right.getUserName()));
    }
    return membersByGroup;
  }

  /** Adds users to a group without removing existing members. */
  public void addUsersToGroup(long groupId, List<Long> userIds) throws IOException {
    if (userIds == null || userIds.isEmpty()) {
      return;
    }
    USER_GROUP_REL_META_SERVICE.insertMemberships(
        groupId, userIds, POConverters.INIT_VERSION, POConverters.INIT_VERSION);
  }

  /** Removes users from a group. */
  public void removeUsersFromGroup(long groupId, List<Long> userIds) {
    if (userIds == null || userIds.isEmpty()) {
      return;
    }
    USER_GROUP_REL_META_SERVICE.softDeleteMembersByGroupAndUserIds(groupId, userIds);
  }

  /** Replaces the full membership set for a group. */
  public void replaceUsersInGroup(long groupId, List<Long> userIds) throws IOException {
    USER_GROUP_REL_META_SERVICE.replaceMembersByGroupId(
        groupId,
        userIds == null ? List.of() : userIds,
        POConverters.INIT_VERSION,
        POConverters.INIT_VERSION);
  }

  /** Replaces one membership entry's user id when the replacement user exists. */
  public boolean replaceMemberUserInGroup(long groupId, long oldUserId, long newUserId)
      throws IOException {
    if (oldUserId == newUserId) {
      return true;
    }
    return USER_GROUP_REL_META_SERVICE.updateMemberUserId(
        groupId, oldUserId, newUserId, POConverters.INIT_VERSION, POConverters.INIT_VERSION);
  }

  @Override
  public void close() throws IOException {
    relationalStorage.close();
  }
}
