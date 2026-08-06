/*
 * Copyright 2026 Datastrato Pvt Ltd.
 * This software is licensed under the Apache License version 2.
 */

package com.datastrato.gravitino.scim.storage.relational;

import static org.apache.gravitino.Configs.STORE_DELETE_AFTER_TIME;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

import com.datastrato.gravitino.scim.storage.mapper.AbstractScimMetaStorageTest;
import com.datastrato.gravitino.scim.storage.mapper.ScimTokenMetaMapper;
import com.datastrato.gravitino.scim.storage.mapper.ScimUserGroupRelMapper;
import com.datastrato.gravitino.scim.storage.po.ScimTokenMetaPO;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.util.List;
import org.apache.gravitino.storage.relational.mapper.GroupMetaMapper;
import org.apache.gravitino.storage.relational.mapper.MetalakeMetaMapper;
import org.apache.gravitino.storage.relational.mapper.UserMetaMapper;
import org.apache.gravitino.storage.relational.po.GroupPO;
import org.apache.gravitino.storage.relational.po.MetalakePO;
import org.apache.gravitino.storage.relational.po.UserPO;
import org.apache.gravitino.storage.relational.session.SqlSessionFactoryHelper;
import org.apache.ibatis.session.SqlSession;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;

@Tag("gravitino-docker-test")
class TestScimGarbageCollector extends AbstractScimMetaStorageTest {
  private static final String METALAKE_NAME = "test_metalake";
  private static final long METALAKE_ID = 10L;
  private static final long USER_ID = 100L;
  private static final long GROUP_ID = 200L;
  private static final String USERNAME = "alice";
  private static final String GROUP_NAME = "engineers";

  @ParameterizedTest
  @MethodSource("storageProvider")
  void testCollectAndClean(String type) throws Exception {
    init(type);
    insertMetalake();
    scimTokenMetaMapper()
        .insert(
            ScimTokenMetaPO.builder()
                .withTokenId(1L)
                .withMetalakeId(METALAKE_ID)
                .withTokenName("legacy-token")
                .withTokenHash("hash-a")
                .withExpiresAt(0L)
                .withAuditInfo("{}")
                .withDeletedAt(System.currentTimeMillis() - 700_000L)
                .withUpdatedAt(0L)
                .build());
    scimTokenMetaMapper()
        .insert(
            ScimTokenMetaPO.builder()
                .withTokenId(2L)
                .withMetalakeId(METALAKE_ID)
                .withTokenName("active-token")
                .withTokenHash("hash-b")
                .withExpiresAt(0L)
                .withAuditInfo("{}")
                .withDeletedAt(0L)
                .withUpdatedAt(0L)
                .build());

    getConfig().set(STORE_DELETE_AFTER_TIME, 600_000L);
    closeSession();

    ScimGarbageCollector garbageCollector = new ScimGarbageCollector(getConfig());
    try {
      garbageCollector.collectAndClean();
    } finally {
      garbageCollector.close();
    }

    reopenSession();
    assertNull(scimTokenMetaMapper().selectByTokenHash("hash-a"));
    assertEquals("active-token", scimTokenMetaMapper().selectByTokenHash("hash-b").getTokenName());
  }

  @ParameterizedTest
  @MethodSource("storageProvider")
  void testCollectAndCleanMembership(String type) throws Exception {
    init(type);
    insertMetalake();
    insertUserAndGroup();
    scimUserGroupRelMapper()
        .insertMemberships(METALAKE_NAME, GROUP_ID, List.of(USER_ID), "{}", 1L, 0L);
    scimUserGroupRelMapper()
        .softDeleteMembersByGroupAndUserIds(METALAKE_NAME, GROUP_ID, List.of(USER_ID));
    updateLegacyDeletedAt(System.currentTimeMillis() - 700_000L);
    scimUserGroupRelMapper()
        .insertMemberships(METALAKE_NAME, GROUP_ID, List.of(USER_ID), "{}", 1L, 0L);

    getConfig().set(STORE_DELETE_AFTER_TIME, 600_000L);
    closeSession();

    ScimGarbageCollector garbageCollector = new ScimGarbageCollector(getConfig());
    try {
      garbageCollector.collectAndClean();
    } finally {
      garbageCollector.close();
    }

    reopenSession();
    assertEquals(
        1, scimUserGroupRelMapper().selectMembersByGroupId(METALAKE_NAME, GROUP_ID).size());
  }

