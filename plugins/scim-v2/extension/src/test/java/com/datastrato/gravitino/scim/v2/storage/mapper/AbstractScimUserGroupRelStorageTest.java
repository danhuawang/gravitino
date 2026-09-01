/*
 * Copyright 2026 Datastrato Inc.
 */

package com.datastrato.gravitino.scim.v2.storage.mapper;

import com.datastrato.gravitino.scim.v2.storage.po.ScimGroupMemberPO;
import com.datastrato.gravitino.scim.v2.storage.po.ScimGroupMetaPO;
import com.datastrato.gravitino.scim.v2.storage.po.ScimUserMetaPO;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

/** Base helpers for SCIM v2 user-group membership storage tests. */
public abstract class AbstractScimUserGroupRelStorageTest extends AbstractScimMetaStorageTest {
  protected static final long USER_ID = 100L;
  protected static final long GROUP_ID = 200L;
  protected static final String USERNAME = "alice";
  protected static final String GROUP_NAME = "engineers";

  protected ScimUserGroupRelMapper scimUserGroupRelMapper;
  protected ScimUserMetaMapper scimUserMetaMapper;
  protected ScimGroupMetaMapper scimGroupMetaMapper;

  @Override
  protected void initializeMappers() {
    scimUserGroupRelMapper = sharedSession.getMapper(ScimUserGroupRelMapper.class);
    scimUserMetaMapper = sharedSession.getMapper(ScimUserMetaMapper.class);
    scimGroupMetaMapper = sharedSession.getMapper(ScimGroupMetaMapper.class);
  }

  protected static String externalIdForUser(long userId) {
    return "user-ext-" + userId;
  }

  protected static String externalIdForGroup(long groupId) {
    return "group-ext-" + groupId;
  }

  protected void insertUser(long userId, String username) {
    insertUser(userId, username, externalIdForUser(userId));
  }

  protected void insertUser(long userId, String username, String externalId) {
    insertUser(userId, username, externalId, true);
  }

  protected void insertUser(long userId, String username, String externalId, boolean enabled) {
    scimUserMetaMapper.insert(
        ScimUserMetaPO.builder()
            .withUserId(userId)
            .withUserName(username)
            .withExternalId(externalId)
            .withEnabled(enabled)
            .withCurrentVersion(1L)
            .withLastVersion(0L)
            .withDeletedAt(0L)
            .build());
  }

  protected void softDeleteUser(long userId) {
    scimUserMetaMapper.softDeleteByExternalId(externalIdForUser(userId));
  }

  protected void insertGroup(long groupId, String groupName) {
    insertGroup(groupId, groupName, externalIdForGroup(groupId));
  }

  protected void insertGroup(long groupId, String groupName, String externalId) {
    scimGroupMetaMapper.insert(
        ScimGroupMetaPO.builder()
            .withGroupId(groupId)
            .withGroupName(groupName)
            .withExternalId(externalId)
            .withCurrentVersion(1L)
            .withLastVersion(0L)
            .withDeletedAt(0L)
            .build());
  }

  protected void softDeleteGroup(long groupId) {
    scimGroupMetaMapper.softDeleteByExternalId(externalIdForGroup(groupId));
  }

  protected void insertMembership(long userId, long groupId) {
    scimUserGroupRelMapper.insertMemberships(groupId, List.of(userId), 1L, 0L);
  }

  protected Set<Long> memberUserIdsForGroup(long groupId) {
    return scimUserGroupRelMapper.selectMembersByGroupId(groupId).stream()
        .map(ScimGroupMemberPO::getUserId)
        .collect(Collectors.toSet());
  }
}
