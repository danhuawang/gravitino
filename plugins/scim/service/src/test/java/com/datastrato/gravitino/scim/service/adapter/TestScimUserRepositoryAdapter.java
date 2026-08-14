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
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.datastrato.gravitino.scim.service.ScimConfig;
import com.datastrato.gravitino.scim.service.ScimServiceTestEntities;
import com.datastrato.gravitino.scim.service.listener.ScimUserEventDispatcher;
import com.datastrato.gravitino.scim.service.web.ScimMetalakeContext;
import java.util.List;
import java.util.Map;
import org.apache.directory.scim.spec.exception.ResourceException;
import org.apache.directory.scim.spec.filter.PageRequest;
import org.apache.directory.scim.spec.patch.PatchOperation;
import org.apache.directory.scim.spec.patch.PatchOperationPath;
import org.apache.directory.scim.spec.resources.ScimUser;
import org.apache.gravitino.Config;
import org.apache.gravitino.authorization.AccessControlDispatcher;
import org.apache.gravitino.authorization.PagedResult;
import org.apache.gravitino.authorization.User;
import org.apache.gravitino.authorization.UserChange;
import org.apache.gravitino.exceptions.NoSuchUserException;
import org.apache.gravitino.exceptions.UserAlreadyExistsException;
import org.apache.gravitino.listener.AccessControlEventDispatcher;
import org.apache.gravitino.listener.EventBus;
import org.apache.gravitino.listener.api.event.AddUserEvent;
import org.apache.gravitino.listener.api.event.AlterUserEvent;
import org.apache.gravitino.listener.api.event.BaseEvent;
import org.apache.gravitino.listener.api.event.GetUserByIdEvent;
import org.apache.gravitino.listener.api.event.RemoveUserEvent;
import org.apache.gravitino.listener.api.event.scim.ScimAddUserEvent;
import org.apache.gravitino.listener.api.event.scim.ScimAddUserPreEvent;
import org.apache.gravitino.listener.api.event.scim.ScimAlterUserEvent;
import org.apache.gravitino.listener.api.event.scim.ScimAlterUserPreEvent;
import org.apache.gravitino.listener.api.event.scim.ScimRemoveUserEvent;
import org.apache.gravitino.listener.api.event.scim.ScimRemoveUserPreEvent;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

class TestScimUserRepositoryAdapter {

  private static final String METALAKE = "test_metalake";
  private static final long USER_ID = 1L;

  private AccessControlDispatcher dispatcher;
  private ScimUserRepositoryAdapter adapter;
  private ScimConfig scimConfig;

  @BeforeEach
  void setUp() {
    scimConfig = new ScimConfig(Map.of(), new Config() {});
    dispatcher = mock(AccessControlDispatcher.class);
    when(dispatcher.listUsers(METALAKE)).thenReturn(new User[0]);
    adapter = new ScimUserRepositoryAdapter(dispatcher, scimConfig);
    ScimMetalakeContext.setMetalake(METALAKE);
  }

  @AfterEach
  void tearDown() {
    ScimMetalakeContext.clear();
  }

  @Test
  void testCreateNoUserName() {
    ResourceException exception =
        assertThrows(ResourceException.class, () -> adapter.create(new ScimUser()));
    assertEquals(400, exception.getStatus());
  }

  @Test
  void testCreateWithoutExternalId() throws Exception {
    User created = ScimServiceTestEntities.user(USER_ID, "alice", null, true);
    when(dispatcher.addUser(METALAKE, "alice", null, true)).thenReturn(created);

    ScimUser result = adapter.create(new ScimUser().setUserName("alice"));
    assertEquals("1", result.getId());
    assertNull(result.getExternalId());
    verify(dispatcher).addUser(METALAKE, "alice", null, true);
  }

  @Test
  void testCreateAdd() throws Exception {
    User created = ScimServiceTestEntities.user(USER_ID, "alice", "ext-1", true);
    when(dispatcher.addUser(METALAKE, "alice", "ext-1", true)).thenReturn(created);

    ScimUser result = adapter.create(new ScimUser().setExternalId("ext-1").setUserName("alice"));
    assertEquals("1", result.getId());
    assertEquals("ext-1", result.getExternalId());
    verify(dispatcher).addUser(METALAKE, "alice", "ext-1", true);
  }

