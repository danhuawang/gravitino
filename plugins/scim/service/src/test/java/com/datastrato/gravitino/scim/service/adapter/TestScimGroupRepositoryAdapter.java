/*
 * Copyright 2026 Datastrato Inc.
 */

package com.datastrato.gravitino.scim.service.adapter;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.datastrato.gravitino.scim.ScimGroupManager;
import com.datastrato.gravitino.scim.ScimUserGroupRelManager;
import com.datastrato.gravitino.scim.model.ScimGroupMeta;
import com.datastrato.gravitino.scim.service.ScimConfig;
import com.datastrato.gravitino.scim.service.ScimServiceTestEntities;
import com.datastrato.gravitino.scim.service.listener.ScimGroupEventDispatcher;
import com.datastrato.gravitino.scim.service.web.ScimRequestContext;
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
import org.apache.gravitino.authorization.PagedResult;
import org.apache.gravitino.exceptions.AlreadyExistsException;
import org.apache.gravitino.exceptions.NotFoundException;
import org.apache.gravitino.listener.EventBus;
import org.apache.gravitino.listener.api.event.BaseEvent;
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

  private static final String GROUP_EXT_ID = "group-1";
  private static final String GROUP_EXT_G1 = "ext-g1";
  private static final long GROUP_ID = 2L;
  private static final long USER_ID = 3L;
  private static final String GROUP_SCIM_ID = String.valueOf(GROUP_ID);
  private static final String USER_SCIM_ID = String.valueOf(USER_ID);

  private ScimGroupManager groupManager;
  private ScimUserGroupRelManager membershipManager;
  private ScimGroupRepositoryAdapter adapter;
  private ScimConfig scimConfig;

  @BeforeEach
  void setUp() {
    scimConfig = new ScimConfig(Map.of(), new Config() {});
    groupManager = mock(ScimGroupManager.class);
    membershipManager = mock(ScimUserGroupRelManager.class);
    when(membershipManager.listMembersForGroup(anyLong())).thenReturn(List.of());
    when(membershipManager.listMembersForGroups(anyList())).thenReturn(Map.of());
    adapter = new ScimGroupRepositoryAdapter(groupManager, membershipManager, scimConfig);
    ScimRequestContext.bindRequestBaseUri("http://localhost:9201");
  }

  @AfterEach
  void tearDown() {
    ScimRequestContext.clear();
  }

  @Test
  void testCreateNoDisplayName() {
    ResourceException exception =
        assertThrows(ResourceException.class, () -> adapter.create(new ScimGroup()));
    assertEquals(400, exception.getStatus());
  }

  @Test
  void testCreateWithoutExternalId() throws Exception {
    ScimGroupMeta created = ScimServiceTestEntities.group(1L, "engineers", null);
    when(groupManager.createGroup("engineers", null)).thenReturn(created);
    when(membershipManager.listMembersForGroup(1L)).thenReturn(List.of());

    ScimGroup result = adapter.create(new ScimGroup().setDisplayName("engineers"));
    assertEquals("1", result.getId());
    assertEquals(null, result.getExternalId());
    verify(groupManager).createGroup("engineers", null);
  }

  @Test
  void testCreateConflict409() throws Exception {
    when(groupManager.createGroup("engineers", GROUP_EXT_G1))
        .thenThrow(new AlreadyExistsException("engineers"));

    ResourceException exception =
        assertThrows(
            ResourceException.class,
            () ->
                adapter.create(
                    new ScimGroup().setExternalId(GROUP_EXT_G1).setDisplayName("engineers")));
    assertEquals(409, exception.getStatus());
    assertEquals("Group already exists: displayName=engineers", exception.getMessage());
  }

  @Test
  void testCreateConflictIgnoreCase409() throws Exception {
    when(groupManager.createGroup("Engineers", "ext-g2"))
        .thenThrow(new AlreadyExistsException("Engineers"));

    ResourceException exception =
        assertThrows(
            ResourceException.class,
            () ->
                adapter.create(
                    new ScimGroup().setExternalId("ext-g2").setDisplayName("Engineers")));
    assertEquals(409, exception.getStatus());
    assertEquals("Group already exists: displayName=Engineers", exception.getMessage());
  }

  @Test
  void testCreateGroupWithMembers() throws Exception {
    ScimGroupMeta group = ScimServiceTestEntities.group(GROUP_ID, "engineering", GROUP_EXT_ID);
    GroupMembership membership = new GroupMembership().setValue(USER_SCIM_ID);
    when(groupManager.createGroup("engineering", GROUP_EXT_ID)).thenReturn(group);
    when(membershipManager.listMembersForGroup(GROUP_ID))
        .thenReturn(
            List.of(
                ScimGroupMemberPO.builder()
                    .withUserId(USER_ID)
                    .withUserName("alice")
                    .withExternalId("member-ext")
                    .build()));

    ScimGroup created =
        adapter.create(
            new ScimGroup()
                .setExternalId(GROUP_EXT_ID)
                .setDisplayName("engineering")
                .setMembers(List.of(membership)));
    assertEquals(GROUP_SCIM_ID, created.getId());
    assertEquals(1, created.getMembers().size());
    assertEquals(USER_SCIM_ID, created.getMembers().get(0).getValue());
    verify(groupManager).createGroup("engineering", GROUP_EXT_ID);
    verify(membershipManager).replaceUsersInGroup(eq(GROUP_ID), eq(List.of(USER_ID)));
  }

  @Test
  void testCreateGroup() throws Exception {
    ScimGroupMeta created = ScimServiceTestEntities.group(1L, "engineers", GROUP_EXT_G1);
    when(groupManager.createGroup("engineers", GROUP_EXT_G1)).thenReturn(created);
    when(membershipManager.listMembersForGroup(1L)).thenReturn(List.of());

    ScimGroup result =
        adapter.create(
            new ScimGroup()
                .setExternalId(GROUP_EXT_G1)
                .setDisplayName("engineers")
                .setMembers(List.of()));
    assertEquals("1", result.getId());
    assertEquals(GROUP_EXT_G1, result.getExternalId());
    verify(groupManager).createGroup("engineers", GROUP_EXT_G1);
  }

  @Test
  void testPatchMembersAdd() throws Exception {
    ScimGroupMeta group = ScimServiceTestEntities.group(GROUP_ID, "engineering", GROUP_EXT_ID);
    when(groupManager.getGroup(GROUP_ID)).thenReturn(group);
    when(membershipManager.listMembersForGroup(GROUP_ID))
        .thenReturn(
            List.of(
                ScimGroupMemberPO.builder()
                    .withUserId(USER_ID)
                    .withUserName("alice")
                    .withExternalId("member-ext")
                    .build()));

    PatchOperation operation = new PatchOperation();
    operation.setOperation(PatchOperation.Type.ADD);
    operation.setValue(List.of(new GroupMembership().setValue(USER_SCIM_ID)));

    ScimGroup patched = adapter.patch(GROUP_SCIM_ID, null, List.of(operation), null, null);
    assertEquals(GROUP_SCIM_ID, patched.getId());
    verify(membershipManager).addUsersToGroup(eq(GROUP_ID), eq(List.of(USER_ID)));
  }

  @Test
  void testPatchMembersRemove() throws Exception {
    ScimGroupMeta group = ScimServiceTestEntities.group(GROUP_ID, "engineering", GROUP_EXT_ID);
    when(groupManager.getGroup(GROUP_ID)).thenReturn(group);
    when(membershipManager.listMembersForGroup(GROUP_ID)).thenReturn(List.of());

    PatchOperation operation = new PatchOperation();
    operation.setOperation(PatchOperation.Type.REMOVE);
    operation.setValue(List.of(new GroupMembership().setValue(USER_SCIM_ID)));

    ScimGroup patched = adapter.patch(GROUP_SCIM_ID, null, List.of(operation), null, null);
    assertEquals(GROUP_SCIM_ID, patched.getId());
    verify(membershipManager).removeUsersFromGroup(eq(GROUP_ID), eq(List.of(USER_ID)));
  }

  @Test
  void testPatchMembersRemoveByValueFilter() throws Exception {
    ScimGroupMeta group = ScimServiceTestEntities.group(GROUP_ID, "engineering", GROUP_EXT_ID);
    when(groupManager.getGroup(GROUP_ID)).thenReturn(group);
    when(membershipManager.listMembersForGroup(GROUP_ID)).thenReturn(List.of());

    PatchOperation operation = new PatchOperation();
    operation.setOperation(PatchOperation.Type.REMOVE);
    operation.setPath(PatchOperationPath.fromString("members[value eq \"3\"]"));

    ScimGroup patched = adapter.patch(GROUP_SCIM_ID, null, List.of(operation), null, null);
    assertEquals(GROUP_SCIM_ID, patched.getId());
    verify(membershipManager).removeUsersFromGroup(eq(GROUP_ID), eq(List.of(USER_ID)));
  }

  @Test
  void testPatchMembersReplaceByValueFilter() throws Exception {
    ScimGroupMeta group = ScimServiceTestEntities.group(GROUP_ID, "engineering", GROUP_EXT_ID);
    when(groupManager.getGroup(GROUP_ID)).thenReturn(group);
    when(membershipManager.listMembersForGroup(GROUP_ID)).thenReturn(List.of());
    when(membershipManager.replaceMemberUserInGroup(GROUP_ID, USER_ID, 4L)).thenReturn(true);

    PatchOperation operation = new PatchOperation();
    operation.setOperation(PatchOperation.Type.REPLACE);
    operation.setPath(PatchOperationPath.fromString("members[value eq \"3\"]"));
    operation.setValue(new GroupMembership().setValue("4"));

    ScimGroup patched = adapter.patch(GROUP_SCIM_ID, null, List.of(operation), null, null);
    assertEquals(GROUP_SCIM_ID, patched.getId());
    verify(membershipManager).replaceMemberUserInGroup(GROUP_ID, USER_ID, 4L);
    verify(membershipManager, never()).replaceUsersInGroup(anyLong(), any());
  }

  @Test
  void testPatchMembersReplaceByValueFilterMissing404() throws Exception {
    ScimGroupMeta group = ScimServiceTestEntities.group(GROUP_ID, "engineering", GROUP_EXT_ID);
    when(groupManager.getGroup(GROUP_ID)).thenReturn(group);
    when(membershipManager.replaceMemberUserInGroup(GROUP_ID, USER_ID, 4L)).thenReturn(false);

    PatchOperation operation = new PatchOperation();
    operation.setOperation(PatchOperation.Type.REPLACE);
    operation.setPath(PatchOperationPath.fromString("members[value eq \"3\"]"));
    operation.setValue(Map.of("value", "4"));

    ResourceException exception =
        assertThrows(
            ResourceException.class,
            () -> adapter.patch(GROUP_SCIM_ID, null, List.of(operation), null, null));
    assertEquals(404, exception.getStatus());
  }

  @Test
  void testPatchReplaceExternalId() throws Exception {
    ScimGroupMeta group = ScimServiceTestEntities.group(GROUP_ID, "engineering", GROUP_EXT_ID);
    when(groupManager.getGroup(GROUP_ID)).thenReturn(group);

    PatchOperation operation = new PatchOperation();
    operation.setOperation(PatchOperation.Type.REPLACE);
    operation.setValue(Map.of("externalId", "group-2"));

    ResourceException exception =
        assertThrows(
            ResourceException.class,
            () -> adapter.patch(GROUP_SCIM_ID, null, List.of(operation), null, null));
    assertEquals(400, exception.getStatus());
    assertEquals("Group PATCH supports members only", exception.getMessage());
  }

  @Test
  void testPatchReplaceExternalIdByPath() throws Exception {
    ScimGroupMeta group = ScimServiceTestEntities.group(GROUP_ID, "engineering", GROUP_EXT_ID);
    when(groupManager.getGroup(GROUP_ID)).thenReturn(group);

    PatchOperation operation = new PatchOperation();
    operation.setOperation(PatchOperation.Type.REPLACE);
    operation.setPath(PatchOperationPath.fromString("externalId"));
    operation.setValue("group-2");

    ResourceException exception =
        assertThrows(
            ResourceException.class,
            () -> adapter.patch(GROUP_SCIM_ID, null, List.of(operation), null, null));
    assertEquals(400, exception.getStatus());
    assertEquals("Group PATCH supports members only", exception.getMessage());
  }

  @Test
  void testPatchUnchangedExternalIdSkipsAlter() throws Exception {
    ScimGroupMeta group = ScimServiceTestEntities.group(GROUP_ID, "engineering", GROUP_EXT_ID);
    when(groupManager.getGroup(GROUP_ID)).thenReturn(group);

    PatchOperation operation = new PatchOperation();
    operation.setOperation(PatchOperation.Type.REPLACE);
    operation.setValue(Map.of("externalId", GROUP_EXT_ID));

    ResourceException exception =
        assertThrows(
            ResourceException.class,
            () -> adapter.patch(GROUP_SCIM_ID, null, List.of(operation), null, null));
    assertEquals(400, exception.getStatus());
  }

  @Test
  void testPatchRenameDisplayName400() throws Exception {
    ScimGroupMeta group = ScimServiceTestEntities.group(GROUP_ID, "engineering", GROUP_EXT_ID);
    when(groupManager.getGroup(GROUP_ID)).thenReturn(group);

    PatchOperation operation = new PatchOperation();
    operation.setOperation(PatchOperation.Type.REPLACE);
    operation.setValue(Map.of("displayName", "renamed"));

    ResourceException exception =
        assertThrows(
            ResourceException.class,
            () -> adapter.patch(GROUP_SCIM_ID, null, List.of(operation), null, null));
    assertEquals(400, exception.getStatus());
    assertEquals("Group displayName is immutable", exception.getMessage());
  }

  @Test
  void testPatchDisplayNameCaseOnlyNoOp() throws Exception {
    ScimGroupMeta group = ScimServiceTestEntities.group(GROUP_ID, "engineering", GROUP_EXT_ID);
    when(groupManager.getGroup(GROUP_ID)).thenReturn(group);
    when(membershipManager.listMembersForGroup(GROUP_ID)).thenReturn(List.of());

    PatchOperation operation = new PatchOperation();
    operation.setOperation(PatchOperation.Type.REPLACE);
    operation.setValue(Map.of("displayName", "Engineering"));

    ScimGroup patched = adapter.patch(GROUP_SCIM_ID, null, List.of(operation), null, null);
    assertEquals("engineering", patched.getDisplayName());
  }

  @Test
  void testFindByDisplayNameIgnoreCaseMiss() throws Exception {
    when(groupManager.findGroupByGroupNameIgnoreCase("ops")).thenReturn(null);

    var response =
        adapter.find(
            Filter.decode("displayName eq \"ops\""),
            new PageRequest().setStartIndex(1).setCount(10),
            null);
    assertEquals(0, response.getTotalResults());
  }

  @Test
  void testPatchMembersReplace() throws Exception {
    ScimGroupMeta group = ScimServiceTestEntities.group(GROUP_ID, "engineering", GROUP_EXT_ID);
    when(groupManager.getGroup(GROUP_ID)).thenReturn(group);
    when(membershipManager.listMembersForGroup(GROUP_ID)).thenReturn(List.of());

    PatchOperation operation = new PatchOperation();
    operation.setOperation(PatchOperation.Type.REPLACE);
    operation.setValue(
        List.of(new GroupMembership().setValue(USER_SCIM_ID), new GroupMembership().setValue("4")));

    ScimGroup patched = adapter.patch(GROUP_SCIM_ID, null, List.of(operation), null, null);
    assertEquals(GROUP_SCIM_ID, patched.getId());
    verify(membershipManager).replaceUsersInGroup(eq(GROUP_ID), eq(List.of(USER_ID, 4L)));
  }

  @Test
  void testPatchMembersInvalidPath() throws Exception {
    ScimGroupMeta group = ScimServiceTestEntities.group(GROUP_ID, "engineering", GROUP_EXT_ID);
    when(groupManager.getGroup(GROUP_ID)).thenReturn(group);

    PatchOperation operation = new PatchOperation();
    operation.setOperation(PatchOperation.Type.REPLACE);
    operation.setPath(PatchOperationPath.fromString("roles"));
    operation.setValue("other");

    ResourceException exception =
        assertThrows(
            ResourceException.class,
            () -> adapter.patch(GROUP_SCIM_ID, null, List.of(operation), null, null));
    assertEquals(400, exception.getStatus());
  }

  @Test
  void testFindByDisplayNameIgnoreCase() throws Exception {
    ScimGroupMeta group = ScimServiceTestEntities.group(1L, "Engineers", GROUP_EXT_G1);
    when(groupManager.findGroupByGroupNameIgnoreCase("engineers")).thenReturn(group);
    when(membershipManager.listMembersForGroup(1L)).thenReturn(List.of());

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
    ScimGroupMeta group = ScimServiceTestEntities.group(GROUP_ID, "engineering", GROUP_EXT_ID);
    when(groupManager.getGroup(GROUP_ID)).thenReturn(group);
    when(membershipManager.listMembersForGroup(GROUP_ID))
        .thenReturn(
            List.of(
                ScimGroupMemberPO.builder()
                    .withUserId(USER_ID)
                    .withUserName("alice")
                    .withExternalId("member-ext")
                    .build()));

    ScimGroup updated =
        adapter.update(
            GROUP_SCIM_ID,
            null,
            new ScimGroup()
                .setId(GROUP_SCIM_ID)
                .setExternalId(GROUP_EXT_ID)
                .setDisplayName("engineering")
                .setMembers(List.of(new GroupMembership().setValue(USER_SCIM_ID))),
            null,
            null);

    assertEquals(GROUP_SCIM_ID, updated.getId());
    assertEquals(1, updated.getMembers().size());
    assertEquals(USER_SCIM_ID, updated.getMembers().get(0).getValue());
    verify(membershipManager).replaceUsersInGroup(eq(GROUP_ID), eq(List.of(USER_ID)));
  }

  @Test
  void testUpdateEmptyMembers() throws Exception {
    ScimGroupMeta group = ScimServiceTestEntities.group(GROUP_ID, "engineering", GROUP_EXT_ID);
    when(groupManager.getGroup(GROUP_ID)).thenReturn(group);
    when(membershipManager.listMembersForGroup(GROUP_ID)).thenReturn(List.of());

    ScimGroup updated =
        adapter.update(
            GROUP_SCIM_ID,
            null,
            new ScimGroup()
                .setExternalId(GROUP_EXT_ID)
                .setDisplayName("engineering")
                .setMembers(List.of()),
            null,
            null);

    assertEquals(GROUP_SCIM_ID, updated.getId());
    assertEquals(0, updated.getMembers().size());
    verify(membershipManager).replaceUsersInGroup(eq(GROUP_ID), eq(List.of()));
  }

  @Test
  void testUpdateSameName() throws Exception {
    ScimGroupMeta group = ScimServiceTestEntities.group(GROUP_ID, "engineering", GROUP_EXT_ID);
    when(groupManager.getGroup(GROUP_ID)).thenReturn(group);
    when(membershipManager.listMembersForGroup(GROUP_ID)).thenReturn(List.of());

    ScimGroup updated =
        adapter.update(
            GROUP_SCIM_ID,
            null,
            new ScimGroup().setDisplayName("engineering").setMembers(List.of()),
            null,
            null);

    assertEquals("engineering", updated.getDisplayName());
    verify(membershipManager).replaceUsersInGroup(eq(GROUP_ID), eq(List.of()));
  }

  @Test
  void testUpdateDisplayNameCaseOnlyNoOp() throws Exception {
    ScimGroupMeta group = ScimServiceTestEntities.group(GROUP_ID, "engineering", GROUP_EXT_ID);
    when(groupManager.getGroup(GROUP_ID)).thenReturn(group);
    when(membershipManager.listMembersForGroup(GROUP_ID)).thenReturn(List.of());

    ScimGroup updated =
        adapter.update(
            GROUP_SCIM_ID,
            null,
            new ScimGroup().setDisplayName("Engineering").setMembers(List.of()),
            null,
            null);

    assertEquals("engineering", updated.getDisplayName());
    verify(membershipManager).replaceUsersInGroup(eq(GROUP_ID), eq(List.of()));
  }

  @Test
  void testUpdateNoName() throws Exception {
    ScimGroupMeta group = ScimServiceTestEntities.group(GROUP_ID, "engineering", GROUP_EXT_ID);
    when(groupManager.getGroup(GROUP_ID)).thenReturn(group);
    when(membershipManager.listMembersForGroup(GROUP_ID)).thenReturn(List.of());

    ScimGroup updated =
        adapter.update(GROUP_SCIM_ID, null, new ScimGroup().setMembers(List.of()), null, null);

    assertEquals("engineering", updated.getDisplayName());
    verify(membershipManager).replaceUsersInGroup(eq(GROUP_ID), eq(List.of()));
  }

  @Test
  void testUpdateRename400() throws Exception {
    ScimGroupMeta group = ScimServiceTestEntities.group(GROUP_ID, "engineering", GROUP_EXT_ID);
    when(groupManager.getGroup(GROUP_ID)).thenReturn(group);

    ResourceException exception =
        assertThrows(
            ResourceException.class,
            () ->
                adapter.update(
                    GROUP_SCIM_ID,
                    null,
                    new ScimGroup().setDisplayName("renamed").setMembers(List.of()),
                    null,
                    null));
    assertEquals(400, exception.getStatus());
    verify(membershipManager, never()).replaceUsersInGroup(anyLong(), any());
  }

  @Test
  void testUpdateExtId400() throws Exception {
    ScimGroupMeta group = ScimServiceTestEntities.group(GROUP_ID, "engineering", GROUP_EXT_ID);
    when(groupManager.getGroup(GROUP_ID)).thenReturn(group);

    ResourceException exception =
        assertThrows(
            ResourceException.class,
            () ->
                adapter.update(
                    GROUP_SCIM_ID,
                    null,
                    new ScimGroup()
                        .setExternalId("other-ext")
                        .setDisplayName("engineering")
                        .setMembers(List.of()),
                    null,
                    null));
    assertEquals(400, exception.getStatus());
    verify(membershipManager, never()).replaceUsersInGroup(anyLong(), any());
  }

  @Test
  void testUpdateId400() throws Exception {
    ScimGroupMeta group = ScimServiceTestEntities.group(GROUP_ID, "engineering", GROUP_EXT_ID);
    when(groupManager.getGroup(GROUP_ID)).thenReturn(group);

    ResourceException exception =
        assertThrows(
            ResourceException.class,
            () ->
                adapter.update(
                    GROUP_SCIM_ID,
                    null,
                    new ScimGroup()
                        .setId("other-id")
                        .setDisplayName("engineering")
                        .setMembers(List.of()),
                    null,
                    null));
    assertEquals(400, exception.getStatus());
    verify(membershipManager, never()).replaceUsersInGroup(anyLong(), any());
  }

  @Test
  void testUpdate404() {
    ResourceException exception =
        assertThrows(
            ResourceException.class,
            () ->
                adapter.update(
                    "missing", null, new ScimGroup().setDisplayName("engineering"), null, null));
    assertEquals(404, exception.getStatus());
  }

  @Test
  void testDelete() throws Exception {
    when(groupManager.deleteGroup(1L)).thenReturn(true);
    adapter.delete("1");
    verify(groupManager).deleteGroup(1L);
  }

  @Test
  void testDelete404() {
    ResourceException exception =
        assertThrows(ResourceException.class, () -> adapter.delete("missing"));
    assertEquals(404, exception.getStatus());
  }

  @Test
  void testFindById() throws Exception {
    ScimGroupMeta group = ScimServiceTestEntities.group(1L, "engineers", GROUP_EXT_G1);
    when(groupManager.getGroup(1L)).thenReturn(group);
    when(membershipManager.listMembersForGroup(1L)).thenReturn(List.of());

    var response =
        adapter.find(
            Filter.decode("id eq \"1\""), new PageRequest().setStartIndex(1).setCount(10), null);
    assertEquals(1, response.getTotalResults());
    assertEquals("1", response.getResources().iterator().next().getId());
  }

  @Test
  void testFindByIdMissing() throws Exception {
    when(groupManager.getGroup(999L)).thenThrow(new NotFoundException("999"));

    var response =
        adapter.find(
            Filter.decode("id eq \"999\""), new PageRequest().setStartIndex(1).setCount(10), null);
    assertEquals(0, response.getTotalResults());
    assertEquals(0, response.getResources().size());
  }

  @Test
  void testFindByDisplayName() throws Exception {
    ScimGroupMeta group = ScimServiceTestEntities.group(1L, "engineers", GROUP_EXT_G1);
    when(groupManager.findGroupByGroupNameIgnoreCase("engineers")).thenReturn(group);
    when(membershipManager.listMembersForGroup(1L)).thenReturn(List.of());

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
    ScimGroupMeta group = ScimServiceTestEntities.group(1L, "engineers", GROUP_EXT_G1);
    when(groupManager.getGroupByExternalId(GROUP_EXT_G1)).thenReturn(group);
    when(membershipManager.listMembersForGroup(1L)).thenReturn(List.of());

    var response =
        adapter.find(
            Filter.decode("externalId eq \"ext-g1\""),
            new PageRequest().setStartIndex(1).setCount(10),
            null);
    assertEquals(1, response.getTotalResults());
    assertEquals("1", response.getResources().iterator().next().getId());
    assertEquals(GROUP_EXT_G1, response.getResources().iterator().next().getExternalId());
  }

  @Test
  void testFindAndMismatch() throws Exception {
    ScimGroupMeta group = ScimServiceTestEntities.group(1L, "engineers", GROUP_EXT_G1);
    when(groupManager.getGroupByExternalId(GROUP_EXT_G1)).thenReturn(group);

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
    when(groupManager.listGroups(0, 10)).thenReturn(new PagedResult<>(0, List.of()));
    var response = adapter.find(null, new PageRequest().setStartIndex(1).setCount(10), null);
    assertEquals(0, response.getTotalResults());
    assertEquals(0, response.getResources().size());
    verify(groupManager).listGroups(0, 10);
  }

  @Test
  void testFindUnfilteredPaged() throws Exception {
    ScimGroupMeta engineers = ScimServiceTestEntities.group(GROUP_ID, "engineers", GROUP_EXT_G1);
    ScimGroupMeta ops = ScimServiceTestEntities.group(4L, "ops", "ext-g2");
    when(groupManager.listGroups(0, 10)).thenReturn(new PagedResult<>(2, List.of(engineers, ops)));
    when(membershipManager.listMembersForGroups(eq(List.of(GROUP_ID, 4L))))
        .thenReturn(Map.of(GROUP_ID, List.of(), 4L, List.of()));

    var response = adapter.find(null, new PageRequest().setStartIndex(1).setCount(10), null);

    assertEquals(2, response.getTotalResults());
    assertEquals(2, response.getResources().size());
    var groups = response.getResources().toArray(new ScimGroup[0]);
    assertEquals("engineers", groups[0].getDisplayName());
    assertEquals("ops", groups[1].getDisplayName());
    verify(groupManager).listGroups(0, 10);
    verify(membershipManager, times(1)).listMembersForGroups(eq(List.of(GROUP_ID, 4L)));
    verify(membershipManager, never()).listMembersForGroup(anyLong());
  }

  @Test
  void testGet404() throws Exception {
    assertThrows(ResourceException.class, () -> adapter.get("missing"));
  }

  @Test
  void testGetWithMembers() throws Exception {
    ScimGroupMeta group = ScimServiceTestEntities.group(1L, "engineers", GROUP_EXT_G1);
    when(groupManager.getGroup(1L)).thenReturn(group);
    when(membershipManager.listMembersForGroup(1L))
        .thenReturn(
            List.of(
                ScimGroupMemberPO.builder()
                    .withUserId(1L)
                    .withUserName("alice")
                    .withExternalId("member-1")
                    .build()));

    ScimGroup result = adapter.get("1");
    assertEquals(1, result.getMembers().size());
    assertEquals("1", result.getMembers().get(0).getValue());
  }

  @Test
  void testCreateThroughEventDispatcherEmitsScimEventsOnly() throws Exception {
    ScimGroupManager manager = mock(ScimGroupManager.class);
    ScimGroupMeta created = ScimServiceTestEntities.group(GROUP_ID, "engineering", "ext-1");
    when(manager.createGroup("engineering", "ext-1")).thenReturn(created);
    when(membershipManager.listMembersForGroup(GROUP_ID)).thenReturn(List.of());

    EventBus eventBus = mock(EventBus.class);
    ScimGroupEventDispatcher scimDispatcher =
        new ScimGroupEventDispatcher(
            eventBus, new ScimGroupRepositoryAdapter(manager, membershipManager, scimConfig));

    ScimGroup result =
        scimDispatcher.create(new ScimGroup().setDisplayName("engineering").setExternalId("ext-1"));
    assertEquals(GROUP_SCIM_ID, result.getId());

    ArgumentCaptor<BaseEvent> captor = ArgumentCaptor.forClass(BaseEvent.class);
    verify(eventBus, times(2)).dispatchEvent(captor.capture());
    List<BaseEvent> events = captor.getAllValues();
    assertInstanceOf(ScimAddGroupPreEvent.class, events.get(0));
    assertInstanceOf(ScimAddGroupEvent.class, events.get(1));
  }

  @Test
  void testUpdateThroughEventDispatcherEmitsScimEventsOnly() throws Exception {
    ScimGroupManager manager = mock(ScimGroupManager.class);
    ScimGroupMeta existing = ScimServiceTestEntities.group(GROUP_ID, "engineering", "ext-1");
    when(manager.getGroup(GROUP_ID)).thenReturn(existing);
    when(membershipManager.listMembersForGroup(GROUP_ID)).thenReturn(List.of());

    EventBus eventBus = mock(EventBus.class);
    ScimGroupEventDispatcher scimDispatcher =
        new ScimGroupEventDispatcher(
            eventBus, new ScimGroupRepositoryAdapter(manager, membershipManager, scimConfig));

    ScimGroup result =
        scimDispatcher.update(
            GROUP_SCIM_ID,
            null,
            new ScimGroup()
                .setId(GROUP_SCIM_ID)
                .setExternalId("ext-1")
                .setDisplayName("engineering"),
            null,
            null);
    assertEquals("engineering", result.getDisplayName());

    ArgumentCaptor<BaseEvent> captor = ArgumentCaptor.forClass(BaseEvent.class);
    verify(eventBus, times(2)).dispatchEvent(captor.capture());
    List<BaseEvent> events = captor.getAllValues();
    assertInstanceOf(ScimAlterGroupPreEvent.class, events.get(0));
    assertInstanceOf(ScimAlterGroupEvent.class, events.get(1));
  }

  @Test
  void testDeleteThroughEventDispatcherEmitsScimEventsOnly() throws Exception {
    ScimGroupManager manager = mock(ScimGroupManager.class);
    ScimGroupMeta existing = ScimServiceTestEntities.group(GROUP_ID, "engineering", "ext-1");
    when(manager.getGroup(GROUP_ID)).thenReturn(existing);
    when(manager.deleteGroup(GROUP_ID)).thenReturn(true);
    when(membershipManager.listMembersForGroup(GROUP_ID)).thenReturn(List.of());

    EventBus eventBus = mock(EventBus.class);
    ScimGroupEventDispatcher scimDispatcher =
        new ScimGroupEventDispatcher(
            eventBus, new ScimGroupRepositoryAdapter(manager, membershipManager, scimConfig));

    scimDispatcher.delete(GROUP_SCIM_ID);

    ArgumentCaptor<BaseEvent> captor = ArgumentCaptor.forClass(BaseEvent.class);
    verify(eventBus, times(2)).dispatchEvent(captor.capture());
    List<BaseEvent> events = captor.getAllValues();
    assertInstanceOf(ScimRemoveGroupPreEvent.class, events.get(0));
    assertInstanceOf(ScimRemoveGroupEvent.class, events.get(1));
    assertEquals("engineering", ((ScimRemoveGroupEvent) events.get(1)).groupName());
    verify(manager).getGroup(GROUP_ID);
  }
}
