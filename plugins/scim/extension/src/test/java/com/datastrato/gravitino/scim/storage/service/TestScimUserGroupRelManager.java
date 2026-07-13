/*
 * Copyright 2026 Datastrato Pvt Ltd.
 * This software is licensed under the Apache License version 2.
 */

package com.datastrato.gravitino.scim.storage.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

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
    insertMetalakeForManager();
    insertUser(USER_ID, USERNAME);
    insertUser(USER_ID + 1, "bob");
    insertGroup(GROUP_ID, GROUP_NAME);

    runManagerCall(
        () ->
            manager.addUsersToGroup(
                METALAKE_NAME,
                externalIdForGroup(GROUP_ID),
                List.of(externalIdForUser(USER_ID), externalIdForUser(USER_ID + 1))));

    assertEquals(List.of(GROUP_NAME), manager.listGroupNamesForUser(METALAKE_NAME, USERNAME));
    assertEquals(
        List.of(USERNAME, "bob"),
        manager.listUsernamesForGroup(METALAKE_NAME, externalIdForGroup(GROUP_ID)));
  }

  @ParameterizedTest
  @MethodSource("storageProvider")
  void testRemoveUsers(String type) throws Exception {
    initManagerTest(type);
    insertMetalakeForManager();
    insertUser(USER_ID, USERNAME);
    insertUser(USER_ID + 1, "bob");
    insertGroup(GROUP_ID, GROUP_NAME);
    runManagerCall(
        () ->
            manager.addUsersToGroup(
                METALAKE_NAME,
                externalIdForGroup(GROUP_ID),
                List.of(externalIdForUser(USER_ID), externalIdForUser(USER_ID + 1))));

    manager.removeUsersFromGroup(
        METALAKE_NAME, externalIdForGroup(GROUP_ID), List.of(externalIdForUser(USER_ID)));

    assertTrue(manager.listGroupNamesForUser(METALAKE_NAME, USERNAME).isEmpty());
    assertEquals(
        List.of("bob"), manager.listUsernamesForGroup(METALAKE_NAME, externalIdForGroup(GROUP_ID)));
  }

  @ParameterizedTest
  @MethodSource("storageProvider")
  void testReplaceUsers(String type) throws Exception {
    initManagerTest(type);
    insertMetalakeForManager();
    insertUser(USER_ID, USERNAME);
    insertUser(USER_ID + 1, "bob");
    insertGroup(GROUP_ID, GROUP_NAME);
    runManagerCall(
        () ->
            manager.addUsersToGroup(
                METALAKE_NAME, externalIdForGroup(GROUP_ID), List.of(externalIdForUser(USER_ID))));

    runManagerCall(
        () ->
            manager.replaceUsersInGroup(
                METALAKE_NAME,
                externalIdForGroup(GROUP_ID),
                List.of(externalIdForUser(USER_ID + 1))));

    assertEquals(
        List.of("bob"), manager.listUsernamesForGroup(METALAKE_NAME, externalIdForGroup(GROUP_ID)));
    assertTrue(manager.listGroupNamesForUser(METALAKE_NAME, USERNAME).isEmpty());
  }

  @ParameterizedTest
  @MethodSource("storageProvider")
  void testNoOpMissingGroup(String type) throws Exception {
    initManagerTest(type);
    insertMetalakeForManager();
    insertUser(USER_ID, USERNAME);

    runManagerCall(
        () ->
            manager.addUsersToGroup(
                METALAKE_NAME, externalIdForGroup(GROUP_ID), List.of(externalIdForUser(USER_ID))));

    assertTrue(manager.listGroupNamesForUser(METALAKE_NAME, USERNAME).isEmpty());
  }
}