  @Test
  void testCreateAddInactive() throws Exception {
    User created = ScimServiceTestEntities.user(USER_ID, "alice", "ext-1", false);
    when(dispatcher.addUser(METALAKE, "alice", "ext-1", false)).thenReturn(created);

    ScimUser result =
        adapter.create(new ScimUser().setExternalId("ext-1").setUserName("alice").setActive(false));
    assertEquals("1", result.getId());
    assertEquals(Boolean.FALSE, result.getActive());
    verify(dispatcher).addUser(METALAKE, "alice", "ext-1", false);
  }

  @Test
  void testCreateConflict409() throws Exception {
    when(dispatcher.addUser(METALAKE, "alice", "ext-1", true))
        .thenThrow(new UserAlreadyExistsException("alice"));

    ResourceException exception =
        assertThrows(
            ResourceException.class,
            () -> adapter.create(new ScimUser().setExternalId("ext-1").setUserName("alice")));
    assertEquals(409, exception.getStatus());
    assertEquals("User already exists: userName=alice, externalId=ext-1", exception.getMessage());
  }

  @Test
  void testCreateConflictIgnoreCase409() throws Exception {
    User existing = ScimServiceTestEntities.user(USER_ID, "alice", "ext-1", true);
    when(dispatcher.listUsers(METALAKE)).thenReturn(new User[] {existing});

    ResourceException exception =
        assertThrows(
            ResourceException.class,
            () -> adapter.create(new ScimUser().setExternalId("ext-2").setUserName("Alice")));
    assertEquals(409, exception.getStatus());
    assertEquals("User already exists: userName=Alice, externalId=ext-2", exception.getMessage());
    verify(dispatcher, never()).addUser(anyString(), anyString(), anyString(), anyBoolean());
  }

  @Test
  void testPatchActive() throws Exception {
    User disabled = ScimServiceTestEntities.user(USER_ID, "alice", "ext-1", false);
    when(dispatcher.alterUserById(METALAKE, USER_ID, UserChange.updateEnabled(false)))
        .thenReturn(disabled);

    PatchOperation operation = new PatchOperation();
    operation.setOperation(PatchOperation.Type.REPLACE);
    operation.setValue(Boolean.FALSE);
    ScimUser patched = adapter.patch("1", null, List.of(operation), null, null);
    assertEquals(Boolean.FALSE, patched.getActive());
    verify(dispatcher).alterUserById(METALAKE, USER_ID, UserChange.updateEnabled(false));
  }

  @Test
  void testPatchActivePath() throws Exception {
    User enabled = ScimServiceTestEntities.user(USER_ID, "alice", "ext-1", true);
    when(dispatcher.alterUserById(METALAKE, USER_ID, UserChange.updateEnabled(true)))
        .thenReturn(enabled);

    PatchOperation operation = new PatchOperation();
    operation.setOperation(PatchOperation.Type.REPLACE);
    operation.setPath(PatchOperationPath.fromString("active"));
    operation.setValue(Boolean.TRUE);
    ScimUser patched = adapter.patch("1", null, List.of(operation), null, null);
    assertEquals(Boolean.TRUE, patched.getActive());
    verify(dispatcher).alterUserById(METALAKE, USER_ID, UserChange.updateEnabled(true));
  }

  @Test
  void testPatchActivePathStr() throws Exception {
    User disabled = ScimServiceTestEntities.user(USER_ID, "alice", "ext-1", false);
    when(dispatcher.alterUserById(METALAKE, USER_ID, UserChange.updateEnabled(false)))
        .thenReturn(disabled);

    PatchOperation operation = new PatchOperation();
    operation.setOperation(PatchOperation.Type.REPLACE);
    operation.setPath(PatchOperationPath.fromString("active"));
    operation.setValue("false");
    ScimUser patched = adapter.patch("1", null, List.of(operation), null, null);
    assertEquals(Boolean.FALSE, patched.getActive());
    verify(dispatcher).alterUserById(METALAKE, USER_ID, UserChange.updateEnabled(false));
  }

