/*
 * Copyright 2026 Datastrato Pvt Ltd.
 * This software is licensed under the Apache License version 2.
 */

package com.datastrato.gravitino.scim.storage.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;

@Tag("gravitino-docker-test")
class TestScimUserGroupRelMetaService extends AbstractScimUserGroupRelMetaServiceTest {

  @ParameterizedTest
  @MethodSource("storageProvider")
  void testListMembership(String type) throws IOException {
    init(type);
    insertMetalake();
    insertUser(USER_ID, USERNAME);
    insertUser(USER_ID + 1, "bob");
    insertGroup(GROUP_ID, GROUP_NAME);
    insertMembership(USER_ID, GROUP_ID);
    insertMembership(USER_ID + 1, GROUP_ID);

    ScimUserGroupRelMetaService metaService = ScimUserGroupRelMetaService.getInstance();

    assertEquals(
        List.of(GROUP_NAME), metaService.listGroupNamesByUsername(USERNAME, METALAKE_NAME));
    assertEquals(Set.of(USER_ID, USER_ID + 1), memberUserIdsForGroup(GROUP_ID));
  }

  @ParameterizedTest
  @MethodSource("storageProvider")
  void testInsertAndSoftDelete(String type) throws IOException {
    init(type);
    insertMetalake();
    insertUser(USER_ID, USERNAME);
    insertGroup(GROUP_ID, GROUP_NAME);
    ScimUserGroupRelMetaService metaService = ScimUserGroupRelMetaService.getInstance();

    runServiceCall(
        () ->
            metaService.insertMemberships(METALAKE_NAME, GROUP_ID, List.of(USER_ID), "{}", 1L, 0L));
    assertEquals(Set.of(USER_ID), memberUserIdsForGroup(GROUP_ID));

    runServiceCall(
        () ->
            metaService.softDeleteMembersByGroupAndUserIds(
                METALAKE_NAME, GROUP_ID, List.of(USER_ID)));
    assertTrue(memberUserIdsForGroup(GROUP_ID).isEmpty());
  }

  @ParameterizedTest
  @MethodSource("storageProvider")
  void testSoftDeleteByUser(String type) throws IOException {
    init(type);
    insertMetalake();
    insertUser(USER_ID, USERNAME);
    insertGroup(GROUP_ID, GROUP_NAME);
    insertMembership(USER_ID, GROUP_ID);
    ScimUserGroupRelMetaService metaService = ScimUserGroupRelMetaService.getInstance();

    runServiceCall(() -> metaService.softDeleteMembersByUserId(METALAKE_NAME, USER_ID));
    assertTrue(metaService.listGroupNamesByUsername(USERNAME, METALAKE_NAME).isEmpty());
  }

  @ParameterizedTest
  @MethodSource("storageProvider")
  void testReplaceMembers(String type) throws IOException {
    init(type);
    insertMetalake();
    insertUser(USER_ID, USERNAME);
    insertUser(USER_ID + 1, "bob");
    insertGroup(GROUP_ID, GROUP_NAME);
    insertMembership(USER_ID, GROUP_ID);
    ScimUserGroupRelMetaService metaService = ScimUserGroupRelMetaService.getInstance();

    runServiceCall(
        () ->
            metaService.replaceMembersByGroupId(
                METALAKE_NAME, GROUP_ID, List.of(USER_ID + 1), "{}", 1L, 0L));

    assertEquals(Set.of(USER_ID + 1), memberUserIdsForGroup(GROUP_ID));
  }

  @ParameterizedTest
  @MethodSource("storageProvider")
  void testDeleteByLegacyTimeline(String type) throws IOException {
    init(type);
    insertMetalake();
    insertUser(USER_ID, USERNAME);
    insertGroup(GROUP_ID, GROUP_NAME);
    insertMembership(USER_ID, GROUP_ID);
    scimUserGroupRelMapper.softDeleteMembersByGroupAndUserIds(
        METALAKE_NAME, GROUP_ID, List.of(USER_ID));
    insertMembership(USER_ID, GROUP_ID);
    ScimUserGroupRelMetaService metaService = ScimUserGroupRelMetaService.getInstance();

    closeSession();
    assertEquals(1, metaService.deleteScimUserGroupRelMetasByLegacyTimeline(Long.MAX_VALUE, 1));
    refreshSession();
    assertEquals(Set.of(USER_ID), memberUserIdsForGroup(GROUP_ID));
  }
}
