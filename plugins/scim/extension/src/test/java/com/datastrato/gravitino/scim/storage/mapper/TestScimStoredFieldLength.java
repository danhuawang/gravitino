/*
 * Copyright 2026 Datastrato Inc.
 */

package com.datastrato.gravitino.scim.storage.mapper;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

import com.datastrato.gravitino.scim.ScimFieldLimits;
import com.datastrato.gravitino.scim.model.ScimGroupMeta;
import com.datastrato.gravitino.scim.model.ScimUserMeta;
import com.datastrato.gravitino.scim.storage.po.ScimGroupMetaPO;
import com.datastrato.gravitino.scim.storage.po.ScimTokenMetaPO;
import com.datastrato.gravitino.scim.storage.po.ScimUserMetaPO;
import com.datastrato.gravitino.scim.storage.relational.utils.ScimExceptionUtils;
import com.datastrato.gravitino.scim.storage.relational.utils.ScimPOConverters;
import com.datastrato.gravitino.scim.storage.service.ScimGroupMetaService;
import com.datastrato.gravitino.scim.storage.service.ScimTokenMetaService;
import com.datastrato.gravitino.scim.storage.service.ScimUserMetaService;
import java.io.IOException;
import java.time.Instant;
import org.apache.gravitino.exceptions.NotFoundException;
import org.apache.gravitino.meta.AuditInfo;
import org.apache.gravitino.storage.relational.utils.SessionUtils;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/** H2 checks that oversized SCIM fields are rejected before a row is stored. */
class TestScimStoredFieldLength extends AbstractScimMetaStorageTest {
  private static final String AUDIT_INFO =
      ScimPOConverters.serializeAuditInfo(
          AuditInfo.builder().withCreator("test").withCreateTime(Instant.EPOCH).build());

  private final ScimUserMetaService userService = ScimUserMetaService.getInstance();
  private final ScimGroupMetaService groupService = ScimGroupMetaService.getInstance();
  private final ScimTokenMetaService tokenService = ScimTokenMetaService.getInstance();

  @BeforeEach
  void setUp() throws IOException {
    init("h2");
  }

  @Test
  void testExactLimitValuesAreStored() throws IOException {
    String userName = "u".repeat(ScimFieldLimits.MAX_NAME_LENGTH);
    String userExternalId = "e".repeat(ScimFieldLimits.MAX_EXTERNAL_ID_LENGTH);
    userService.insertScimUser(user(1L, userName, userExternalId));

    ScimUserMeta storedUser = userService.getScimUserByUserName(userName);
    assertEquals(userName, storedUser.getUserName());
    assertEquals(userExternalId, storedUser.getExternalId());

    String groupName = "g".repeat(ScimFieldLimits.MAX_NAME_LENGTH);
    String groupExternalId = "f".repeat(ScimFieldLimits.MAX_EXTERNAL_ID_LENGTH);
    String groupComment = "c".repeat(ScimFieldLimits.MAX_GROUP_COMMENT_LENGTH);
    groupService.insertScimGroup(group(2L, groupName, groupExternalId, groupComment));

    ScimGroupMeta storedGroup = groupService.getScimGroupByGroupName(groupName);
    assertEquals(groupName, storedGroup.getGroupName());
    assertEquals(groupExternalId, storedGroup.getExternalId());
    assertEquals(groupComment, storedGroup.getGroupComment());
  }

  @Test
  void testTooLongFieldsAreRejectedBeforeInsert() {
    String longUserName = "u".repeat(ScimFieldLimits.MAX_NAME_LENGTH + 1);
    IllegalArgumentException userName =
        assertThrows(
            IllegalArgumentException.class,
            () -> userService.insertScimUser(user(11L, longUserName, "ext-user")));
    assertEquals(
        "The userName of the SCIM user must not exceed 128 characters", userName.getMessage());
    assertNull(userService.getScimUserByUserName(longUserName));

    String longUserExternalId = "e".repeat(ScimFieldLimits.MAX_EXTERNAL_ID_LENGTH + 1);
    IllegalArgumentException userExternalId =
        assertThrows(
            IllegalArgumentException.class,
            () -> userService.insertScimUser(user(12L, "alice", longUserExternalId)));
    assertEquals(
        "The externalId of the SCIM user must not exceed 256 characters",
        userExternalId.getMessage());
    assertNull(userService.getScimUserByUserName("alice"));

    String longGroupName = "g".repeat(ScimFieldLimits.MAX_NAME_LENGTH + 1);
    IllegalArgumentException groupName =
        assertThrows(
            IllegalArgumentException.class,
            () -> groupService.insertScimGroup(group(21L, longGroupName, "ext-group", "")));
    assertEquals(
        "The displayName of the SCIM group must not exceed 128 characters", groupName.getMessage());
    assertNull(groupService.getScimGroupByGroupName(longGroupName));

    String longComment = "c".repeat(ScimFieldLimits.MAX_GROUP_COMMENT_LENGTH + 1);
    IllegalArgumentException comment =
        assertThrows(
            IllegalArgumentException.class,
            () ->
                groupService.insertScimGroup(group(22L, "engineers", "ext-comment", longComment)));
    assertEquals(
        "The group comment of the SCIM group must not exceed 1024 characters",
        comment.getMessage());
    assertNull(groupService.getScimGroupByGroupName("engineers"));

    String longGroupExternalId = "f".repeat(ScimFieldLimits.MAX_EXTERNAL_ID_LENGTH + 1);
    IllegalArgumentException groupExternalId =
        assertThrows(
            IllegalArgumentException.class,
            () -> groupService.insertScimGroup(group(23L, "platform", longGroupExternalId, "ok")));
    assertEquals(
        "The externalId of the SCIM group must not exceed 256 characters",
        groupExternalId.getMessage());
    assertNull(groupService.getScimGroupByGroupName("platform"));
  }