  @Test
  void testUpdateDisable() throws Exception {
    User enabled = ScimServiceTestEntities.user(USER_ID, "alice", "ext-1", true);
    User disabled = ScimServiceTestEntities.user(USER_ID, "alice", "ext-1", false);
    when(dispatcher.getUserById(METALAKE, USER_ID)).thenReturn(enabled);
    when(dispatcher.alterUserById(METALAKE, USER_ID, UserChange.updateEnabled(false)))
        .thenReturn(disabled);

    ScimUser updated =
        adapter.update(
            "1",
            null,
            new ScimUser().setId("1").setExternalId("ext-1").setUserName("alice").setActive(false),
            null,
            null);

    assertEquals(Boolean.FALSE, updated.getActive());
    verify(dispatcher).alterUserById(METALAKE, USER_ID, UserChange.updateEnabled(false));
    verify(dispatcher, never())
        .alterUserById(eq(METALAKE), eq(USER_ID), eq(UserChange.updateEnabled(true)));
  }

  @Test
  void testUpdateEnable() throws Exception {
    User disabled = ScimServiceTestEntities.user(USER_ID, "alice", "ext-1", false);
    User enabled = ScimServiceTestEntities.user(USER_ID, "alice", "ext-1", true);
    when(dispatcher.getUserById(METALAKE, USER_ID)).thenReturn(disabled);
    when(dispatcher.alterUserById(METALAKE, USER_ID, UserChange.updateEnabled(true)))
        .thenReturn(enabled);

    ScimUser updated =
        adapter.update("1", null, new ScimUser().setUserName("alice").setActive(true), null, null);

    assertEquals(Boolean.TRUE, updated.getActive());
    verify(dispatcher).alterUserById(METALAKE, USER_ID, UserChange.updateEnabled(true));
  }

  @Test
  void testUpdateSameActive() throws Exception {
    User enabled = ScimServiceTestEntities.user(USER_ID, "alice", "ext-1", true);
    when(dispatcher.getUserById(METALAKE, USER_ID)).thenReturn(enabled);

    ScimUser updated =
        adapter.update("1", null, new ScimUser().setUserName("alice").setActive(true), null, null);

    assertEquals(Boolean.TRUE, updated.getActive());
    verify(dispatcher, never()).alterUserById(anyString(), anyLong(), any());
  }

  @Test
  void testUpdateNoActive() throws Exception {
    User enabled = ScimServiceTestEntities.user(USER_ID, "alice", "ext-1", true);
    when(dispatcher.getUserById(METALAKE, USER_ID)).thenReturn(enabled);

    ScimUser updated = adapter.update("1", null, new ScimUser().setUserName("alice"), null, null);

    assertEquals(Boolean.TRUE, updated.getActive());
    verify(dispatcher, never()).alterUserById(anyString(), anyLong(), any());
  }

  @Test
  void testUpdateNoUserName() throws Exception {
    User enabled = ScimServiceTestEntities.user(USER_ID, "alice", "ext-1", true);
    when(dispatcher.getUserById(METALAKE, USER_ID)).thenReturn(enabled);

    ScimUser updated = adapter.update("1", null, new ScimUser().setActive(true), null, null);

    assertEquals("alice", updated.getUserName());
    verify(dispatcher, never()).alterUserById(anyString(), anyLong(), any());
  }

  @Test
  void testUpdateRename400() throws Exception {
    User user = ScimServiceTestEntities.user(USER_ID, "alice", "ext-1", true);
    when(dispatcher.getUserById(METALAKE, USER_ID)).thenReturn(user);

    ResourceException exception =
        assertThrows(
            ResourceException.class,
            () ->
                adapter.update(
                    "1", null, new ScimUser().setUserName("bob").setActive(true), null, null));
    assertEquals(400, exception.getStatus());
    verify(dispatcher, never()).alterUserById(anyString(), anyLong(), any());
  }

  @Test
  void testUpdateUserNameCaseOnlyNoOp() throws Exception {
    User user = ScimServiceTestEntities.user(USER_ID, "alice", "ext-1", true);
    when(dispatcher.getUserById(METALAKE, USER_ID)).thenReturn(user);

    ScimUser updated =
        adapter.update("1", null, new ScimUser().setUserName("Alice").setActive(true), null, null);

    assertEquals("alice", updated.getUserName());
    verify(dispatcher, never()).alterUserById(anyString(), anyLong(), any());
  }

