/*
 * Copyright 2026 Datastrato Inc.
 */

package com.datastrato.gravitino.scim.storage.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.datastrato.gravitino.scim.storage.po.ScimGroupMemberPO;
import java.util.List;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;

@Tag("gravitino-docker-test")
class TestScimUserGroupRelManager extends AbstractScimUserGroupRelManagerTest {

  @ParameterizedTest
  @MethodSource("storageProvider")
  void testAddAndList(String type) throws Exception {
    initManagerTest(type);
    insertUser(USER_ID, USERNAME);
    insertUser(USER_ID + 1, "bob");
    insertGroup(GROUP_ID, GROUP_NAME);

    runManagerCall(() -> manager.addUsersToGroup(GROUP_ID, List.of(USER_ID, USER_ID + 1)));

    assertEquals(List.of(GROUP_NAME), manager.listGroupNamesForUser(USERNAME));
    assertEquals(
        List.of("bob", USERNAME),
        manager.listMembersForGroup(GROUP_ID).stream()
            .map(ScimGroupMemberPO::getUserName)
            .collect(java.util.stream.Collectors.toList()));
  }

  @ParameterizedTest
  @MethodSource("storageProvider")
  void testRemoveUsers(String type) throws Exception {
    initManagerTest(type);
    insertUser(USER_ID, USERNAME);
    insertUser(USER_ID + 1, "bob");
    insertGroup(GROUP_ID, GROUP_NAME);
    runManagerCall(() -> manager.addUsersToGroup(GROUP_ID, List.of(USER_ID, USER_ID + 1)));

    manager.removeUsersFromGroup(GROUP_ID, List.of(USER_ID));

    assertTrue(manager.listGroupNamesForUser(USERNAME).isEmpty());
    assertEquals(
        List.of("bob"),
        manager.listMembersForGroup(GROUP_ID).stream()
            .map(ScimGroupMemberPO::getUserName)
            .collect(java.util.stream.Collectors.toList()));
  }

  @ParameterizedTest
  @MethodSource("storageProvider")
  void testReplaceUsers(String type) throws Exception {
    initManagerTest(type);
    insertUser(USER_ID, USERNAME);
    insertUser(USER_ID + 1, "bob");
    insertGroup(GROUP_ID, GROUP_NAME);
    runManagerCall(() -> manager.addUsersToGroup(GROUP_ID, List.of(USER_ID)));

    runManagerCall(() -> manager.replaceUsersInGroup(GROUP_ID, List.of(USER_ID + 1)));

    assertEquals(
        List.of("bob"),
        manager.listMembersForGroup(GROUP_ID).stream()
            .map(ScimGroupMemberPO::getUserName)
            .collect(java.util.stream.Collectors.toList()));
    assertTrue(manager.listGroupNamesForUser(USERNAME).isEmpty());
  }

  @ParameterizedTest
  @MethodSource("storageProvider")
  void testNoOpMissingGroup(String type) throws Exception {
    initManagerTest(type);
    insertUser(USER_ID, USERNAME);

    runManagerCall(() -> manager.addUsersToGroup(GROUP_ID, List.of(USER_ID)));

    assertTrue(manager.listGroupNamesForUser(USERNAME).isEmpty());
  }
}
