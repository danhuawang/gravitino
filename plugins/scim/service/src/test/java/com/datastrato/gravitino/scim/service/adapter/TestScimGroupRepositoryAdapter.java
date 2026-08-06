/*
 * Copyright 2026 Datastrato Pvt Ltd.
 * This software is licensed under the Apache License version 2.
 */

package com.datastrato.gravitino.scim.service.adapter;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.datastrato.gravitino.scim.ScimUserGroupRelManager;
import com.datastrato.gravitino.scim.service.ScimConfig;
import com.datastrato.gravitino.scim.service.ScimServiceTestEntities;
import com.datastrato.gravitino.scim.service.web.ScimMetalakeContext;
import com.datastrato.gravitino.scim.storage.po.ScimGroupMemberPO;
import java.util.List;
import java.util.Map;
import org.apache.directory.scim.spec.exception.ResourceException;
import org.apache.directory.scim.spec.filter.Filter;
import org.apache.directory.scim.spec.filter.PageRequest;
import org.apache.directory.scim.spec.patch.PatchOperation;
import org.apache.directory.scim.spec.patch.PatchOperationPath;
import org.apache.directory.scim.spec.resources.GroupMembership;
import org.apache.directory.scim.spec.resources.ScimGroup;
import org.apache.gravitino.Config;
import org.apache.gravitino.authorization.AccessControlDispatcher;
import org.apache.gravitino.authorization.Group;
import org.apache.gravitino.exceptions.GroupAlreadyExistsException;
import org.apache.gravitino.exceptions.NoSuchGroupException;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class TestScimGroupRepositoryAdapter {

  private static final String METALAKE = "test_metalake";
  private static final long GROUP_ID = 2L;
  private static final long USER_ID = 3L;

  private AccessControlDispatcher dispatcher;
  private ScimUserGroupRelManager membershipManager;
  private ScimGroupRepositoryAdapter adapter;
  private ScimConfig scimConfig;

  @BeforeEach
  void setUp() {
    scimConfig = new ScimConfig(Map.of(), new Config() {});
    dispatcher = mock(AccessControlDispatcher.class);
    membershipManager = mock(ScimUserGroupRelManager.class);
    adapter = new ScimGroupRepositoryAdapter(dispatcher, membershipManager, scimConfig);
    ScimMetalakeContext.setMetalake(METALAKE);
  }

  @AfterEach
  void tearDown() {
    ScimMetalakeContext.clear();
  }

  @Test
  void testCreateNoDisplayName() {
    ResourceException exception =
        assertThrows(ResourceException.class, () -> adapter.create(new ScimGroup()));
    assertEquals(400, exception.getStatus());
  }

  @Test
  void testCreateWithoutExternalId() throws Exception {
    Group created = ScimServiceTestEntities.group(1L, "engineers", null);
    when(dispatcher.addGroup(METALAKE, "engineers", null)).thenReturn(created);
    when(membershipManager.listMembersForGroup(METALAKE, 1L)).thenReturn(List.of());

    ScimGroup result = adapter.create(new ScimGroup().setDisplayName("engineers"));
    assertEquals("1", result.getId());
    assertNull(result.getExternalId());
    verify(dispatcher).addGroup(METALAKE, "engineers", null);
  }

  @Test
  void testCreateConflict409() throws Exception {
    when(dispatcher.addGroup(METALAKE, "engineers", "ext-g1"))
        .thenThrow(new GroupAlreadyExistsException("engineers"));

    ResourceException exception =
        assertThrows(
            ResourceException.class,
            () ->
                adapter.create(
                    new ScimGroup().setExternalId("ext-g1").setDisplayName("engineers")));
    assertEquals(409, exception.getStatus());
    assertEquals(
        "Group already exists: displayName=engineers, externalId=ext-g1", exception.getMessage());
  }

  @Test
  void testCreateGroupWithMembers() throws Exception {
    Group group = ScimServiceTestEntities.group(GROUP_ID, "engineering", "group-1");
    GroupMembership membership = new GroupMembership().setValue("3");
    when(dispatcher.addGroup(METALAKE, "engineering", "group-1")).thenReturn(group);
    when(membershipManager.listMembersForGroup(METALAKE, GROUP_ID))
        .thenReturn(
            List.of(ScimGroupMemberPO.builder().withUserId(USER_ID).withUserName("alice").build()));

    ScimGroup created =
        adapter.create(
            new ScimGroup()
                .setExternalId("group-1")
                .setDisplayName("engineering")
                .setMembers(List.of(membership)));
    assertEquals("2", created.getId());
    assertEquals(1, created.getMembers().size());
    assertEquals("3", created.getMembers().get(0).getValue());
    verify(dispatcher).addGroup(METALAKE, "engineering", "group-1");
    verify(membershipManager).replaceUsersInGroup(eq(METALAKE), eq(GROUP_ID), eq(List.of(USER_ID)));
  }

  @Test
  void testCreateGroup() throws Exception {
    Group created = ScimServiceTestEntities.group(1L, "engineers", "ext-g1");
    when(dispatcher.addGroup(METALAKE, "engineers", "ext-g1")).thenReturn(created);
    when(membershipManager.listMembersForGroup(METALAKE, 1L)).thenReturn(List.of());

    ScimGroup result =
        adapter.create(
            new ScimGroup()
                .setExternalId("ext-g1")
                .setDisplayName("engineers")
                .setMembers(List.of()));
    assertEquals("1", result.getId());
    assertEquals("ext-g1", result.getExternalId());
    verify(dispatcher).addGroup(METALAKE, "engineers", "ext-g1");
  }

  @Test
  void testPatchMembersAdd() throws Exception {
    Group group = ScimServiceTestEntities.group(GROUP_ID, "engineering", "group-1");
    when(dispatcher.getGroupById(METALAKE, GROUP_ID)).thenReturn(group);
    when(membershipManager.listMembersForGroup(METALAKE, GROUP_ID))
        .thenReturn(
            List.of(ScimGroupMemberPO.builder().withUserId(USER_ID).withUserName("alice").build()));

    PatchOperation operation = new PatchOperation();
    operation.setOperation(PatchOperation.Type.ADD);
    operation.setValue(List.of(new GroupMembership().setValue("3")));

    ScimGroup patched = adapter.patch("2", null, List.of(operation), null, null);
    assertEquals("2", patched.getId());
    verify(membershipManager).addUsersToGroup(eq(METALAKE), eq(GROUP_ID), eq(List.of(USER_ID)));
  }

  @Test
  void testPatchMembersRemove() throws Exception {
    Group group = ScimServiceTestEntities.group(GROUP_ID, "engineering", "group-1");
    when(dispatcher.getGroupById(METALAKE, GROUP_ID)).thenReturn(group);
    when(membershipManager.listMembersForGroup(METALAKE, GROUP_ID)).thenReturn(List.of());

    PatchOperation operation = new PatchOperation();
    operation.setOperation(PatchOperation.Type.REMOVE);
    operation.setValue(List.of(new GroupMembership().setValue("3")));

    ScimGroup patched = adapter.patch("2", null, List.of(operation), null, null);
    assertEquals("2", patched.getId());
    verify(membershipManager)
        .removeUsersFromGroup(eq(METALAKE), eq(GROUP_ID), eq(List.of(USER_ID)));
  }

  @Test
  void testPatchMembersReplace() throws Exception {
    Group group = ScimServiceTestEntities.group(GROUP_ID, "engineering", "group-1");
    when(dispatcher.getGroupById(METALAKE, GROUP_ID)).thenReturn(group);
    when(membershipManager.listMembersForGroup(METALAKE, GROUP_ID)).thenReturn(List.of());

    PatchOperation operation = new PatchOperation();
    operation.setOperation(PatchOperation.Type.REPLACE);
    operation.setValue(
        List.of(new GroupMembership().setValue("3"), new GroupMembership().setValue("4")));

    ScimGroup patched = adapter.patch("2", null, List.of(operation), null, null);
    assertEquals("2", patched.getId());
    verify(membershipManager)
        .replaceUsersInGroup(eq(METALAKE), eq(GROUP_ID), eq(List.of(USER_ID, 4L)));
  }

  @Test
  void testPatchMembersInvalidPath() throws Exception {
    Group group = ScimServiceTestEntities.group(GROUP_ID, "engineering", "group-1");
    when(dispatcher.getGroupById(METALAKE, GROUP_ID)).thenReturn(group);

    PatchOperation operation = new PatchOperation();
    operation.setOperation(PatchOperation.Type.REPLACE);
    operation.setPath(PatchOperationPath.fromString("displayName"));
    operation.setValue("other");

    assertThrows(
        ResourceException.class, () -> adapter.patch("2", null, List.of(operation), null, null));
  }

  @Test
  void testUpdateMembers() throws Exception {
    Group group = ScimServiceTestEntities.group(GROUP_ID, "engineering", "group-1");
    when(dispatcher.getGroupById(METALAKE, GROUP_ID)).thenReturn(group);
    when(membershipManager.listMembersForGroup(METALAKE, GROUP_ID))
        .thenReturn(
            List.of(ScimGroupMemberPO.builder().withUserId(USER_ID).withUserName("alice").build()));

    ScimGroup updated =
        adapter.update(
            "2",
            null,
            new ScimGroup()
                .setId("2")
                .setExternalId("group-1")
                .setDisplayName("engineering")
                .setMembers(List.of(new GroupMembership().setValue("3"))),
            null,
            null);

    assertEquals("2", updated.getId());
    assertEquals(1, updated.getMembers().size());
    assertEquals("3", updated.getMembers().get(0).getValue());
    verify(membershipManager).replaceUsersInGroup(eq(METALAKE), eq(GROUP_ID), eq(List.of(USER_ID)));
  }

  @Test
  void testUpdateEmptyMembers() throws Exception {
    Group group = ScimServiceTestEntities.group(GROUP_ID, "engineering", "group-1");
    when(dispatcher.getGroupById(METALAKE, GROUP_ID)).thenReturn(group);
    when(membershipManager.listMembersForGroup(METALAKE, GROUP_ID)).thenReturn(List.of());

    ScimGroup updated =
        adapter.update(
            "2",
            null,
            new ScimGroup()
                .setExternalId("group-1")
                .setDisplayName("engineering")
                .setMembers(List.of()),
            null,
            null);

    assertEquals("2", updated.getId());
    assertEquals(0, updated.getMembers().size());
    verify(membershipManager).replaceUsersInGroup(eq(METALAKE), eq(GROUP_ID), eq(List.of()));
  }

  @Test
  void testUpdateSameName() throws Exception {
    Group group = ScimServiceTestEntities.group(GROUP_ID, "engineering", "group-1");
    when(dispatcher.getGroupById(METALAKE, GROUP_ID)).thenReturn(group);
    when(membershipManager.listMembersForGroup(METALAKE, GROUP_ID)).thenReturn(List.of());

    ScimGroup updated =
        adapter.update(
            "2",
            null,
            new ScimGroup().setDisplayName("engineering").setMembers(List.of()),
            null,
            null);

    assertEquals("engineering", updated.getDisplayName());
    verify(membershipManager).replaceUsersInGroup(eq(METALAKE), eq(GROUP_ID), eq(List.of()));
  }

  @Test
  void testUpdateNoName() throws Exception {
    Group group = ScimServiceTestEntities.group(GROUP_ID, "engineering", "group-1");
    when(dispatcher.getGroupById(METALAKE, GROUP_ID)).thenReturn(group);
    when(membershipManager.listMembersForGroup(METALAKE, GROUP_ID)).thenReturn(List.of());

    ScimGroup updated =
        adapter.update("2", null, new ScimGroup().setMembers(List.of()), null, null);

    assertEquals("engineering", updated.getDisplayName());
    verify(membershipManager).replaceUsersInGroup(eq(METALAKE), eq(GROUP_ID), eq(List.of()));
  }

  @Test
  void testUpdateRename400() throws Exception {
    Group group = ScimServiceTestEntities.group(GROUP_ID, "engineering", "group-1");
    when(dispatcher.getGroupById(METALAKE, GROUP_ID)).thenReturn(group);

    ResourceException exception =
        assertThrows(
            ResourceException.class,
            () ->
                adapter.update(
                    "2",
                    null,
                    new ScimGroup().setDisplayName("renamed").setMembers(List.of()),
                    null,
                    null));
    assertEquals(400, exception.getStatus());
    verify(membershipManager, never()).replaceUsersInGroup(anyString(), anyLong(), any());
  }

  @Test
  void testUpdateExtId400() throws Exception {
    Group group = ScimServiceTestEntities.group(GROUP_ID, "engineering", "group-1");
    when(dispatcher.getGroupById(METALAKE, GROUP_ID)).thenReturn(group);

    ResourceException exception =
        assertThrows(
            ResourceException.class,
            () ->
                adapter.update(
                    "2",
                    null,
                    new ScimGroup()
                        .setExternalId("other-ext")
                        .setDisplayName("engineering")
                        .setMembers(List.of()),
                    null,
                    null));
    assertEquals(400, exception.getStatus());
    verify(membershipManager, never()).replaceUsersInGroup(anyString(), anyLong(), any());
  }

  @Test
  void testUpdateId400() throws Exception {
    Group group = ScimServiceTestEntities.group(GROUP_ID, "engineering", "group-1");
    when(dispatcher.getGroupById(METALAKE, GROUP_ID)).thenReturn(group);

    ResourceException exception =
        assertThrows(
            ResourceException.class,
            () ->
                adapter.update(
                    "2",
                    null,
                    new ScimGroup()
                        .setId("other-id")
                        .setDisplayName("engineering")
                        .setMembers(List.of()),
                    null,
                    null));
    assertEquals(400, exception.getStatus());
    verify(membershipManager, never()).replaceUsersInGroup(anyString(), anyLong(), any());
  }

  @Test
  void testUpdate404() {
    when(dispatcher.getGroupById(METALAKE, 999L)).thenThrow(new NoSuchGroupException("999"));

    ResourceException exception =
        assertThrows(
            ResourceException.class,
            () ->
                adapter.update(
                    "999", null, new ScimGroup().setDisplayName("engineering"), null, null));
    assertEquals(404, exception.getStatus());
  }

  @Test
  void testDelete() throws Exception {
    when(dispatcher.removeGroupById(METALAKE, 1L)).thenReturn(true);
    adapter.delete("1");
    verify(dispatcher).removeGroupById(eq(METALAKE), eq(1L));
  }

  @Test
  void testDelete404() {
    when(dispatcher.removeGroupById(METALAKE, 999L)).thenReturn(false);
    ResourceException exception =
        assertThrows(ResourceException.class, () -> adapter.delete("999"));
    assertEquals(404, exception.getStatus());
  }

  @Test
  void testFindByDisplayName() throws Exception {
    Group group = ScimServiceTestEntities.group(1L, "engineers", "ext-g1");
    when(dispatcher.getGroup(METALAKE, "engineers")).thenReturn(group);
    when(membershipManager.listMembersForGroup(METALAKE, 1L)).thenReturn(List.of());

    var response =
        adapter.find(
            Filter.decode("displayName eq \"engineers\""),
            new PageRequest().setStartIndex(1).setCount(10),
            null);
    assertEquals(1, response.getTotalResults());
    assertEquals("engineers", response.getResources().iterator().next().getDisplayName());
    assertEquals("1", response.getResources().iterator().next().getId());
  }

  @Test
  void testFindByExternalId() throws Exception {
    Group group = ScimServiceTestEntities.group(1L, "engineers", "ext-g1");
    when(dispatcher.getGroupByExternalId(METALAKE, "ext-g1")).thenReturn(group);
    when(membershipManager.listMembersForGroup(METALAKE, 1L)).thenReturn(List.of());

    var response =
        adapter.find(
            Filter.decode("externalId eq \"ext-g1\""),
            new PageRequest().setStartIndex(1).setCount(10),
            null);
    assertEquals(1, response.getTotalResults());
    assertEquals("1", response.getResources().iterator().next().getId());
    assertEquals("ext-g1", response.getResources().iterator().next().getExternalId());
  }

  @Test
  void testFindAndMismatch() throws Exception {
    Group group = ScimServiceTestEntities.group(1L, "engineers", "ext-g1");
    when(dispatcher.getGroupByExternalId(METALAKE, "ext-g1")).thenReturn(group);

    var response =
        adapter.find(
            Filter.decode("externalId eq \"ext-g1\" and displayName eq \"other\""),
            new PageRequest().setStartIndex(1).setCount(10),
            null);
    assertEquals(0, response.getTotalResults());
    assertEquals(0, response.getResources().size());
  }

  @Test
  void testFindEmpty() throws Exception {
    var response = adapter.find(null, new PageRequest().setStartIndex(1).setCount(10), null);
    assertEquals(0, response.getTotalResults());
    assertEquals(0, response.getResources().size());
  }

  @Test
  void testGet404() throws Exception {
    when(dispatcher.getGroupById(eq(METALAKE), eq(999L)))
        .thenThrow(new NoSuchGroupException("999"));
    assertThrows(ResourceException.class, () -> adapter.get("999"));
  }

  @Test
  void testGetWithMembers() throws Exception {
    Group group = ScimServiceTestEntities.group(1L, "engineers", "ext-g1");
    when(dispatcher.getGroupById(METALAKE, 1L)).thenReturn(group);
    when(membershipManager.listMembersForGroup(METALAKE, 1L))
        .thenReturn(
            List.of(ScimGroupMemberPO.builder().withUserId(1L).withUserName("alice").build()));

    ScimGroup result = adapter.get("1");
    assertEquals(1, result.getMembers().size());
    assertEquals("1", result.getMembers().get(0).getValue());
  }
}