  @Test
  void testUpdateExtId400() throws Exception {
    User user = ScimServiceTestEntities.user(USER_ID, "alice", "ext-1", true);
    when(dispatcher.getUserById(METALAKE, USER_ID)).thenReturn(user);

    ResourceException exception =
        assertThrows(
            ResourceException.class,
            () ->
                adapter.update(
                    "1",
                    null,
                    new ScimUser().setExternalId("other-ext").setUserName("alice").setActive(true),
                    null,
                    null));
    assertEquals(400, exception.getStatus());
  }

  @Test
  void testUpdateId400() throws Exception {
    User user = ScimServiceTestEntities.user(USER_ID, "alice", "ext-1", true);
    when(dispatcher.getUserById(METALAKE, USER_ID)).thenReturn(user);

    ResourceException exception =
        assertThrows(
            ResourceException.class,
            () ->
                adapter.update(
                    "1",
                    null,
                    new ScimUser().setId("other-id").setUserName("alice").setActive(true),
                    null,
                    null));
    assertEquals(400, exception.getStatus());
  }

  @Test
  void testUpdate404() {
    when(dispatcher.getUserById(METALAKE, 999L)).thenThrow(new NoSuchUserException("999"));

    ResourceException exception =
        assertThrows(
            ResourceException.class,
            () -> adapter.update("999", null, new ScimUser().setUserName("alice"), null, null));
    assertEquals(404, exception.getStatus());
  }

  @Test
  void testDelete() throws Exception {
    when(dispatcher.removeUserById(METALAKE, USER_ID)).thenReturn(true);
    adapter.delete("1");
    verify(dispatcher).removeUserById(eq(METALAKE), eq(USER_ID));
  }

  @Test
  void testDelete404() {
    when(dispatcher.removeUserById(METALAKE, 999L)).thenReturn(false);
    ResourceException exception =
        assertThrows(ResourceException.class, () -> adapter.delete("999"));
    assertEquals(404, exception.getStatus());
  }

  @Test
  void testFindAndMismatch() throws Exception {
    User user = ScimServiceTestEntities.user(USER_ID, "bob", "ext-1", true);
    when(dispatcher.getUserByExternalId(METALAKE, "ext-1")).thenReturn(user);

    var response =
        adapter.find(
            org.apache.directory.scim.spec.filter.Filter.decode(
                "externalId eq \"ext-1\" and userName eq \"alice\""),
            new PageRequest().setStartIndex(1).setCount(10),
            null);
    assertEquals(0, response.getTotalResults());
    assertEquals(0, response.getResources().size());
  }

  @Test
  void testFindByUserName() throws Exception {
    User user = ScimServiceTestEntities.user(USER_ID, "alice", "ext-1", true);
    when(dispatcher.getUser(METALAKE, "alice")).thenReturn(user);

    var response =
        adapter.find(
            org.apache.directory.scim.spec.filter.Filter.decode("userName eq \"alice\""),
            new PageRequest().setStartIndex(1).setCount(10),
            null);
    assertEquals(1, response.getTotalResults());
    assertEquals("alice", response.getResources().iterator().next().getUserName());
    assertEquals("1", response.getResources().iterator().next().getId());
  }

  @Test
  void testFindByUserNameIgnoreCase() throws Exception {
    User user = ScimServiceTestEntities.user(USER_ID, "alice@example.com", "ext-1", true);
    when(dispatcher.getUser(METALAKE, "ALICE@EXAMPLE.COM"))
        .thenThrow(new NoSuchUserException("ALICE@EXAMPLE.COM"));
    when(dispatcher.listUsers(METALAKE)).thenReturn(new User[] {user});

    var response =
        adapter.find(
            org.apache.directory.scim.spec.filter.Filter.decode(
                "userName eq \"ALICE@EXAMPLE.COM\""),
            new PageRequest().setStartIndex(1).setCount(10),
            null);
    assertEquals(1, response.getTotalResults());
    assertEquals("alice@example.com", response.getResources().iterator().next().getUserName());
  }

