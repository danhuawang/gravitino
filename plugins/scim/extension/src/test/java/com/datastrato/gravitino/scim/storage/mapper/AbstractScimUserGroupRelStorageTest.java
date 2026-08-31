/*
 * Copyright 2026 Datastrato Inc.
 */

package com.datastrato.gravitino.scim.storage.mapper;

import com.datastrato.gravitino.scim.storage.po.ScimGroupMemberPO;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;
import org.apache.gravitino.storage.relational.mapper.GroupMetaMapper;
import org.apache.gravitino.storage.relational.mapper.MetalakeMetaMapper;
import org.apache.gravitino.storage.relational.mapper.UserMetaMapper;
import org.apache.gravitino.storage.relational.po.GroupPO;
import org.apache.gravitino.storage.relational.po.MetalakePO;
import org.apache.gravitino.storage.relational.po.UserPO;

/** Base helpers for SCIM user-group membership storage tests. */
public abstract class AbstractScimUserGroupRelStorageTest extends AbstractScimMetaStorageTest {
  protected static final String METALAKE_NAME = "test_metalake";
  protected static final long METALAKE_ID = 10L;
  protected static final long USER_ID = 100L;
  protected static final long GROUP_ID = 200L;
  protected static final String USERNAME = "alice";
  protected static final String GROUP_NAME = "engineers";

  protected ScimUserGroupRelMapper scimUserGroupRelMapper;

  @Override
  protected void initializeMappers() {
    scimUserGroupRelMapper = sharedSession.getMapper(ScimUserGroupRelMapper.class);
  }

  protected void insertMetalake() {
    insertMetalake(METALAKE_ID, METALAKE_NAME);
  }

  protected void insertMetalake(long metalakeId, String metalakeName) {
    MetalakeMetaMapper metalakeMetaMapper = sharedSession.getMapper(MetalakeMetaMapper.class);
    metalakeMetaMapper.insertMetalakeMeta(
        MetalakePO.builder()
            .withMetalakeId(metalakeId)
            .withMetalakeName(metalakeName)
            .withAuditInfo("{}")
            .withSchemaVersion("1.0")
            .withCurrentVersion(1L)
            .withLastVersion(0L)
            .withDeletedAt(0L)
            .build());
  }

  protected void softDeleteMetalake(long metalakeId) {
    MetalakeMetaMapper metalakeMetaMapper = sharedSession.getMapper(MetalakeMetaMapper.class);
    metalakeMetaMapper.softDeleteMetalakeMetaByMetalakeId(metalakeId);
  }

  protected static String externalIdForUser(long userId) {
    return "user-ext-" + userId;
  }

  protected static String externalIdForGroup(long groupId) {
    return "group-ext-" + groupId;
  }

  protected void insertUser(long userId, String username) {
    insertUser(METALAKE_ID, userId, username, externalIdForUser(userId));
  }

  protected void insertUser(long userId, String username, String externalId) {
    insertUser(METALAKE_ID, userId, username, externalId);
  }

  protected void insertUser(long metalakeId, long userId, String username) {
    insertUser(metalakeId, userId, username, externalIdForUser(userId));
  }

  protected void insertUser(long metalakeId, long userId, String username, String externalId) {
    insertUser(metalakeId, userId, username, externalId, true);
  }

  protected void insertUser(
      long metalakeId, long userId, String username, String externalId, boolean enabled) {
    UserMetaMapper userMetaMapper = sharedSession.getMapper(UserMetaMapper.class);
    userMetaMapper.insertUserMeta(
        UserPO.builder()
            .withUserId(userId)
            .withUserName(username)
            .withMetalakeId(metalakeId)
            .withExternalId(externalId)
            .withEnabled(enabled)
            .withAuditInfo("{}")
            .withCurrentVersion(1L)
            .withLastVersion(0L)
            .withDeletedAt(0L)
            .build());
  }

  protected void insertGroup(long groupId, String groupName) {
    insertGroup(METALAKE_ID, groupId, groupName);
  }

  protected void insertGroup(long metalakeId, long groupId, String groupName) {
    insertGroup(metalakeId, groupId, groupName, externalIdForGroup(groupId));
  }

  protected void insertGroup(long metalakeId, long groupId, String groupName, String externalId) {
    GroupMetaMapper groupMetaMapper = sharedSession.getMapper(GroupMetaMapper.class);
    groupMetaMapper.insertGroupMeta(
        GroupPO.builder()
            .withGroupId(groupId)
            .withGroupName(groupName)
            .withMetalakeId(metalakeId)
            .withExternalId(externalId)
            .withAuditInfo("{}")
            .withCurrentVersion(1L)
            .withLastVersion(0L)
            .withDeletedAt(0L)
            .build());
  }

  protected void insertMembership(long userId, long groupId) {
    insertMembership(METALAKE_NAME, userId, groupId);
  }

  protected void insertMembership(String metalakeName, long userId, long groupId) {
    scimUserGroupRelMapper.insertMemberships(metalakeName, groupId, List.of(userId), "{}", 1L, 0L);
  }

  protected Set<Long> memberUserIdsForGroup(long groupId) {
    return memberUserIdsForGroup(METALAKE_NAME, groupId);
  }

  protected Set<Long> memberUserIdsForGroup(String metalakeName, long groupId) {
    return scimUserGroupRelMapper.selectMembersByGroupId(metalakeName, groupId).stream()
        .map(ScimGroupMemberPO::getUserId)
        .collect(Collectors.toSet());
  }
}