  @ParameterizedTest
  @MethodSource("storageProvider")
  void testSoftDeleteExpired(String type) throws Exception {
    init(type);
    insertMetalake();
    scimTokenMetaMapper()
        .insert(
            ScimTokenMetaPO.builder()
                .withTokenId(1L)
                .withMetalakeId(METALAKE_ID)
                .withTokenName("expired")
                .withTokenHash("hash-expired")
                .withExpiresAt(System.currentTimeMillis() - 60_000L)
                .withAuditInfo("{}")
                .withDeletedAt(0L)
                .withUpdatedAt(0L)
                .build());

    closeSession();

    ScimGarbageCollector garbageCollector = new ScimGarbageCollector(getConfig());
    try {
      garbageCollector.softDeleteExpiredTokens();
    } finally {
      garbageCollector.close();
    }

    reopenSession();
    assertNull(scimTokenMetaMapper().selectByTokenHash("hash-expired"));
  }

  @ParameterizedTest
  @MethodSource("storageProvider")
  void testSoftDeleteUnavailableMetalake(String type) throws Exception {
    init(type);
    long deletedMetalakeId = 20L;
    long missingMetalakeId = 99L;

    insertMetalake();
    scimTokenMetaMapper()
        .insert(
            ScimTokenMetaPO.builder()
                .withTokenId(1L)
                .withMetalakeId(METALAKE_ID)
                .withTokenName("active-token")
                .withTokenHash("hash-active")
                .withExpiresAt(0L)
                .withAuditInfo("{}")
                .withDeletedAt(0L)
                .withUpdatedAt(0L)
                .build());

    insertMetalake(deletedMetalakeId, "deleted_metalake");
    scimTokenMetaMapper()
        .insert(
            ScimTokenMetaPO.builder()
                .withTokenId(2L)
                .withMetalakeId(deletedMetalakeId)
                .withTokenName("deleted-metalake-token")
                .withTokenHash("hash-deleted")
                .withExpiresAt(0L)
                .withAuditInfo("{}")
                .withDeletedAt(0L)
                .withUpdatedAt(0L)
                .build());
    softDeleteMetalake(deletedMetalakeId);

    scimTokenMetaMapper()
        .insert(
            ScimTokenMetaPO.builder()
                .withTokenId(3L)
                .withMetalakeId(missingMetalakeId)
                .withTokenName("missing-metalake-token")
                .withTokenHash("hash-missing")
                .withExpiresAt(0L)
                .withAuditInfo("{}")
                .withDeletedAt(0L)
                .withUpdatedAt(0L)
                .build());

    closeSession();

    ScimGarbageCollector garbageCollector = new ScimGarbageCollector(getConfig());
    try {
      garbageCollector.softDeleteExpiredTokens();
    } finally {
      garbageCollector.close();
    }

    reopenSession();
    assertEquals(
        "active-token", scimTokenMetaMapper().selectByTokenHash("hash-active").getTokenName());
    assertNull(scimTokenMetaMapper().selectByTokenHash("hash-deleted"));
    assertNull(scimTokenMetaMapper().selectByTokenHash("hash-missing"));
  }

