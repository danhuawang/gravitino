/*
 * Copyright 2026 Datastrato Inc.
 */

package com.datastrato.gravitino.scim.service.adapter;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.datastrato.gravitino.scim.ScimUserManager;
import com.datastrato.gravitino.scim.model.ScimUserMeta;
import com.datastrato.gravitino.scim.service.ScimConfig;
import com.datastrato.gravitino.scim.service.ScimServiceTestEntities;
import com.datastrato.gravitino.scim.service.listener.ScimUserEventDispatcher;
import com.datastrato.gravitino.scim.service.web.ScimRequestContext;
import java.util.List;
import java.util.Map;
import org.apache.directory.scim.spec.exception.ResourceException;
import org.apache.directory.scim.spec.filter.PageRequest;
import org.apache.directory.scim.spec.patch.PatchOperation;
import org.apache.directory.scim.spec.patch.PatchOperationPath;
import org.apache.directory.scim.spec.resources.ScimUser;
import org.apache.gravitino.Config;
import org.apache.gravitino.authorization.PagedResult;
import org.apache.gravitino.exceptions.AlreadyExistsException;
import org.apache.gravitino.exceptions.NotFoundException;
import org.apache.gravitino.listener.EventBus;
import org.apache.gravitino.listener.api.event.BaseEvent;
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

  private static final String USER_EXT_ID = "ext-1";
  private static final long USER_ID = 1L;
  private static final String USER_SCIM_ID = String.valueOf(USER_ID);

  private ScimUserManager userManager;
  private ScimUserRepositoryAdapter adapter;
  private ScimConfig scimConfig;

  @BeforeEach
  void setUp() {
    scimConfig = new ScimConfig(Map.of(), new Config() {});
    userManager = mock(ScimUserManager.class);
    adapter = new ScimUserRepositoryAdapter(userManager, scimConfig);
    ScimRequestContext.bindRequestBaseUri("http://localhost:9201");
  }

  @AfterEach
  void tearDown() {
    ScimRequestContext.clear();
  }

  @Test
  void testCreateNoUserName() {
    ResourceException exception =
        assertThrows(ResourceException.class, () -> adapter.create(new ScimUser()));
    assertEquals(400, exception.getStatus());
  }

  @Test
  void testCreateWithoutExternalId() throws Exception {
    ScimUserMeta created = ScimServiceTestEntities.user(USER_ID, "alice", null, true);
    when(userManager.createUser("alice", null, true)).thenReturn(created);

    ScimUser result = adapter.create(new ScimUser().setUserName("alice"));
    assertEquals(USER_SCIM_ID, result.getId());
    assertEquals(null, result.getExternalId());
    verify(userManager).createUser("alice", null, true);
  }

  @Test
  void testCreateAdd() throws Exception {
    ScimUserMeta created = ScimServiceTestEntities.user(USER_ID, "alice", USER_EXT_ID, true);
    when(userManager.createUser("alice", USER_EXT_ID, true)).thenReturn(created);

    ScimUser result =
        adapter.create(new ScimUser().setExternalId(USER_EXT_ID).setUserName("alice"));
    assertEquals(USER_SCIM_ID, result.getId());
    assertEquals(USER_EXT_ID, result.getExternalId());
    verify(userManager).createUser("alice", USER_EXT_ID, true);
  }

  @Test
  void testCreateAddInactive() throws Exception {
    ScimUserMeta created = ScimServiceTestEntities.user(USER_ID, "alice", USER_EXT_ID, false);
    when(userManager.createUser("alice", USER_EXT_ID, false)).thenReturn(created);

    ScimUser result =
        adapter.create(
            new ScimUser().setExternalId(USER_EXT_ID).setUserName("alice").setActive(false));
    assertEquals(USER_SCIM_ID, result.getId());
    assertEquals(Boolean.FALSE, result.getActive());
    verify(userManager).createUser("alice", USER_EXT_ID, false);
  }

  @Test
  void testCreateConflict409() throws Exception {
    when(userManager.createUser("alice", USER_EXT_ID, true))
        .thenThrow(new AlreadyExistsException("alice"));

    ResourceException exception =
        assertThrows(
            ResourceException.class,
            () -> adapter.create(new ScimUser().setExternalId(USER_EXT_ID).setUserName("alice")));
    assertEquals(409, exception.getStatus());
    assertEquals("User already exists: userName=alice", exception.getMessage());
  }

  @Test
  void testCreateConflictIgnoreCase409() throws Exception {
    when(userManager.createUser("Alice", "ext-2", true))
        .thenThrow(new AlreadyExistsException("Alice"));

    ResourceException exception =
        assertThrows(
            ResourceException.class,
            () -> adapter.create(new ScimUser().setExternalId("ext-2").setUserName("Alice")));
    assertEquals(409, exception.getStatus());
    assertEquals("User already exists: userName=Alice", exception.getMessage());
  }

  @Test
  void testPatchActive() throws Exception {
    ScimUserMeta disabled = ScimServiceTestEntities.user(USER_ID, "alice", USER_EXT_ID, false);
    when(userManager.updateEnabled(USER_ID, false)).thenReturn(disabled);

    PatchOperation operation = new PatchOperation();
    operation.setOperation(PatchOperation.Type.REPLACE);
    operation.setValue(Boolean.FALSE);
    ScimUser patched = adapter.patch(USER_SCIM_ID, null, List.of(operation), null, null);
    assertEquals(Boolean.FALSE, patched.getActive());
    verify(userManager).updateEnabled(USER_ID, false);
  }

  @Test
  void testPatchActivePath() throws Exception {
    ScimUserMeta enabled = ScimServiceTestEntities.user(USER_ID, "alice", USER_EXT_ID, true);
    when(userManager.updateEnabled(USER_ID, true)).thenReturn(enabled);

    PatchOperation operation = new PatchOperation();
    operation.setOperation(PatchOperation.Type.REPLACE);
    operation.setPath(PatchOperationPath.fromString("active"));
    operation.setValue(Boolean.TRUE);
    ScimUser patched = adapter.patch(USER_SCIM_ID, null, List.of(operation), null, null);
    assertEquals(Boolean.TRUE, patched.getActive());
    verify(userManager).updateEnabled(USER_ID, true);
  }

  @Test
  void testPatchActivePathStr() throws Exception {
    ScimUserMeta disabled = ScimServiceTestEntities.user(USER_ID, "alice", USER_EXT_ID, false);
    when(userManager.updateEnabled(USER_ID, false)).thenReturn(disabled);

    PatchOperation operation = new PatchOperation();
    operation.setOperation(PatchOperation.Type.REPLACE);
    operation.setPath(PatchOperationPath.fromString("active"));
    operation.setValue("false");
    ScimUser patched = adapter.patch(USER_SCIM_ID, null, List.of(operation), null, null);
    assertEquals(Boolean.FALSE, patched.getActive());
    verify(userManager).updateEnabled(USER_ID, false);
  }

  @Test
  void testUpdateDisable() throws Exception {
    ScimUserMeta enabled = ScimServiceTestEntities.user(USER_ID, "alice", USER_EXT_ID, true);
    ScimUserMeta disabled = ScimServiceTestEntities.user(USER_ID, "alice", USER_EXT_ID, false);
    when(userManager.getUser(USER_ID)).thenReturn(enabled);
    when(userManager.updateEnabled(USER_ID, false)).thenReturn(disabled);

    ScimUser updated =
        adapter.update(
            USER_SCIM_ID,
            null,
            new ScimUser()
                .setId(USER_SCIM_ID)
                .setExternalId(USER_EXT_ID)
                .setUserName("alice")
                .setActive(false),
            null,
            null);

    assertEquals(Boolean.FALSE, updated.getActive());
    verify(userManager).updateEnabled(USER_ID, false);
    verify(userManager, never()).updateEnabled(USER_ID, true);
  }

  @Test
  void testUpdateEnable() throws Exception {
    ScimUserMeta disabled = ScimServiceTestEntities.user(USER_ID, "alice", USER_EXT_ID, false);
    ScimUserMeta enabled = ScimServiceTestEntities.user(USER_ID, "alice", USER_EXT_ID, true);
    when(userManager.getUser(USER_ID)).thenReturn(disabled);
    when(userManager.updateEnabled(USER_ID, true)).thenReturn(enabled);

    ScimUser updated =
        adapter.update(
            USER_SCIM_ID, null, new ScimUser().setUserName("alice").setActive(true), null, null);

    assertEquals(Boolean.TRUE, updated.getActive());
    verify(userManager).updateEnabled(USER_ID, true);
  }

  @Test
  void testUpdateSameActive() throws Exception {
    ScimUserMeta enabled = ScimServiceTestEntities.user(USER_ID, "alice", USER_EXT_ID, true);
    when(userManager.getUser(USER_ID)).thenReturn(enabled);

    ScimUser updated =
        adapter.update(
            USER_SCIM_ID, null, new ScimUser().setUserName("alice").setActive(true), null, null);

    assertEquals(Boolean.TRUE, updated.getActive());
    verify(userManager, never()).updateEnabled(anyLong(), anyBoolean());
  }

  @Test
  void testUpdateNoActive() throws Exception {
    ScimUserMeta enabled = ScimServiceTestEntities.user(USER_ID, "alice", USER_EXT_ID, true);
    when(userManager.getUser(USER_ID)).thenReturn(enabled);

    ScimUser updated =
        adapter.update(USER_SCIM_ID, null, new ScimUser().setUserName("alice"), null, null);

    assertEquals(Boolean.TRUE, updated.getActive());
    verify(userManager, never()).updateEnabled(anyLong(), anyBoolean());
  }

  @Test
  void testUpdateNoUserName() throws Exception {
    ScimUserMeta enabled = ScimServiceTestEntities.user(USER_ID, "alice", USER_EXT_ID, true);
    when(userManager.getUser(USER_ID)).thenReturn(enabled);

    ScimUser updated =
        adapter.update(USER_SCIM_ID, null, new ScimUser().setActive(true), null, null);

    assertEquals("alice", updated.getUserName());
    verify(userManager, never()).updateEnabled(anyLong(), anyBoolean());
  }

  @Test
  void testUpdateRename400() throws Exception {
    ScimUserMeta user = ScimServiceTestEntities.user(USER_ID, "alice", USER_EXT_ID, true);
    when(userManager.getUser(USER_ID)).thenReturn(user);

    ResourceException exception =
        assertThrows(
            ResourceException.class,
            () ->
                adapter.update(
                    USER_SCIM_ID,
                    null,
                    new ScimUser().setUserName("bob").setActive(true),
                    null,
                    null));
    assertEquals(400, exception.getStatus());
    verify(userManager, never()).updateEnabled(anyLong(), anyBoolean());
  }

  @Test
  void testUpdateUserNameCaseOnlyNoOp() throws Exception {
    ScimUserMeta user = ScimServiceTestEntities.user(USER_ID, "alice", USER_EXT_ID, true);
    when(userManager.getUser(USER_ID)).thenReturn(user);

    ScimUser updated =
        adapter.update(
            USER_SCIM_ID, null, new ScimUser().setUserName("Alice").setActive(true), null, null);

    assertEquals("alice", updated.getUserName());
    verify(userManager, never()).updateEnabled(anyLong(), anyBoolean());
  }

  @Test
  void testUpdateExtId400() throws Exception {
    ScimUserMeta user = ScimServiceTestEntities.user(USER_ID, "alice", USER_EXT_ID, true);
    when(userManager.getUser(USER_ID)).thenReturn(user);

    ResourceException exception =
        assertThrows(
            ResourceException.class,
            () ->
                adapter.update(
                    USER_SCIM_ID,
                    null,
                    new ScimUser().setExternalId("other-ext").setUserName("alice").setActive(true),
                    null,
                    null));
    assertEquals(400, exception.getStatus());
  }

  @Test
  void testUpdateId400() throws Exception {
    ScimUserMeta user = ScimServiceTestEntities.user(USER_ID, "alice", USER_EXT_ID, true);
    when(userManager.getUser(USER_ID)).thenReturn(user);

    ResourceException exception =
        assertThrows(
            ResourceException.class,
            () ->
                adapter.update(
                    USER_SCIM_ID,
                    null,
                    new ScimUser().setId("other-id").setUserName("alice").setActive(true),
                    null,
                    null));
    assertEquals(400, exception.getStatus());
  }

  @Test
  void testUpdate404() {
    ResourceException exception =
        assertThrows(
            ResourceException.class,
            () -> adapter.update("missing", null, new ScimUser().setUserName("alice"), null, null));
    assertEquals(404, exception.getStatus());
  }

  @Test
  void testDelete() throws Exception {
    when(userManager.deleteUser(USER_ID)).thenReturn(true);
    adapter.delete(USER_SCIM_ID);
    verify(userManager).deleteUser(USER_ID);
  }

  @Test
  void testDelete404() {
    ResourceException exception =
        assertThrows(ResourceException.class, () -> adapter.delete("missing"));
    assertEquals(404, exception.getStatus());
  }

  @Test
  void testFindAndMismatch() throws Exception {
    ScimUserMeta user = ScimServiceTestEntities.user(USER_ID, "bob", USER_EXT_ID, true);
    when(userManager.getUserByExternalId(USER_EXT_ID)).thenReturn(user);

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
    ScimUserMeta user = ScimServiceTestEntities.user(USER_ID, "alice", USER_EXT_ID, true);
    when(userManager.findUserByUserNameIgnoreCase("alice")).thenReturn(user);

    var response =
        adapter.find(
            org.apache.directory.scim.spec.filter.Filter.decode("userName eq \"alice\""),
            new PageRequest().setStartIndex(1).setCount(10),
            null);
    assertEquals(1, response.getTotalResults());
    assertEquals("alice", response.getResources().iterator().next().getUserName());
    assertEquals(USER_SCIM_ID, response.getResources().iterator().next().getId());
  }

  @Test
  void testFindByUserNameIgnoreCase() throws Exception {
    ScimUserMeta user =
        ScimServiceTestEntities.user(USER_ID, "alice@example.com", USER_EXT_ID, true);
    when(userManager.findUserByUserNameIgnoreCase("ALICE@EXAMPLE.COM")).thenReturn(user);

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
  void testFindById() throws Exception {
    ScimUserMeta user = ScimServiceTestEntities.user(USER_ID, "alice", USER_EXT_ID, false);
    when(userManager.getUser(USER_ID)).thenReturn(user);

    var response =
        adapter.find(
            org.apache.directory.scim.spec.filter.Filter.decode("id eq \"1\""),
            new PageRequest().setStartIndex(1).setCount(10),
            null);
    assertEquals(1, response.getTotalResults());
    assertEquals(USER_SCIM_ID, response.getResources().iterator().next().getId());
    assertEquals(Boolean.FALSE, response.getResources().iterator().next().getActive());
  }

  @Test
  void testFindByIdMissing() throws Exception {
    when(userManager.getUser(999L)).thenThrow(new NotFoundException("999"));

    var response =
        adapter.find(
            org.apache.directory.scim.spec.filter.Filter.decode("id eq \"999\""),
            new PageRequest().setStartIndex(1).setCount(10),
            null);
    assertEquals(0, response.getTotalResults());
    assertEquals(0, response.getResources().size());
  }

  @Test
  void testFindByExtId() throws Exception {
    ScimUserMeta user = ScimServiceTestEntities.user(USER_ID, "alice", USER_EXT_ID, true);
    when(userManager.getUserByExternalId(USER_EXT_ID)).thenReturn(user);

    var response =
        adapter.find(
            org.apache.directory.scim.spec.filter.Filter.decode("externalId eq \"ext-1\""),
            new PageRequest().setStartIndex(1).setCount(10),
            null);
    assertEquals(1, response.getTotalResults());
    assertEquals(USER_SCIM_ID, response.getResources().iterator().next().getId());
    assertEquals(USER_EXT_ID, response.getResources().iterator().next().getExternalId());
  }

  @Test
  void testGet404() throws Exception {
    assertThrows(ResourceException.class, () -> adapter.get("missing"));
  }

  @Test
  void testFindEmpty() throws Exception {
    when(userManager.listUsers(0, 10)).thenReturn(new PagedResult<>(0, List.of()));
    var response = adapter.find(null, new PageRequest().setStartIndex(1).setCount(10), null);
    assertEquals(0, response.getTotalResults());
    assertEquals(0, response.getResources().size());
    verify(userManager).listUsers(0, 10);
  }

  @Test
  void testFindUnfilteredPaged() throws Exception {
    ScimUserMeta alice = ScimServiceTestEntities.user(USER_ID, "alice", USER_EXT_ID, true);
    ScimUserMeta bob = ScimServiceTestEntities.user(2L, "bob", "ext-2", true);
    when(userManager.listUsers(0, 10)).thenReturn(new PagedResult<>(2, List.of(alice, bob)));

    var response = adapter.find(null, new PageRequest().setStartIndex(1).setCount(10), null);

    assertEquals(2, response.getTotalResults());
    assertEquals(2, response.getResources().size());
    var users = response.getResources().toArray(new ScimUser[0]);
    assertEquals("alice", users[0].getUserName());
    assertEquals("bob", users[1].getUserName());
    verify(userManager).listUsers(0, 10);
  }

  @Test
  void testCreateThroughEventDispatcherEmitsScimEventsOnly() throws Exception {
    ScimUserManager manager = mock(ScimUserManager.class);
    ScimUserMeta created = ScimServiceTestEntities.user(USER_ID, "alice", USER_EXT_ID, true);
    when(manager.createUser("alice", USER_EXT_ID, true)).thenReturn(created);

    EventBus eventBus = mock(EventBus.class);
    ScimUserEventDispatcher scimDispatcher =
        new ScimUserEventDispatcher(eventBus, new ScimUserRepositoryAdapter(manager, scimConfig));

    ScimUser result =
        scimDispatcher.create(new ScimUser().setUserName("alice").setExternalId(USER_EXT_ID));
    assertEquals(USER_SCIM_ID, result.getId());

    ArgumentCaptor<BaseEvent> captor = ArgumentCaptor.forClass(BaseEvent.class);
    verify(eventBus, times(2)).dispatchEvent(captor.capture());
    List<BaseEvent> events = captor.getAllValues();
    assertInstanceOf(ScimAddUserPreEvent.class, events.get(0));
    assertInstanceOf(ScimAddUserEvent.class, events.get(1));
  }

  @Test
  void testUpdateThroughEventDispatcherEmitsScimEventsOnly() throws Exception {
    ScimUserManager manager = mock(ScimUserManager.class);
    ScimUserMeta enabled = ScimServiceTestEntities.user(USER_ID, "alice", USER_EXT_ID, true);
    ScimUserMeta disabled = ScimServiceTestEntities.user(USER_ID, "alice", USER_EXT_ID, false);
    when(manager.getUser(USER_ID)).thenReturn(enabled);
    when(manager.updateEnabled(USER_ID, false)).thenReturn(disabled);

    EventBus eventBus = mock(EventBus.class);
    ScimUserEventDispatcher scimDispatcher =
        new ScimUserEventDispatcher(eventBus, new ScimUserRepositoryAdapter(manager, scimConfig));

    ScimUser result =
        scimDispatcher.update(
            USER_SCIM_ID,
            null,
            new ScimUser()
                .setId(USER_SCIM_ID)
                .setExternalId(USER_EXT_ID)
                .setUserName("alice")
                .setActive(false),
            null,
            null);
    assertEquals(Boolean.FALSE, result.getActive());

    ArgumentCaptor<BaseEvent> captor = ArgumentCaptor.forClass(BaseEvent.class);
    verify(eventBus, times(2)).dispatchEvent(captor.capture());
    List<BaseEvent> events = captor.getAllValues();
    assertInstanceOf(ScimAlterUserPreEvent.class, events.get(0));
    assertInstanceOf(ScimAlterUserEvent.class, events.get(1));
  }

  @Test
  void testDeleteThroughEventDispatcherEmitsScimEventsOnly() throws Exception {
    ScimUserManager manager = mock(ScimUserManager.class);
    ScimUserMeta existing = ScimServiceTestEntities.user(USER_ID, "alice", USER_EXT_ID, true);
    when(manager.getUser(USER_ID)).thenReturn(existing);
    when(manager.deleteUser(USER_ID)).thenReturn(true);

    EventBus eventBus = mock(EventBus.class);
    ScimUserEventDispatcher scimDispatcher =
        new ScimUserEventDispatcher(eventBus, new ScimUserRepositoryAdapter(manager, scimConfig));

    scimDispatcher.delete(USER_SCIM_ID);

    ArgumentCaptor<BaseEvent> captor = ArgumentCaptor.forClass(BaseEvent.class);
    verify(eventBus, times(2)).dispatchEvent(captor.capture());
    List<BaseEvent> events = captor.getAllValues();
    assertInstanceOf(ScimRemoveUserPreEvent.class, events.get(0));
    assertInstanceOf(ScimRemoveUserEvent.class, events.get(1));
    assertEquals("alice", ((ScimRemoveUserEvent) events.get(1)).userName());
    verify(manager).getUser(USER_ID);
  }
}
