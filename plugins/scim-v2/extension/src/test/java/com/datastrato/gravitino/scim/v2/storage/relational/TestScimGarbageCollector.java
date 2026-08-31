/*
 * Copyright 2026 Datastrato Inc.
 */

package com.datastrato.gravitino.scim.v2.storage.relational;

import static org.apache.gravitino.Configs.STORE_DELETE_AFTER_TIME;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

import com.datastrato.gravitino.scim.v2.ScimConfigs;
import com.datastrato.gravitino.scim.v2.storage.mapper.AbstractScimMetaStorageTest;
import com.datastrato.gravitino.scim.v2.storage.mapper.ScimErrorHistoryMapper;
import com.datastrato.gravitino.scim.v2.storage.mapper.ScimGroupMetaMapper;
import com.datastrato.gravitino.scim.v2.storage.mapper.ScimTokenMetaMapper;
import com.datastrato.gravitino.scim.v2.storage.mapper.ScimUserGroupRelMapper;
import com.datastrato.gravitino.scim.v2.storage.mapper.ScimUserMetaMapper;
import com.datastrato.gravitino.scim.v2.storage.po.ScimErrorHistoryPO;
import com.datastrato.gravitino.scim.v2.storage.po.ScimGroupMetaPO;
import com.datastrato.gravitino.scim.v2.storage.po.ScimTokenMetaPO;
import com.datastrato.gravitino.scim.v2.storage.po.ScimUserMetaPO;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.util.List;
import java.util.concurrent.TimeUnit;
import org.apache.gravitino.storage.relational.session.SqlSessionFactoryHelper;
import org.apache.ibatis.session.SqlSession;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;

@Tag("gravitino-docker-test")
class TestScimGarbageCollector extends AbstractScimMetaStorageTest {
  private static final long USER_ID = 100L;
  private static final long GROUP_ID = 200L;
  private static final String USERNAME = "alice";
  private static final String GROUP_NAME = "engineers";