  @ParameterizedTest
  @MethodSource("storageProvider")
  void testSoftDeleteMembersByUnavailableMetalake(String type) throws Exception {
    init(type);
    long deletedMetalakeId = 20L;

    insertMetalake();
    insertUserAndGroup();
    scimUserGroupRelMapper()
        .insertMemberships(METALAKE_NAME, GROUP_ID, List.of(USER_ID), "{}", 1L, 0L);

    insertMetalake(deletedMetalakeId, "deleted_metalake");
    insertUserAndGroup(deletedMetalakeId, USER_ID + 1, "bob", GROUP_ID + 1, "orphan-group");
    scimUserGroupRelMapper()
        .insertMemberships("deleted_metalake", GROUP_ID + 1, List.of(USER_ID + 1), "{}", 1L, 0L);
    softDeleteMetalake(deletedMetalakeId);

    closeSession();

    ScimGarbageCollector garbageCollector = new ScimGarbageCollector(getConfig());
    try {
      garbageCollector.softDeleteMembersByUnavailableMetalake();
    } finally {
      garbageCollector.close();
    }

    reopenSession();
    assertEquals(
        1, scimUserGroupRelMapper().selectMembersByGroupId(METALAKE_NAME, GROUP_ID).size());
    assertEquals(
        0,
        scimUserGroupRelMapper().selectMembersByGroupId("deleted_metalake", GROUP_ID + 1).size());
  }

  private ScimTokenMetaMapper scimTokenMetaMapper() {
    return sharedSession.getMapper(ScimTokenMetaMapper.class);
  }

  private ScimUserGroupRelMapper scimUserGroupRelMapper() {
    return sharedSession.getMapper(ScimUserGroupRelMapper.class);
  }

  private void insertUserAndGroup() {
    insertUserAndGroup(METALAKE_ID, USER_ID, USERNAME, GROUP_ID, GROUP_NAME);
  }

  private void insertUserAndGroup(
      long metalakeId, long userId, String username, long groupId, String groupName) {
    UserMetaMapper userMetaMapper = sharedSession.getMapper(UserMetaMapper.class);
    userMetaMapper.insertUserMeta(
        UserPO.builder()
            .withUserId(userId)
            .withUserName(username)
            .withMetalakeId(metalakeId)
            .withExternalId(externalIdForUser(userId))
            .withEnabled(true)
            .withAuditInfo("{}")
            .withCurrentVersion(1L)
            .withLastVersion(0L)
            .withDeletedAt(0L)
            .build());
    GroupMetaMapper groupMetaMapper = sharedSession.getMapper(GroupMetaMapper.class);
    groupMetaMapper.insertGroupMeta(
        GroupPO.builder()
            .withGroupId(groupId)
            .withGroupName(groupName)
            .withMetalakeId(metalakeId)
            .withExternalId(externalIdForGroup(groupId))
            .withAuditInfo("{}")
            .withCurrentVersion(1L)
            .withLastVersion(0L)
            .withDeletedAt(0L)
            .build());
  }

  private void updateLegacyDeletedAt(long deletedAt) throws Exception {
    try (SqlSession sqlSession =
            SqlSessionFactoryHelper.getInstance().getSqlSessionFactory().openSession(true);
        Connection connection = sqlSession.getConnection();
        PreparedStatement statement =
            connection.prepareStatement(
                "UPDATE scim_user_group_rel SET deleted_at = ? WHERE metalake_id = ? AND user_id ="
                    + " ? AND group_id = ? AND deleted_at > 0")) {
      statement.setLong(1, deletedAt);
      statement.setLong(2, METALAKE_ID);
      statement.setLong(3, USER_ID);
      statement.setLong(4, GROUP_ID);
      statement.executeUpdate();
    }
  }

  private static String externalIdForUser(long userId) {
    return "user-ext-" + userId;
  }

  private static String externalIdForGroup(long groupId) {
    return "group-ext-" + groupId;
  }

  private void insertMetalake() {
    insertMetalake(METALAKE_ID, METALAKE_NAME);
  }

  private void insertMetalake(long metalakeId, String metalakeName) {
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

  private void softDeleteMetalake(long metalakeId) {
    MetalakeMetaMapper metalakeMetaMapper = sharedSession.getMapper(MetalakeMetaMapper.class);
    metalakeMetaMapper.softDeleteMetalakeMetaByMetalakeId(metalakeId);
  }
}
