/*
 * Copyright 2026 Datastrato Pvt Ltd.
 * This software is licensed under the Apache License version 2.
 */

package com.datastrato.gravitino.scim.service.adapter;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.datastrato.gravitino.scim.ScimUserGroupRelManager;
import com.datastrato.gravitino.scim.service.ScimConfig;
import com.datastrato.gravitino.scim.service.ScimServiceTestEntities;
import com.datastrato.gravitino.scim.service.listener.ScimGroupEventDispatcher;
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
import org.apache.gravitino.authorization.PagedResult;
import org.apache.gravitino.exceptions.GroupAlreadyExistsException;
import org.apache.gravitino.exceptions.NoSuchGroupException;
import org.apache.gravitino.listener.AccessControlEventDispatcher;
import org.apache.gravitino.listener.EventBus;
import org.apache.gravitino.listener.api.event.AddGroupEvent;
import org.apache.gravitino.listener.api.event.AlterGroupEvent;
import org.apache.gravitino.listener.api.event.BaseEvent;
import org.apache.gravitino.listener.api.event.GetGroupByIdEvent;
import org.apache.gravitino.listener.api.event.RemoveGroupEvent;
import org.apache.gravitino.listener.api.event.scim.ScimAddGroupEvent;
import org.apache.gravitino.listener.api.event.scim.ScimAddGroupPreEvent;
import org.apache.gravitino.listener.api.event.scim.ScimAlterGroupEvent;
import org.apache.gravitino.listener.api.event.scim.ScimAlterGroupPreEvent;
import org.apache.gravitino.listener.api.event.scim.ScimRemoveGroupEvent;
import org.apache.gravitino.listener.api.event.scim.ScimRemoveGroupPreEvent;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

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
    when(dispatcher.listGroups(METALAKE)).thenReturn(new Group[0]);
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
  void testCreateConflictIgnoreCase409() throws Exception {
    Group existing = ScimServiceTestEntities.group(GROUP_ID, "engineers", "ext-g1");
    when(dispatcher.listGroups(METALAKE)).thenReturn(new Group[] {existing});

    ResourceException exception =
        assertThrows(
            ResourceException.class,
            () ->
                adapter.create(
                    new ScimGroup().setExternalId("ext-g2").setDisplayName("Engineers")));
    assertEquals(409, exception.getStatus());
    assertEquals(
        "Group already exists: displayName=Engineers, externalId=ext-g2", exception.getMessage());
    verify(dispatcher, never()).addGroup(anyString(), anyString(), anyString());
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
  void testPatchMembersRemoveByValueFilter() throws Exception {
    Group group = ScimServiceTestEntities.group(GROUP_ID, "engineering", "group-1");
    when(dispatcher.getGroupById(METALAKE, GROUP_ID)).thenReturn(group);
    when(membershipManager.listMembersForGroup(METALAKE, GROUP_ID)).thenReturn(List.of());

    PatchOperation operation = new PatchOperation();
    operation.setOperation(PatchOperation.Type.REMOVE);
    operation.setPath(PatchOperationPath.fromString("members[value eq \"3\"]"));

    ScimGroup patched = adapter.patch("2", null, List.of(operation), null, null);
    assertEquals("2", patched.getId());
    verify(membershipManager)
        .removeUsersFromGroup(eq(METALAKE), eq(GROUP_ID), eq(List.of(USER_ID)));
  }

  @Test
  void testPatchMembersReplaceByValueFilter() throws Exception {
    Group group = ScimServiceTestEntities.group(GROUP_ID, "engineering", "group-1");
    when(dispatcher.getGroupById(METALAKE, GROUP_ID)).thenReturn(group);
    when(membershipManager.listMembersForGroup(METALAKE, GROUP_ID)).thenReturn(List.of());
    when(membershipManager.replaceMemberUserInGroup(METALAKE, GROUP_ID, USER_ID, 4L))
        .thenReturn(true);

    PatchOperation operation = new PatchOperation();
    operation.setOperation(PatchOperation.Type.REPLACE);
    operation.setPath(PatchOperationPath.fromString("members[value eq \"3\"]"));
    operation.setValue(new GroupMembership().setValue("4"));

    ScimGroup patched = adapter.patch("2", null, List.of(operation), null, null);
    assertEquals("2", patched.getId());
    verify(membershipManager).replaceMemberUserInGroup(METALAKE, GROUP_ID, USER_ID, 4L);
    verify(membershipManager, never()).replaceUsersInGroup(anyString(), anyLong(), any());
  }

  @Test
  void testPatchMembersReplaceByValueFilterMissing404() throws Exception {
    Group group = ScimServiceTestEntities.group(GROUP_ID, "engineering", "group-1");
    when(dispatcher.getGroupById(METALAKE, GROUP_ID)).thenReturn(group);
    when(membershipManager.replaceMemberUserInGroup(METALAKE, GROUP_ID, USER_ID, 4L))
        .thenReturn(false);

    PatchOperation operation = new PatchOperation();
    operation.setOperation(PatchOperation.Type.REPLACE);
    operation.setPath(PatchOperationPath.fromString("members[value eq \"3\"]"));
    operation.setValue(Map.of("value", "4"));

    ResourceException exception =
        assertThrows(
            ResourceException.class,
            () -> adapter.patch("2", null, List.of(operation), null, null));
    assertEquals(404, exception.getStatus());
  }

  @Test
  void testPatchReplaceExternalId() throws Exception {
    Group group = ScimServiceTestEntities.group(GROUP_ID, "engineering", "group-1");
    Group updated = ScimServiceTestEntities.group(GROUP_ID, "engineering", "group-2");
    when(dispatcher.getGroupById(METALAKE, GROUP_ID)).thenReturn(group);
    when(dispatcher.alterGroupById(eq(METALAKE), eq(GROUP_ID), any())).thenReturn(updated);
    when(membershipManager.listMembersForGroup(METALAKE, GROUP_ID)).thenReturn(List.of());

    PatchOperation operation = new PatchOperation();
    operation.setOperation(PatchOperation.Type.REPLACE);
    operation.setValue(Map.of("externalId", "group-2"));

    ScimGroup patched = adapter.patch("2", null, List.of(operation), null, null);
    assertEquals("group-2", patched.getExternalId());
    verify(dispatcher).alterGroupById(eq(METALAKE), eq(GROUP_ID), any());
  }

  @Test
  void testPatchReplaceExternalIdByPath() throws Exception {
    Group group = ScimServiceTestEntities.group(GROUP_ID, "engineering", "group-1");
    Group updated = ScimServiceTestEntities.group(GROUP_ID, "engineering", "group-2");
    when(dispatcher.getGroupById(METALAKE, GROUP_ID)).thenReturn(group);
    when(dispatcher.alterGroupById(eq(METALAKE), eq(GROUP_ID), any())).thenReturn(updated);
    when(membershipManager.listMembersForGroup(METALAKE, GROUP_ID)).thenReturn(List.of());

    PatchOperation operation = new PatchOperation();
    operation.setOperation(PatchOperation.Type.REPLACE);
    operation.setPath(PatchOperationPath.fromString("externalId"));
    operation.setValue("group-2");

    ScimGroup patched = adapter.patch("2", null, List.of(operation), null, null);
    assertEquals("group-2", patched.getExternalId());
    verify(dispatcher).alterGroupById(eq(METALAKE), eq(GROUP_ID), any());
  }

  @Test
  void testPatchUnchangedExternalIdSkipsAlter() throws Exception {
    Group group = ScimServiceTestEntities.group(GROUP_ID, "engineering", "group-1");
    when(dispatcher.getGroupById(METALAKE, GROUP_ID)).thenReturn(group);
    when(membershipManager.listMembersForGroup(METALAKE, GROUP_ID)).thenReturn(List.of());

    PatchOperation operation = new PatchOperation();
    operation.setOperation(PatchOperation.Type.REPLACE);
    operation.setValue(Map.of("externalId", "group-1"));

    ScimGroup patched = adapter.patch("2", null, List.of(operation), null, null);
    assertEquals("group-1", patched.getExternalId());
    verify(dispatcher, never()).alterGroupById(anyString(), anyLong(), any());
  }

  @Test
  void testPatchRenameDisplayName400() throws Exception {
    Group group = ScimServiceTestEntities.group(GROUP_ID, "engineering", "group-1");
    when(dispatcher.getGroupById(METALAKE, GROUP_ID)).thenReturn(group);

    PatchOperation operation = new PatchOperation();
    operation.setOperation(PatchOperation.Type.REPLACE);
    operation.setValue(Map.of("displayName", "renamed"));

    ResourceException exception =
        assertThrows(
            ResourceException.class,
            () -> adapter.patch("2", null, List.of(operation), null, null));
    assertEquals(400, exception.getStatus());
    assertEquals("Group displayName is immutable", exception.getMessage());
  }

  @Test
  void testPatchDisplayNameCaseOnlyNoOp() throws Exception {
    Group group = ScimServiceTestEntities.group(GROUP_ID, "engineering", "group-1");
    when(dispatcher.getGroupById(METALAKE, GROUP_ID)).thenReturn(group);
    when(membershipManager.listMembersForGroup(METALAKE, GROUP_ID)).thenReturn(List.of());

    PatchOperation operation = new PatchOperation();
    operation.setOperation(PatchOperation.Type.REPLACE);
    operation.setValue(Map.of("displayName", "Engineering"));

    ScimGroup patched = adapter.patch("2", null, List.of(operation), null, null);
    assertEquals("engineering", patched.getDisplayName());
  }

  @Test
  void testFindByDisplayNameIgnoreCaseMiss() throws Exception {
    Group group = ScimServiceTestEntities.group(1L, "Engineers", "ext-g1");
    when(dispatcher.getGroup(METALAKE, "ops")).thenThrow(new NoSuchGroupException("ops"));
    when(dispatcher.listGroups(METALAKE)).thenReturn(new Group[] {group});

    var response =
        adapter.find(
            Filter.decode("displayName eq \"ops\""),
            new PageRequest().setStartIndex(1).setCount(10),
            null);
    assertEquals(0, response.getTotalResults());
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
    operation.setPath(PatchOperationPath.fromString("roles"));
    operation.setValue("other");

    ResourceException exception =
        assertThrows(
            ResourceException.class,
            () -> adapter.patch("2", null, List.of(operation), null, null));
    assertEquals(400, exception.getStatus());
  }

  @Test
  void testFindByDisplayNameIgnoreCase() throws Exception {
    Group group = ScimServiceTestEntities.group(1L, "Engineers", "ext-g1");
    when(dispatcher.getGroup(METALAKE, "engineers"))
        .thenThrow(new NoSuchGroupException("engineers"));
    when(dispatcher.listGroups(METALAKE)).thenReturn(new Group[] {group});
    when(membershipManager.listMembersForGroup(METALAKE, 1L)).thenReturn(List.of());

    var response =
        adapter.find(
            Filter.decode("displayName eq \"engineers\""),
            new PageRequest().setStartIndex(1).setCount(10),
            null);
    assertEquals(1, response.getTotalResults());
    assertEquals("Engineers", response.getResources().iterator().next().getDisplayName());
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
  void testUpdateDisplayNameCaseOnlyNoOp() throws Exception {
    Group group = ScimServiceTestEntities.group(GROUP_ID, "engineering", "group-1");
    when(dispatcher.getGroupById(METALAKE, GROUP_ID)).thenReturn(group);
    when(membershipManager.listMembersForGroup(METALAKE, GROUP_ID)).thenReturn(List.of());

    ScimGroup updated =
        adapter.update(
            "2",
            null,
            new ScimGroup().setDisplayName("Engineering").setMembers(List.of()),
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
    when(dispatcher.listGroups(METALAKE, 0, 10)).thenReturn(new PagedResult<>(0, List.of()));
    var response = adapter.find(null, new PageRequest().setStartIndex(1).setCount(10), null);
    assertEquals(0, response.getTotalResults());
    assertEquals(0, response.getResources().size());
    verify(dispatcher).listGroups(METALAKE, 0, 10);
  }

  @Test
  void testFindUnfilteredPaged() throws Exception {
    Group engineers = ScimServiceTestEntities.group(GROUP_ID, "engineers", "ext-g1");
    Group ops = ScimServiceTestEntities.group(4L, "ops", "ext-g2");
    when(dispatcher.listGroups(METALAKE, 0, 10))
        .thenReturn(new PagedResult<>(2, List.of(engineers, ops)));
    when(membershipManager.listMembersForGroup(anyString(), anyLong())).thenReturn(List.of());

    var response = adapter.find(null, new PageRequest().setStartIndex(1).setCount(10), null);

    assertEquals(2, response.getTotalResults());
    assertEquals(2, response.getResources().size());
    var groups = response.getResources().toArray(new ScimGroup[0]);
    assertEquals("engineers", groups[0].getDisplayName());
    assertEquals("ops", groups[1].getDisplayName());
    verify(dispatcher).listGroups(METALAKE, 0, 10);
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

  @Test
  void testCreateThroughInternalDispatcherDoesNotEmitCoreGroupEvents() throws Exception {
    AccessControlDispatcher manager = mock(AccessControlDispatcher.class);
    when(manager.listGroups(METALAKE)).thenReturn(new Group[0]);
    Group created = ScimServiceTestEntities.group(GROUP_ID, "engineering", "ext-1");
    when(manager.addGroup(METALAKE, "engineering", "ext-1")).thenReturn(created);
    when(membershipManager.listMembersForGroup(METALAKE, GROUP_ID)).thenReturn(List.of());

    EventBus eventBus = mock(EventBus.class);
    ScimGroupEventDispatcher scimDispatcher = newInternalChainedDispatcher(eventBus, manager);

    ScimGroup result =
        scimDispatcher.create(new ScimGroup().setDisplayName("engineering").setExternalId("ext-1"));
    assertEquals("2", result.getId());

    ArgumentCaptor<BaseEvent> captor = ArgumentCaptor.forClass(BaseEvent.class);
    verify(eventBus, times(2)).dispatchEvent(captor.capture());
    List<BaseEvent> events = captor.getAllValues();
    assertInstanceOf(ScimAddGroupPreEvent.class, events.get(0));
    assertInstanceOf(ScimAddGroupEvent.class, events.get(1));
    assertTrue(events.stream().noneMatch(AddGroupEvent.class::isInstance));
  }

  @Test
  void testCreateThroughAuditedDispatcherEmitsCoreGroupEvents() throws Exception {
    AccessControlDispatcher manager = mock(AccessControlDispatcher.class);
    when(manager.listGroups(METALAKE)).thenReturn(new Group[0]);
    Group created = ScimServiceTestEntities.group(GROUP_ID, "engineering", "ext-1");
    when(manager.addGroup(METALAKE, "engineering", "ext-1")).thenReturn(created);
    when(membershipManager.listMembersForGroup(METALAKE, GROUP_ID)).thenReturn(List.of());

    EventBus eventBus = mock(EventBus.class);
    ScimGroupEventDispatcher scimDispatcher = newAuditedChainedDispatcher(eventBus, manager);

    scimDispatcher.create(new ScimGroup().setDisplayName("engineering").setExternalId("ext-1"));

    ArgumentCaptor<BaseEvent> captor = ArgumentCaptor.forClass(BaseEvent.class);
    verify(eventBus, org.mockito.Mockito.atLeastOnce()).dispatchEvent(captor.capture());
    assertTrue(captor.getAllValues().stream().anyMatch(AddGroupEvent.class::isInstance));
    assertTrue(captor.getAllValues().stream().anyMatch(ScimAddGroupEvent.class::isInstance));
  }

  @Test
  void testUpdateThroughInternalDispatcherDoesNotEmitCoreGroupEvents() throws Exception {
    AccessControlDispatcher manager = mock(AccessControlDispatcher.class);
    Group existing = ScimServiceTestEntities.group(GROUP_ID, "engineering", "ext-1");
    when(manager.getGroupById(METALAKE, GROUP_ID)).thenReturn(existing);
    when(membershipManager.listMembersForGroup(METALAKE, GROUP_ID)).thenReturn(List.of());

    EventBus eventBus = mock(EventBus.class);
    ScimGroupEventDispatcher scimDispatcher = newInternalChainedDispatcher(eventBus, manager);

    ScimGroup result =
        scimDispatcher.update(
            "2",
            null,
            new ScimGroup().setId("2").setExternalId("ext-1").setDisplayName("engineering"),
            null,
            null);
    assertEquals("engineering", result.getDisplayName());

    ArgumentCaptor<BaseEvent> captor = ArgumentCaptor.forClass(BaseEvent.class);
    verify(eventBus, times(2)).dispatchEvent(captor.capture());
    List<BaseEvent> events = captor.getAllValues();
    assertInstanceOf(ScimAlterGroupPreEvent.class, events.get(0));
    assertInstanceOf(ScimAlterGroupEvent.class, events.get(1));
    assertTrue(events.stream().noneMatch(GetGroupByIdEvent.class::isInstance));
    assertTrue(events.stream().noneMatch(AlterGroupEvent.class::isInstance));
  }

  @Test
  void testDeleteThroughInternalDispatcherDoesNotEmitCoreGroupEvents() throws Exception {
    AccessControlDispatcher manager = mock(AccessControlDispatcher.class);
    Group existing = ScimServiceTestEntities.group(GROUP_ID, "engineering", "ext-1");
    when(manager.getGroupById(METALAKE, GROUP_ID)).thenReturn(existing);
    when(manager.removeGroupById(METALAKE, GROUP_ID)).thenReturn(true);
    when(membershipManager.listMembersForGroup(METALAKE, GROUP_ID)).thenReturn(List.of());

    EventBus eventBus = mock(EventBus.class);
    ScimGroupEventDispatcher scimDispatcher = newInternalChainedDispatcher(eventBus, manager);

    scimDispatcher.delete("2");

    ArgumentCaptor<BaseEvent> captor = ArgumentCaptor.forClass(BaseEvent.class);
    verify(eventBus, times(2)).dispatchEvent(captor.capture());
    List<BaseEvent> events = captor.getAllValues();
    assertInstanceOf(ScimRemoveGroupPreEvent.class, events.get(0));
    assertInstanceOf(ScimRemoveGroupEvent.class, events.get(1));
    assertEquals("engineering", ((ScimRemoveGroupEvent) events.get(1)).groupName());
    assertTrue(events.stream().noneMatch(GetGroupByIdEvent.class::isInstance));
    assertTrue(events.stream().noneMatch(RemoveGroupEvent.class::isInstance));
    // Preload for SCIM audit naming uses getGroupById on the internal dispatcher (no core events).
    verify(manager).getGroupById(METALAKE, GROUP_ID);
  }

  private ScimGroupEventDispatcher newInternalChainedDispatcher(
      EventBus eventBus, AccessControlDispatcher manager) {
    return new ScimGroupEventDispatcher(
        eventBus, new ScimGroupRepositoryAdapter(manager, membershipManager, scimConfig));
  }

  private ScimGroupEventDispatcher newAuditedChainedDispatcher(
      EventBus eventBus, AccessControlDispatcher manager) {
    AccessControlDispatcher audited = new AccessControlEventDispatcher(eventBus, manager);
    return new ScimGroupEventDispatcher(
        eventBus, new ScimGroupRepositoryAdapter(audited, membershipManager, scimConfig));
  }
}
