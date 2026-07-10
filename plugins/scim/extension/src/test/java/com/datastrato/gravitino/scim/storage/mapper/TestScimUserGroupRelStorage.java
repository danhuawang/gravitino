/*
 * Copyright 2026 Datastrato Pvt Ltd.
 * This software is licensed under the Apache License version 2.
 */

package com.datastrato.gravitino.scim.storage.mapper;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.datastrato.gravitino.scim.storage.po.ScimGroupMemberPO;
import java.io.IOException;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;
import java.util.stream.Collectors;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;

@Tag("gravitino-docker-test")
class TestScimUserGroupRelStorage extends AbstractScimUserGroupRelStorageTest {

  @ParameterizedTest
  @MethodSource("storageProvider")
  void testInsertAndSelect(String type) throws IOException {
    init(type);
    insertMetalake();
    insertUser(USER_ID, USERNAME);
    insertGroup(GROUP_ID, GROUP_NAME);
    insertMembership(USER_ID, GROUP_ID);

    assertEquals(
        List.of(GROUP_NAME),
        scimUserGroupRelMapper.selectGroupNamesByUsername(USERNAME, METALAKE_NAME));
    assertEquals(Set.of(externalIdForUser(USER_ID)), memberExternalIdsForGroup(GROUP_ID));
  }

  @ParameterizedTest
  @MethodSource("storageProvider")
  void testSoftDeleteGroupUsers(String type) throws IOException {
    init(type);
    insertMetalake();
    insertUser(USER_ID, USERNAME);
    insertUser(USER_ID + 1, "bob");
    insertGroup(GROUP_ID, GROUP_NAME);
    insertMembership(USER_ID, GROUP_ID);
    insertMembership(USER_ID + 1, GROUP_ID);

    scimUserGroupRelMapper.softDeleteMembersByGroupAndUserExternalIds(
        METALAKE_NAME, externalIdForGroup(GROUP_ID), List.of(externalIdForUser(USER_ID)));
    assertEquals(Set.of(externalIdForUser(USER_ID + 1)), memberExternalIdsForGroup(GROUP_ID));
    assertTrue(
        scimUserGroupRelMapper.selectGroupNamesByUsername(USERNAME, METALAKE_NAME).isEmpty());
  }

  @ParameterizedTest
  @MethodSource("storageProvider")
  void testInsertByExtIds(String type) throws IOException {
    init(type);
    insertMetalake();
    insertUser(USER_ID, USERNAME);
    insertUser(USER_ID + 1, "bob");
    insertGroup(GROUP_ID, GROUP_NAME);

    assertEquals(
        2,
        scimUserGroupRelMapper.insertMemberships(
            METALAKE_NAME,
            externalIdForGroup(GROUP_ID),
            List.of(externalIdForUser(USER_ID), externalIdForUser(USER_ID + 1)),
            "{}",
            1L,
            0L));
    assertEquals(
        Set.of(externalIdForUser(USER_ID), externalIdForUser(USER_ID + 1)),
        memberExternalIdsForGroup(GROUP_ID));
  }

  @ParameterizedTest
  @MethodSource("storageProvider")
  void testInsertByExtIdsUpsert(String type) throws IOException {
    init(type);
    insertMetalake();
    insertUser(USER_ID, USERNAME);
    insertUser(USER_ID + 1, "bob");
    insertGroup(GROUP_ID, GROUP_NAME);
    insertMembership(USER_ID, GROUP_ID);

    scimUserGroupRelMapper.insertMemberships(
        METALAKE_NAME,
        externalIdForGroup(GROUP_ID),
        List.of(externalIdForUser(USER_ID), "missing-ext", externalIdForUser(USER_ID + 1)),
        "{}",
        1L,
        0L);

    assertEquals(
        Set.of(externalIdForUser(USER_ID), externalIdForUser(USER_ID + 1)),
        memberExternalIdsForGroup(GROUP_ID));

    scimUserGroupRelMapper.insertMemberships(
        METALAKE_NAME,
        externalIdForGroup(GROUP_ID),
        List.of(externalIdForUser(USER_ID), externalIdForUser(USER_ID + 1)),
        "{}",
        1L,
        0L);
    assertEquals(
        Set.of(externalIdForUser(USER_ID), externalIdForUser(USER_ID + 1)),
        memberExternalIdsForGroup(GROUP_ID));
  }

  @ParameterizedTest
  @MethodSource("storageProvider")
  void testSelectMembers(String type) throws IOException {
    init(type);
    insertMetalake();
    insertUser(USER_ID, USERNAME);
    insertUser(USER_ID + 1, "bob");
    insertGroup(GROUP_ID, GROUP_NAME);
    insertMembership(USER_ID, GROUP_ID);
    insertMembership(USER_ID + 1, GROUP_ID);

    Map<String, ScimGroupMemberPO> membersByExternalId =
        scimUserGroupRelMapper
            .selectMembersByGroupExternalId(METALAKE_NAME, externalIdForGroup(GROUP_ID))
            .stream()
            .collect(Collectors.toMap(ScimGroupMemberPO::getExternalId, Function.identity()));

    assertEquals(
        Set.of(externalIdForUser(USER_ID), externalIdForUser(USER_ID + 1)),
        membersByExternalId.keySet());
    assertEquals(USERNAME, membersByExternalId.get(externalIdForUser(USER_ID)).getUserName());
    assertEquals("bob", membersByExternalId.get(externalIdForUser(USER_ID + 1)).getUserName());
  }

