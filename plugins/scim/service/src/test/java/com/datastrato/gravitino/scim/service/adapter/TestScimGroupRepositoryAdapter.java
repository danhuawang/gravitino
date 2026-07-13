/*
 * Copyright 2026 Datastrato Pvt Ltd.
 * This software is licensed under the Apache License version 2.
 */

package com.datastrato.gravitino.scim.service.adapter;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.datastrato.gravitino.scim.ScimUserGroupRelManager;
import com.datastrato.gravitino.scim.service.ScimConfig;
import com.datastrato.gravitino.scim.service.ScimServiceTestEntities;
import com.datastrato.gravitino.scim.service.web.ScimMetalakeContext;
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
import org.apache.gravitino.authorization.User;
import org.apache.gravitino.exceptions.GroupAlreadyExistsException;
import org.apache.gravitino.exceptions.NoSuchGroupException;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class TestScimGroupRepositoryAdapter {

  private static final String METALAKE = "test_metalake";

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
  void testCreateNoExtId() {
    assertThrows(ResourceException.class, () -> adapter.create(new ScimGroup()));
  }

  @Test
  void testCreateIdempotent() throws Exception {
    Group existing = ScimServiceTestEntities.group(1L, "engineers", "ext-g1");
    when(dispatcher.getGroupByExternalId(METALAKE, "ext-g1")).thenReturn(existing);
    when(membershipManager.listUsernamesForGroup(METALAKE, "ext-g1")).thenReturn(List.of());

    ScimGroup created =
        adapter.create(
            new ScimGroup()
                .setExternalId("ext-g1")
                .setDisplayName("engineers")
                .setMembers(List.of(new GroupMembership().setValue("user-1"))));
    assertEquals("ext-g1", created.getId());
    assertEquals("engineers", created.getDisplayName());
    verify(membershipManager, never()).replaceUsersInGroup(any(), any(), any());
  }

  @Test
  void testCreateConflict409() throws Exception {
    when(dispatcher.getGroupByExternalId(METALAKE, "ext-g1"))
        .thenThrow(new NoSuchGroupException("ext-g1"));
    when(dispatcher.addGroup(METALAKE, "engineers", "ext-g1"))
        .thenThrow(new GroupAlreadyExistsException("engineers"));

    ResourceException exception =
        assertThrows(
            ResourceException.class,
            () ->
                adapter.create(
                    new ScimGroup().setExternalId("ext-g1").setDisplayName("engineers")));
    assertEquals(409, exception.getStatus());
  }

  @Test
  void testCreateGroupWithMembers() throws Exception {
    Group group = ScimServiceTestEntities.group(2L, "engineering", "group-1");
    GroupMembership membership = new GroupMembership().setValue("user-1");
    User member = ScimServiceTestEntities.user(3L, "alice", "user-1", true);
    when(dispatcher.getGroupByExternalId(METALAKE, "group-1"))
        .thenThrow(new NoSuchGroupException("group-1"));
    when(dispatcher.addGroup(METALAKE, "engineering", "group-1")).thenReturn(group);
    when(membershipManager.listUsernamesForGroup(METALAKE, "group-1")).thenReturn(List.of("alice"));
    when(dispatcher.getUser(METALAKE, "alice")).thenReturn(member);

    ScimGroup created =
        adapter.create(
            new ScimGroup()
                .setExternalId("group-1")
                .setDisplayName("engineering")
                .setMembers(List.of(membership)));
    assertEquals("group-1", created.getId());
    assertEquals(1, created.getMembers().size());
    verify(dispatcher).addGroup(METALAKE, "engineering", "group-1");
    verify(membershipManager)
        .replaceUsersInGroup(eq(METALAKE), eq("group-1"), eq(List.of("user-1")));
  }

  @Test
  void testCreateGroup() throws Exception {
    Group created = ScimServiceTestEntities.group(1L, "engineers", "ext-g1");
    when(dispatcher.getGroupByExternalId(METALAKE, "ext-g1"))
        .thenThrow(new NoSuchGroupException("ext-g1"));
    when(dispatcher.addGroup(METALAKE, "engineers", "ext-g1")).thenReturn(created);
    when(membershipManager.listUsernamesForGroup(METALAKE, "ext-g1")).thenReturn(List.of());

    ScimGroup result =
        adapter.create(
            new ScimGroup()
                .setExternalId("ext-g1")
                .setDisplayName("engineers")
                .setMembers(List.of()));
    assertEquals("ext-g1", result.getId());
    verify(dispatcher).addGroup(METALAKE, "engineers", "ext-g1");
  }

  @Test
  void testPatchMembersAdd() throws Exception {
    Group group = ScimServiceTestEntities.group(2L, "engineering", "group-1");
    when(dispatcher.getGroupByExternalId(METALAKE, "group-1")).thenReturn(group);
    when(membershipManager.listUsernamesForGroup(METALAKE, "group-1")).thenReturn(List.of("alice"));
    when(dispatcher.getUser(METALAKE, "alice"))
        .thenReturn(ScimServiceTestEntities.user(3L, "alice", "user-1", true));

    PatchOperation operation = new PatchOperation();
    operation.setOperation(PatchOperation.Type.ADD);
    operation.setValue(List.of(new GroupMembership().setValue("user-1")));

    ScimGroup patched = adapter.patch("group-1", null, List.of(operation), null, null);
    assertEquals("group-1", patched.getId());
    verify(membershipManager).addUsersToGroup(eq(METALAKE), eq("group-1"), eq(List.of("user-1")));
  }

  @Test
  void testPatchMembersRemove() throws Exception {
    Group group = ScimServiceTestEntities.group(2L, "engineering", "group-1");
    when(dispatcher.getGroupByExternalId(METALAKE, "group-1")).thenReturn(group);
    when(membershipManager.listUsernamesForGroup(METALAKE, "group-1")).thenReturn(List.of());

    PatchOperation operation = new PatchOperation();
    operation.setOperation(PatchOperation.Type.REMOVE);
    operation.setValue(List.of(new GroupMembership().setValue("user-1")));

    ScimGroup patched = adapter.patch("group-1", null, List.of(operation), null, null);
    assertEquals("group-1", patched.getId());
    verify(membershipManager)
        .removeUsersFromGroup(eq(METALAKE), eq("group-1"), eq(List.of("user-1")));
  }

  @Test
  void testPatchMembersReplace() throws Exception {
    Group group = ScimServiceTestEntities.group(2L, "engineering", "group-1");
    when(dispatcher.getGroupByExternalId(METALAKE, "group-1")).thenReturn(group);
    when(membershipManager.listUsernamesForGroup(METALAKE, "group-1")).thenReturn(List.of());

    PatchOperation operation = new PatchOperation();
    operation.setOperation(PatchOperation.Type.REPLACE);
    operation.setValue(
        List.of(
            new GroupMembership().setValue("user-1"), new GroupMembership().setValue("user-2")));

    ScimGroup patched = adapter.patch("group-1", null, List.of(operation), null, null);
    assertEquals("group-1", patched.getId());
    verify(membershipManager)
        .replaceUsersInGroup(eq(METALAKE), eq("group-1"), eq(List.of("user-1", "user-2")));
  }

  @Test
  void testPatchMembersInvalidPath() throws Exception {
    Group group = ScimServiceTestEntities.group(2L, "engineering", "group-1");
    when(dispatcher.getGroupByExternalId(METALAKE, "group-1")).thenReturn(group);

    PatchOperation operation = new PatchOperation();
    operation.setOperation(PatchOperation.Type.REPLACE);
    operation.setPath(PatchOperationPath.fromString("displayName"));
    operation.setValue("other");

    assertThrows(
        ResourceException.class,
        () -> adapter.patch("group-1", null, List.of(operation), null, null));
  }

  @Test
  void testUpdate405() {
    assertThrows(
        ResourceException.class,
        () -> adapter.update("group-1", null, new ScimGroup(), null, null));
  }

  @Test
  void testDelete() throws Exception {
    when(dispatcher.removeGroupByExternalId(METALAKE, "ext-g1")).thenReturn(true);
    adapter.delete("ext-g1");
    verify(dispatcher).removeGroupByExternalId(eq(METALAKE), eq("ext-g1"));
  }

  @Test
  void testDelete404() {
    when(dispatcher.removeGroupByExternalId(METALAKE, "missing")).thenReturn(false);
    ResourceException exception =
        assertThrows(ResourceException.class, () -> adapter.delete("missing"));
    assertEquals(404, exception.getStatus());
  }

  @Test
  void testFindByDisplayName() throws Exception {
    Group group = ScimServiceTestEntities.group(1L, "engineers", "ext-g1");
    when(dispatcher.getGroup(METALAKE, "engineers")).thenReturn(group);
    when(membershipManager.listUsernamesForGroup(METALAKE, "ext-g1")).thenReturn(List.of());

    var response =
        adapter.find(
            Filter.decode("displayName eq \"engineers\""),
            new PageRequest().setStartIndex(1).setCount(10),
            null);
    assertEquals(1, response.getTotalResults());
    assertEquals("engineers", response.getResources().iterator().next().getDisplayName());
  }

  @Test
  void testFindByExternalId() throws Exception {
    Group group = ScimServiceTestEntities.group(1L, "engineers", "ext-g1");
    when(dispatcher.getGroupByExternalId(METALAKE, "ext-g1")).thenReturn(group);
    when(membershipManager.listUsernamesForGroup(METALAKE, "ext-g1")).thenReturn(List.of());

    var response =
        adapter.find(
            Filter.decode("externalId eq \"ext-g1\""),
            new PageRequest().setStartIndex(1).setCount(10),
            null);
    assertEquals(1, response.getTotalResults());
    assertEquals("ext-g1", response.getResources().iterator().next().getId());
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
    when(dispatcher.getGroupByExternalId(eq(METALAKE), eq("missing")))
        .thenThrow(new NoSuchGroupException("missing"));
    assertThrows(ResourceException.class, () -> adapter.get("missing"));
  }

  @Test
  void testGetWithMembers() throws Exception {
    Group group = ScimServiceTestEntities.group(1L, "engineers", "ext-g1");
    User member = ScimServiceTestEntities.user(1L, "alice", "ext-u1", true);
    when(dispatcher.getGroupByExternalId(METALAKE, "ext-g1")).thenReturn(group);
    when(membershipManager.listUsernamesForGroup(METALAKE, "ext-g1")).thenReturn(List.of("alice"));
    when(dispatcher.getUser(METALAKE, "alice")).thenReturn(member);

    ScimGroup result = adapter.get("ext-g1");
    assertEquals(1, result.getMembers().size());
    assertEquals("ext-u1", result.getMembers().get(0).getValue());
  }
}
