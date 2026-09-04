/*
 * Copyright 2026 Datastrato Inc.
 */
package com.datastrato.gravitino.authorization.mapper;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import com.datastrato.gravitino.dto.authorization.ExtendedGroupDTO;
import com.datastrato.gravitino.dto.authorization.ExtendedUserDTO;
import com.datastrato.gravitino.dto.authorization.IdentitySource;
import java.time.Instant;
import java.util.List;
import org.apache.commons.lang3.reflect.FieldUtils;
import org.apache.gravitino.exceptions.NoSuchGroupException;
import org.apache.gravitino.exceptions.NoSuchUserException;
import org.apache.gravitino.json.JsonUtils;
import org.apache.gravitino.meta.AuditInfo;
import org.junit.jupiter.api.Test;

/** Unit tests for {@link IdpNameStatusPO} sentinel-row filtering in list mapping helpers. */
public class TestIdpNameStatusPO {

  @Test
  public void testMapExtendedUsersFiltersNullIdSentinelRows() throws Exception {
    IdpNameStatusPO.UserWithOrigin real = userRow(1L, "alice");
    IdpNameStatusPO.UserWithOrigin sentinel = userRow(null, null);

    ExtendedUserDTO[] users =
        IdpNameStatusPO.toExtendedUsersForMetalakeGroup(
            "metalake", "contractors", List.of(real, sentinel));

    assertEquals(1, users.length);
    assertEquals("alice", users[0].name());
  }

  @Test
  public void testMapExtendedUsersReturnsEmptyWhenOnlySentinelRows() throws Exception {
    ExtendedUserDTO[] users =
        IdpNameStatusPO.toExtendedUsersForMetalakeGroup(
            "metalake", "contractors", List.of(userRow(null, null)));

    assertEquals(0, users.length);
  }

  @Test
  public void testMapExtendedGroupsFiltersNullIdSentinelRows() throws Exception {
    IdpNameStatusPO.GroupWithOrigin real = groupRow(1L, "contractors");
    IdpNameStatusPO.GroupWithOrigin sentinel = groupRow(null, null);

    ExtendedGroupDTO[] groups =
        IdpNameStatusPO.toExtendedGroupsForMetalakeUser(
            "metalake", "alice", List.of(real, sentinel));

    assertEquals(1, groups.length);
    assertEquals("contractors", groups[0].name());
  }

  @Test
  public void testMapExtendedGroupsReturnsEmptyWhenOnlySentinelRows() throws Exception {
    ExtendedGroupDTO[] groups =
        IdpNameStatusPO.toExtendedGroupsForMetalakeUser(
            "metalake", "alice", List.of(groupRow(null, null)));

    assertEquals(0, groups.length);
  }

  @Test
  public void testMapExtendedUsersStillThrowsWhenRowsMissing() {
    assertThrows(
        NoSuchGroupException.class,
        () -> IdpNameStatusPO.toExtendedUsersForMetalakeGroup("metalake", "contractors", null));
  }

  @Test
  public void testMapExtendedGroupsStillThrowsWhenRowsMissing() {
    assertThrows(
        NoSuchUserException.class,
        () -> IdpNameStatusPO.toExtendedGroupsForMetalakeUser("metalake", "alice", null));
  }

  private static IdpNameStatusPO.UserWithOrigin userRow(Long userId, String userName)
      throws Exception {
    IdpNameStatusPO.UserWithOrigin row = new IdpNameStatusPO.UserWithOrigin();
    FieldUtils.writeField(row, "userId", userId, true);
    FieldUtils.writeField(row, "userName", userName, true);
    FieldUtils.writeField(row, "metalakeId", 1L, true);
    FieldUtils.writeField(row, "currentVersion", 1L, true);
    FieldUtils.writeField(row, "lastVersion", 1L, true);
    FieldUtils.writeField(row, "deletedAt", 0L, true);
    FieldUtils.writeField(
        row,
        "auditInfo",
        JsonUtils.anyFieldMapper()
            .writeValueAsString(
                AuditInfo.builder().withCreator("test").withCreateTime(Instant.EPOCH).build()),
        true);
    row.setOriginCode(IdentitySource.ORIGIN_CODE_LOCAL);
    return row;
  }

  private static IdpNameStatusPO.GroupWithOrigin groupRow(Long groupId, String groupName)
      throws Exception {
    IdpNameStatusPO.GroupWithOrigin row = new IdpNameStatusPO.GroupWithOrigin();
    FieldUtils.writeField(row, "groupId", groupId, true);
    FieldUtils.writeField(row, "groupName", groupName, true);
    FieldUtils.writeField(row, "metalakeId", 1L, true);
    FieldUtils.writeField(row, "currentVersion", 1L, true);
    FieldUtils.writeField(row, "lastVersion", 1L, true);
    FieldUtils.writeField(row, "deletedAt", 0L, true);
    FieldUtils.writeField(
        row,
        "auditInfo",
        JsonUtils.anyFieldMapper()
            .writeValueAsString(
                AuditInfo.builder().withCreator("test").withCreateTime(Instant.EPOCH).build()),
        true);
    row.setOriginCode(IdentitySource.ORIGIN_CODE_LOCAL);
    row.setUserCount(0);
    return row;
  }
}
