/*
 * Copyright 2026 Datastrato Pvt Ltd.
 * This software is licensed under the Apache License version 2.
 */

package com.datastrato.gravitino.scim.service.adapter;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.datastrato.gravitino.scim.service.ScimConfig;
import com.datastrato.gravitino.scim.service.ScimServiceTestEntities;
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
import org.apache.gravitino.authorization.User;
import org.apache.gravitino.exceptions.NoSuchUserException;
import org.apache.gravitino.exceptions.UserAlreadyExistsException;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class TestScimUserRepositoryAdapter {

  private static final String METALAKE = "test_metalake";

  private AccessControlDispatcher dispatcher;
  private ScimUserRepositoryAdapter adapter;
  private ScimConfig scimConfig;

  @BeforeEach
  void setUp() {
    scimConfig = new ScimConfig(Map.of(), new Config() {});
    dispatcher = mock(AccessControlDispatcher.class);
    adapter = new ScimUserRepositoryAdapter(dispatcher, scimConfig);
    ScimMetalakeContext.setMetalake(METALAKE);
  }

  @AfterEach
  void tearDown() {
    ScimMetalakeContext.clear();
  }

  @Test
  void testCreateNoExtId() {
    assertThrows(ResourceException.class, () -> adapter.create(new ScimUser()));
  }

  @Test
  void testCreateIdempotent() throws Exception {
    User existing = ScimServiceTestEntities.user(1L, "alice", "ext-1", true);
    when(dispatcher.getUserByExternalId(METALAKE, "ext-1")).thenReturn(existing);

    ScimUser created = adapter.create(new ScimUser().setExternalId("ext-1").setUserName("alice"));
    assertEquals("ext-1", created.getId());
    assertEquals("alice", created.getUserName());
  }

  @Test
  void testCreateAdd() throws Exception {
    User created = ScimServiceTestEntities.user(1L, "alice", "ext-1", true);
    when(dispatcher.getUserByExternalId(METALAKE, "ext-1"))
        .thenThrow(new NoSuchUserException("ext-1"));
    when(dispatcher.addUser(METALAKE, "alice", "ext-1", true)).thenReturn(created);

    ScimUser result = adapter.create(new ScimUser().setExternalId("ext-1").setUserName("alice"));
    assertEquals("ext-1", result.getId());
    verify(dispatcher).addUser(METALAKE, "alice", "ext-1", true);
  }

  @Test
  void testCreateAddInactive() throws Exception {
    User created = ScimServiceTestEntities.user(1L, "alice", "ext-1", false);
    when(dispatcher.getUserByExternalId(METALAKE, "ext-1"))
        .thenThrow(new NoSuchUserException("ext-1"));
    when(dispatcher.addUser(METALAKE, "alice", "ext-1", false)).thenReturn(created);

    ScimUser result =
        adapter.create(new ScimUser().setExternalId("ext-1").setUserName("alice").setActive(false));
    assertEquals("ext-1", result.getId());
    assertEquals(Boolean.FALSE, result.getActive());
    verify(dispatcher).addUser(METALAKE, "alice", "ext-1", false);
  }

  @Test
  void testCreateConflict409() throws Exception {
    when(dispatcher.getUserByExternalId(METALAKE, "ext-1"))
        .thenThrow(new NoSuchUserException("ext-1"));
    when(dispatcher.addUser(METALAKE, "alice", "ext-1", true))
        .thenThrow(new UserAlreadyExistsException("alice"));

    ResourceException exception =
        assertThrows(
            ResourceException.class,
            () -> adapter.create(new ScimUser().setExternalId("ext-1").setUserName("alice")));
    assertEquals(409, exception.getStatus());
  }

  @Test
  void testPatchActive() throws Exception {
    User disabled = ScimServiceTestEntities.user(1L, "alice", "ext-1", false);
    when(dispatcher.disableUser(METALAKE, "ext-1")).thenReturn(disabled);

    PatchOperation operation = new PatchOperation();
    operation.setOperation(PatchOperation.Type.REPLACE);
    operation.setValue(Boolean.FALSE);
    ScimUser patched = adapter.patch("ext-1", null, List.of(operation), null, null);
    assertEquals(Boolean.FALSE, patched.getActive());
  }

  @Test
  void testPatchActivePath() throws Exception {
    User enabled = ScimServiceTestEntities.user(1L, "alice", "ext-1", true);
    when(dispatcher.enableUser(METALAKE, "ext-1")).thenReturn(enabled);

    PatchOperation operation = new PatchOperation();
    operation.setOperation(PatchOperation.Type.REPLACE);
    operation.setPath(PatchOperationPath.fromString("active"));
    operation.setValue(Boolean.TRUE);
    ScimUser patched = adapter.patch("ext-1", null, List.of(operation), null, null);
    assertEquals(Boolean.TRUE, patched.getActive());
  }

  @Test
  void testPatchActivePathStr() throws Exception {
    User disabled = ScimServiceTestEntities.user(1L, "alice", "ext-1", false);
    when(dispatcher.disableUser(METALAKE, "ext-1")).thenReturn(disabled);

    PatchOperation operation = new PatchOperation();
    operation.setOperation(PatchOperation.Type.REPLACE);
    operation.setPath(PatchOperationPath.fromString("active"));
    operation.setValue("false");
    ScimUser patched = adapter.patch("ext-1", null, List.of(operation), null, null);
    assertEquals(Boolean.FALSE, patched.getActive());
  }

  @Test
  void testUpdate405() {
    assertThrows(
        ResourceException.class, () -> adapter.update("ext-1", null, new ScimUser(), null, null));
  }

  @Test
  void testDelete() throws Exception {
    when(dispatcher.removeUserByExternalId(METALAKE, "ext-1")).thenReturn(true);
    adapter.delete("ext-1");
    verify(dispatcher).removeUserByExternalId(eq(METALAKE), eq("ext-1"));
  }

  @Test
  void testDelete404() {
    when(dispatcher.removeUserByExternalId(METALAKE, "missing")).thenReturn(false);
    ResourceException exception =
        assertThrows(ResourceException.class, () -> adapter.delete("missing"));
    assertEquals(404, exception.getStatus());
  }

  @Test
  void testFindAndMismatch() throws Exception {
    User user = ScimServiceTestEntities.user(1L, "bob", "ext-1", true);
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
    User user = ScimServiceTestEntities.user(1L, "alice", "ext-1", true);
    when(dispatcher.getUser(METALAKE, "alice")).thenReturn(user);

    var response =
        adapter.find(
            org.apache.directory.scim.spec.filter.Filter.decode("userName eq \"alice\""),
            new PageRequest().setStartIndex(1).setCount(10),
            null);
    assertEquals(1, response.getTotalResults());
    assertEquals("alice", response.getResources().iterator().next().getUserName());
  }

  @Test
  void testFindByExtId() throws Exception {
    User user = ScimServiceTestEntities.user(1L, "alice", "ext-1", true);
    when(dispatcher.getUserByExternalId(METALAKE, "ext-1")).thenReturn(user);

    var response =
        adapter.find(
            org.apache.directory.scim.spec.filter.Filter.decode("externalId eq \"ext-1\""),
            new PageRequest().setStartIndex(1).setCount(10),
            null);
    assertEquals(1, response.getTotalResults());
    assertEquals("ext-1", response.getResources().iterator().next().getId());
  }

  @Test
  void testGet404() throws Exception {
    when(dispatcher.getUserByExternalId(eq(METALAKE), eq("missing")))
        .thenThrow(new NoSuchUserException("missing"));
    assertThrows(ResourceException.class, () -> adapter.get("missing"));
  }

  @Test
  void testFindEmpty() throws Exception {
    var response = adapter.find(null, new PageRequest().setStartIndex(1).setCount(10), null);
    assertEquals(0, response.getTotalResults());
    assertEquals(0, response.getResources().size());
  }
}