  @Test
  void testFindByExtId() throws Exception {
    User user = ScimServiceTestEntities.user(USER_ID, "alice", "ext-1", true);
    when(dispatcher.getUserByExternalId(METALAKE, "ext-1")).thenReturn(user);

    var response =
        adapter.find(
            org.apache.directory.scim.spec.filter.Filter.decode("externalId eq \"ext-1\""),
            new PageRequest().setStartIndex(1).setCount(10),
            null);
    assertEquals(1, response.getTotalResults());
    assertEquals("1", response.getResources().iterator().next().getId());
    assertEquals("ext-1", response.getResources().iterator().next().getExternalId());
  }

  @Test
  void testGet404() throws Exception {
    when(dispatcher.getUserById(eq(METALAKE), eq(999L))).thenThrow(new NoSuchUserException("999"));
    assertThrows(ResourceException.class, () -> adapter.get("999"));
  }

  @Test
  void testFindEmpty() throws Exception {
    when(dispatcher.listUsers(METALAKE, 0, 10)).thenReturn(new PagedResult<>(0, List.of()));
    var response = adapter.find(null, new PageRequest().setStartIndex(1).setCount(10), null);
    assertEquals(0, response.getTotalResults());
    assertEquals(0, response.getResources().size());
    verify(dispatcher).listUsers(METALAKE, 0, 10);
  }

  @Test
  void testFindUnfilteredPaged() throws Exception {
    User alice = ScimServiceTestEntities.user(USER_ID, "alice", "ext-1", true);
    User bob = ScimServiceTestEntities.user(2L, "bob", "ext-2", true);
    when(dispatcher.listUsers(METALAKE, 0, 10))
        .thenReturn(new PagedResult<>(2, List.of(alice, bob)));

    var response = adapter.find(null, new PageRequest().setStartIndex(1).setCount(10), null);

    assertEquals(2, response.getTotalResults());
    assertEquals(2, response.getResources().size());
    var users = response.getResources().toArray(new ScimUser[0]);
    assertEquals("alice", users[0].getUserName());
    assertEquals("bob", users[1].getUserName());
    verify(dispatcher).listUsers(METALAKE, 0, 10);
  }

  @Test
  void testCreateThroughInternalDispatcherDoesNotEmitCoreUserEvents() throws Exception {
    AccessControlDispatcher manager = mock(AccessControlDispatcher.class);
    when(manager.listUsers(METALAKE)).thenReturn(new User[0]);
    User created = ScimServiceTestEntities.user(USER_ID, "alice", "ext-1", true);
    when(manager.addUser(METALAKE, "alice", "ext-1", true)).thenReturn(created);

    EventBus eventBus = mock(EventBus.class);
    ScimUserEventDispatcher scimDispatcher = newInternalChainedDispatcher(eventBus, manager);

    ScimUser result =
        scimDispatcher.create(new ScimUser().setUserName("alice").setExternalId("ext-1"));
    assertEquals("1", result.getId());

    ArgumentCaptor<BaseEvent> captor = ArgumentCaptor.forClass(BaseEvent.class);
    verify(eventBus, times(2)).dispatchEvent(captor.capture());
    List<BaseEvent> events = captor.getAllValues();
    assertInstanceOf(ScimAddUserPreEvent.class, events.get(0));
    assertInstanceOf(ScimAddUserEvent.class, events.get(1));
    assertTrue(events.stream().noneMatch(AddUserEvent.class::isInstance));
  }

  @Test
  void testCreateThroughAuditedDispatcherEmitsCoreUserEvents() throws Exception {
    AccessControlDispatcher manager = mock(AccessControlDispatcher.class);
    when(manager.listUsers(METALAKE)).thenReturn(new User[0]);
    User created = ScimServiceTestEntities.user(USER_ID, "alice", "ext-1", true);
    when(manager.addUser(METALAKE, "alice", "ext-1", true)).thenReturn(created);

    EventBus eventBus = mock(EventBus.class);
    ScimUserEventDispatcher scimDispatcher = newAuditedChainedDispatcher(eventBus, manager);

    scimDispatcher.create(new ScimUser().setUserName("alice").setExternalId("ext-1"));

    ArgumentCaptor<BaseEvent> captor = ArgumentCaptor.forClass(BaseEvent.class);
    verify(eventBus, org.mockito.Mockito.atLeastOnce()).dispatchEvent(captor.capture());
    assertTrue(captor.getAllValues().stream().anyMatch(AddUserEvent.class::isInstance));
    assertTrue(captor.getAllValues().stream().anyMatch(ScimAddUserEvent.class::isInstance));
  }