  @ParameterizedTest
  @MethodSource("storageProvider")
  void testSelectGroupNamesByUsername(String type) throws IOException {
    init(type);
    insertMetalake();
    insertUser(USER_ID, USERNAME);
    insertGroup(GROUP_ID, GROUP_NAME);
    insertMembership(USER_ID, GROUP_ID);

    assertEquals(
        List.of(GROUP_NAME),
        scimUserGroupRelMapper.selectGroupNamesByUsername(USERNAME, METALAKE_NAME));
  }

  @ParameterizedTest
  @MethodSource("storageProvider")
  void testSoftDeleteUnavailableMetalake(String type) throws IOException {
    init(type);
    long deletedMetalakeId = 20L;

    insertMetalake();
    insertUser(USER_ID, USERNAME);
    insertGroup(GROUP_ID, GROUP_NAME);
    insertMembership(USER_ID, GROUP_ID);

    insertMetalake(deletedMetalakeId, "deleted_metalake");
    insertUser(deletedMetalakeId, USER_ID + 1, "bob");
    insertGroup(deletedMetalakeId, GROUP_ID + 1, "orphan-group");
    insertMembership("deleted_metalake", USER_ID + 1, GROUP_ID + 1);
    softDeleteMetalake(deletedMetalakeId);

    assertEquals(1, scimUserGroupRelMapper.softDeleteMembersByUnavailableMetalake());
    assertEquals(Set.of(externalIdForUser(USER_ID)), memberExternalIdsForGroup(GROUP_ID));
    assertTrue(memberExternalIdsForGroup("deleted_metalake", GROUP_ID + 1).isEmpty());
  }

  @ParameterizedTest
  @MethodSource("storageProvider")
  void testSoftDeleteTwice(String type) throws IOException {
    init(type);
    insertMetalake();
    insertUser(USER_ID, USERNAME);
    insertGroup(GROUP_ID, GROUP_NAME);
    insertMembership(USER_ID, GROUP_ID);

    scimUserGroupRelMapper.softDeleteMembersByGroupAndUserExternalIds(
        METALAKE_NAME, externalIdForGroup(GROUP_ID), List.of(externalIdForUser(USER_ID)));
    insertMembership(USER_ID, GROUP_ID);
    scimUserGroupRelMapper.softDeleteMembersByGroupAndUserExternalIds(
        METALAKE_NAME, externalIdForGroup(GROUP_ID), List.of(externalIdForUser(USER_ID)));

    assertTrue(
        scimUserGroupRelMapper.selectGroupNamesByUsername(USERNAME, METALAKE_NAME).isEmpty());
  }

  @ParameterizedTest
  @MethodSource("storageProvider")
  void testSoftDeleteByGroup(String type) throws IOException {
    init(type);
    insertMetalake();
    insertUser(USER_ID, USERNAME);
    insertGroup(GROUP_ID, GROUP_NAME);
    insertMembership(USER_ID, GROUP_ID);

    scimUserGroupRelMapper.softDeleteMembersByGroupExternalId(
        METALAKE_NAME, externalIdForGroup(GROUP_ID));
    assertTrue(memberExternalIdsForGroup(GROUP_ID).isEmpty());
  }

  @ParameterizedTest
  @MethodSource("storageProvider")
  void testSoftDeleteByUser(String type) throws IOException {
    init(type);
    insertMetalake();
    insertUser(USER_ID, USERNAME);
    insertGroup(GROUP_ID, GROUP_NAME);
    insertMembership(USER_ID, GROUP_ID);

    scimUserGroupRelMapper.softDeleteMembersByUserExternalId(
        METALAKE_NAME, externalIdForUser(USER_ID));
    assertTrue(
        scimUserGroupRelMapper.selectGroupNamesByUsername(USERNAME, METALAKE_NAME).isEmpty());
  }

  @ParameterizedTest
  @MethodSource("storageProvider")
  void testDeleteByLegacyTimeline(String type) throws IOException {
    init(type);
    insertMetalake();
    insertUser(USER_ID, USERNAME);
    insertGroup(GROUP_ID, GROUP_NAME);

    insertMembership(USER_ID, GROUP_ID);
    scimUserGroupRelMapper.softDeleteMembersByGroupAndUserExternalIds(
        METALAKE_NAME, externalIdForGroup(GROUP_ID), List.of(externalIdForUser(USER_ID)));
    assertTrue(memberExternalIdsForGroup(GROUP_ID).isEmpty());

    assertEquals(1, scimUserGroupRelMapper.deleteByLegacyTimeline(Long.MAX_VALUE, 1));
    assertEquals(0, scimUserGroupRelMapper.deleteByLegacyTimeline(Long.MAX_VALUE, 1));

    insertMembership(USER_ID, GROUP_ID);
    assertEquals(Set.of(externalIdForUser(USER_ID)), memberExternalIdsForGroup(GROUP_ID));
  }
}