  @Test
  void testUpdateExternalIdRejectsTooLongValue() throws IOException {
    userService.insertScimUser(user(31L, "bob", "ext-bob"));
    String tooLong = "e".repeat(ScimFieldLimits.MAX_EXTERNAL_ID_LENGTH + 1);

    IllegalArgumentException exception =
        assertThrows(
            IllegalArgumentException.class,
            () -> userService.updateScimUserExternalId(31L, tooLong));
    assertEquals(
        "The externalId of the SCIM user must not exceed 256 characters", exception.getMessage());
    assertEquals("ext-bob", userService.getScimUserByUserName("bob").getExternalId());
  }

  @Test
  void testDatabaseValueTooLongMapsWithoutSqlCause() {
    String userName = "s".repeat(ScimFieldLimits.MAX_NAME_LENGTH + 1);
    ScimUserMetaPO tooLong = user(41L, userName, "ext-sql");
    RuntimeException sqlFailure =
        assertThrows(
            RuntimeException.class,
            () ->
                SessionUtils.doWithCommit(
                    ScimUserMetaMapper.class, mapper -> mapper.insert(tooLong)));

    IllegalArgumentException mapped =
        assertThrows(
            IllegalArgumentException.class,
            () -> ScimExceptionUtils.checkSQLException(sqlFailure, "user", userName));
    assertEquals(
        "The SCIM user has a value that exceeds the maximum length of its column.",
        mapped.getMessage());
    assertNull(mapped.getCause());
    assertNull(userService.getScimUserByUserName(userName));
  }

  @Test
  void testExactLimitTokenNameIsStored() throws IOException {
    String tokenName = "t".repeat(ScimFieldLimits.MAX_TOKEN_NAME_LENGTH);
    tokenService.insertScimToken(token(51L, tokenName));
    assertEquals(tokenName, tokenService.getScimTokenMetaByName(tokenName).getTokenName());
  }

  @Test
  void testTooLongTokenNameIsRejectedBeforeInsert() {
    String tokenName = "t".repeat(ScimFieldLimits.MAX_TOKEN_NAME_LENGTH + 1);
    IllegalArgumentException exception =
        assertThrows(
            IllegalArgumentException.class,
            () -> tokenService.insertScimToken(token(52L, tokenName)));
    assertEquals(
        "The tokenName of the SCIM token must not exceed 256 characters", exception.getMessage());
    assertThrows(NotFoundException.class, () -> tokenService.getScimTokenMetaByName(tokenName));
  }

  @Test
  void testDatabaseTokenNameTooLongMapsWithoutSqlCause() {
    String tokenName = "s".repeat(ScimFieldLimits.MAX_TOKEN_NAME_LENGTH + 1);
    ScimTokenMetaPO tooLong = token(53L, tokenName);
    RuntimeException sqlFailure =
        assertThrows(
            RuntimeException.class,
            () ->
                SessionUtils.doWithCommit(
                    ScimTokenMetaMapper.class, mapper -> mapper.insert(tooLong)));

    IllegalArgumentException mapped =
        assertThrows(
            IllegalArgumentException.class,
            () -> ScimExceptionUtils.checkSQLException(sqlFailure, "token", tokenName));
    assertEquals(
        "The SCIM token has a value that exceeds the maximum length of its column.",
        mapped.getMessage());
    assertNull(mapped.getCause());
    assertThrows(NotFoundException.class, () -> tokenService.getScimTokenMetaByName(tokenName));
  }

  private static ScimTokenMetaPO token(long tokenId, String tokenName) {
    return ScimTokenMetaPO.builder()
        .withTokenId(tokenId)
        .withTokenName(tokenName)
        .withTokenHash(String.format("%064x", tokenId))
        .withExpiresAt(0L)
        .withAuditInfo(AUDIT_INFO)
        .withDeletedAt(0L)
        .withUpdatedAt(0L)
        .withLastUsedAt(0L)
        .build();
  }

  private static ScimUserMetaPO user(long userId, String userName, String externalId) {
    return ScimUserMetaPO.builder()
        .withUserId(userId)
        .withUserName(userName)
        .withExternalId(externalId)
        .withEnabled(true)
        .withAuditInfo(AUDIT_INFO)
        .withCurrentVersion(1L)
        .withLastVersion(0L)
        .withDeletedAt(0L)
        .build();
  }

  private static ScimGroupMetaPO group(
      long groupId, String groupName, String externalId, String groupComment) {
    return ScimGroupMetaPO.builder()
        .withGroupId(groupId)
        .withGroupName(groupName)
        .withGroupComment(groupComment)
        .withExternalId(externalId)
        .withAuditInfo(AUDIT_INFO)
        .withCurrentVersion(1L)
        .withLastVersion(0L)
        .withDeletedAt(0L)
        .build();
  }
}