  @Test
  void testUpdateThroughInternalDispatcherDoesNotEmitCoreUserEvents() throws Exception {
    AccessControlDispatcher manager = mock(AccessControlDispatcher.class);
    User enabled = ScimServiceTestEntities.user(USER_ID, "alice", "ext-1", true);
    User disabled = ScimServiceTestEntities.user(USER_ID, "alice", "ext-1", false);
    when(manager.getUserById(METALAKE, USER_ID)).thenReturn(enabled);
    when(manager.alterUserById(anyString(), anyLong(), any())).thenReturn(disabled);

    EventBus eventBus = mock(EventBus.class);
    ScimUserEventDispatcher scimDispatcher = newInternalChainedDispatcher(eventBus, manager);

    ScimUser result =
        scimDispatcher.update(
            "1",
            null,
            new ScimUser().setId("1").setExternalId("ext-1").setUserName("alice").setActive(false),
            null,
            null);
    assertEquals(Boolean.FALSE, result.getActive());

    ArgumentCaptor<BaseEvent> captor = ArgumentCaptor.forClass(BaseEvent.class);
    verify(eventBus, times(2)).dispatchEvent(captor.capture());
    List<BaseEvent> events = captor.getAllValues();
    assertInstanceOf(ScimAlterUserPreEvent.class, events.get(0));
    assertInstanceOf(ScimAlterUserEvent.class, events.get(1));
    assertTrue(events.stream().noneMatch(GetUserByIdEvent.class::isInstance));
    assertTrue(events.stream().noneMatch(AlterUserEvent.class::isInstance));
  }

  @Test
  void testDeleteThroughInternalDispatcherDoesNotEmitCoreUserEvents() throws Exception {
    AccessControlDispatcher manager = mock(AccessControlDispatcher.class);
    User existing = ScimServiceTestEntities.user(USER_ID, "alice", "ext-1", true);
    when(manager.getUserById(METALAKE, USER_ID)).thenReturn(existing);
    when(manager.removeUserById(METALAKE, USER_ID)).thenReturn(true);

    EventBus eventBus = mock(EventBus.class);
    ScimUserEventDispatcher scimDispatcher = newInternalChainedDispatcher(eventBus, manager);

    scimDispatcher.delete("1");

    ArgumentCaptor<BaseEvent> captor = ArgumentCaptor.forClass(BaseEvent.class);
    verify(eventBus, times(2)).dispatchEvent(captor.capture());
    List<BaseEvent> events = captor.getAllValues();
    assertInstanceOf(ScimRemoveUserPreEvent.class, events.get(0));
    assertInstanceOf(ScimRemoveUserEvent.class, events.get(1));
    assertEquals("alice", ((ScimRemoveUserEvent) events.get(1)).userName());
    assertTrue(events.stream().noneMatch(GetUserByIdEvent.class::isInstance));
    assertTrue(events.stream().noneMatch(RemoveUserEvent.class::isInstance));
    // Preload for SCIM audit naming uses getUserById on the internal dispatcher (no core events).
    verify(manager).getUserById(METALAKE, USER_ID);
  }

  private ScimUserEventDispatcher newInternalChainedDispatcher(
      EventBus eventBus, AccessControlDispatcher manager) {
    return new ScimUserEventDispatcher(
        eventBus, new ScimUserRepositoryAdapter(manager, scimConfig));
  }

  private ScimUserEventDispatcher newAuditedChainedDispatcher(
      EventBus eventBus, AccessControlDispatcher manager) {
    AccessControlDispatcher audited = new AccessControlEventDispatcher(eventBus, manager);
    return new ScimUserEventDispatcher(
        eventBus, new ScimUserRepositoryAdapter(audited, scimConfig));
  }
}