  @ParameterizedTest
  @MethodSource("storageProvider")
  void testCollectAndClean(String type) throws Exception {
    init(type);
    scimTokenMetaMapper()
        .insert(
            ScimTokenMetaPO.builder()
                .withTokenId(1L)
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
    insertUserAndGroup();
    scimUserGroupRelMapper().insertMemberships(GROUP_ID, List.of(USER_ID), 1L, 0L);
    scimUserGroupRelMapper().softDeleteMembersByGroupAndUserIds(GROUP_ID, List.of(USER_ID));
    updateLegacyDeletedAt(System.currentTimeMillis() - 700_000L);
    scimUserGroupRelMapper().insertMemberships(GROUP_ID, List.of(USER_ID), 1L, 0L);

    getConfig().set(STORE_DELETE_AFTER_TIME, 600_000L);
    closeSession();

    ScimGarbageCollector garbageCollector = new ScimGarbageCollector(getConfig());
    try {
      garbageCollector.collectAndClean();
    } finally {
      garbageCollector.close();
    }

    reopenSession();
    assertEquals(1, scimUserGroupRelMapper().selectMembersByGroupId(GROUP_ID).size());
  }

  @ParameterizedTest
  @MethodSource("storageProvider")
  void testSoftDeleteExpired(String type) throws Exception {
    init(type);
    scimTokenMetaMapper()
        .insert(
            ScimTokenMetaPO.builder()
                .withTokenId(1L)
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
  void testSoftDeleteOrphanMemberships(String type) throws Exception {
    init(type);
    insertUserAndGroup();
    scimUserGroupRelMapper().insertMemberships(GROUP_ID, List.of(USER_ID), 1L, 0L);

    insertUserAndGroup(USER_ID + 1, "bob", GROUP_ID + 1, "orphan-group");
    scimUserGroupRelMapper().insertMemberships(GROUP_ID + 1, List.of(USER_ID + 1), 1L, 0L);
    scimUserMetaMapper().softDeleteByExternalId(externalIdForUser(USER_ID + 1));

    closeSession();

    ScimGarbageCollector garbageCollector = new ScimGarbageCollector(getConfig());
    try {
      garbageCollector.softDeleteOrphanMemberships();
    } finally {
      garbageCollector.close();
    }

    reopenSession();
    assertEquals(1, scimUserGroupRelMapper().selectMembersByGroupId(GROUP_ID).size());
    assertEquals(0, scimUserGroupRelMapper().selectMembersByGroupId(GROUP_ID + 1).size());
  }

  @ParameterizedTest
  @MethodSource("storageProvider")
  void testCleanExpiredErrorHistory(String type) throws Exception {
    init(type);
    long now = System.currentTimeMillis();
    scimErrorHistoryMapper().insert(errorRow(1L, now - TimeUnit.DAYS.toMillis(31)));
    scimErrorHistoryMapper().insert(errorRow(2L, now));

    getConfig().set(ScimConfigs.ERROR_HISTORY_RETENTION_DAYS, 30);
    closeSession();

    ScimGarbageCollector garbageCollector = new ScimGarbageCollector(getConfig());
    try {
      garbageCollector.cleanExpiredErrorHistory();
    } finally {
      garbageCollector.close();
    }

    reopenSession();
    assertEquals(1L, scimErrorHistoryMapper().countAll());
  }

  private static ScimErrorHistoryPO errorRow(long errorId, long createdAt) {
    return ScimErrorHistoryPO.builder()
        .withErrorId(errorId)
        .withHttpMethod("POST")
        .withRequestPath("/scim/v2/Users")
        .withHttpStatus(409)
        .withErrorDetail("")
        .withPrincipal("")
        .withCreatedAt(createdAt)
        .build();
  }

  private ScimTokenMetaMapper scimTokenMetaMapper() {
    return sharedSession.getMapper(ScimTokenMetaMapper.class);
  }

  private ScimUserGroupRelMapper scimUserGroupRelMapper() {
    return sharedSession.getMapper(ScimUserGroupRelMapper.class);
  }

  private ScimUserMetaMapper scimUserMetaMapper() {
    return sharedSession.getMapper(ScimUserMetaMapper.class);
  }

  private ScimGroupMetaMapper scimGroupMetaMapper() {
    return sharedSession.getMapper(ScimGroupMetaMapper.class);
  }

  private ScimErrorHistoryMapper scimErrorHistoryMapper() {
    return sharedSession.getMapper(ScimErrorHistoryMapper.class);
  }

  private void insertUserAndGroup() {
    insertUserAndGroup(USER_ID, USERNAME, GROUP_ID, GROUP_NAME);
  }

  private void insertUserAndGroup(long userId, String username, long groupId, String groupName) {
    scimUserMetaMapper()
        .insert(
            ScimUserMetaPO.builder()
                .withUserId(userId)
                .withUserName(username)
                .withExternalId(externalIdForUser(userId))
                .withEnabled(true)
                .withCurrentVersion(1L)
                .withLastVersion(0L)
                .withDeletedAt(0L)
                .build());
    scimGroupMetaMapper()
        .insert(
            ScimGroupMetaPO.builder()
                .withGroupId(groupId)
                .withGroupName(groupName)
                .withExternalId(externalIdForGroup(groupId))
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
                "UPDATE scim_user_group_rel SET deleted_at = ? WHERE user_id = ? AND group_id ="
                    + " ? AND deleted_at > 0")) {
      statement.setLong(1, deletedAt);
      statement.setLong(2, USER_ID);
      statement.setLong(3, GROUP_ID);
      statement.executeUpdate();
    }
  }

  private static String externalIdForUser(long userId) {
    return "user-ext-" + userId;
  }

  private static String externalIdForGroup(long groupId) {
    return "group-ext-" + groupId;
  }
}
