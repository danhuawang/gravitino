/*
 * Copyright 2026 Datastrato Pvt Ltd.
 * This software is licensed under the Apache License version 2.
 */

package com.datastrato.gravitino.scim.service.listener;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.datastrato.gravitino.scim.service.web.ScimMetalakeContext;
import java.util.List;
import org.apache.directory.scim.core.repository.Repository;
import org.apache.directory.scim.spec.exception.ResourceException;
import org.apache.directory.scim.spec.filter.FilterResponse;
import org.apache.directory.scim.spec.filter.PageRequest;
import org.apache.directory.scim.spec.resources.ScimUser;
import org.apache.gravitino.listener.EventBus;
import org.apache.gravitino.listener.api.event.BaseEvent;
import org.apache.gravitino.listener.api.event.OperationType;
import org.apache.gravitino.listener.api.event.scim.ScimAddUserEvent;
import org.apache.gravitino.listener.api.event.scim.ScimAddUserFailureEvent;
import org.apache.gravitino.listener.api.event.scim.ScimAddUserPreEvent;
import org.apache.gravitino.listener.api.event.scim.ScimAlterUserFailureEvent;
import org.apache.gravitino.listener.api.event.scim.ScimAlterUserPreEvent;
import org.apache.gravitino.listener.api.event.scim.ScimGetUserEvent;
import org.apache.gravitino.listener.api.event.scim.ScimGetUserFailureEvent;
import org.apache.gravitino.listener.api.event.scim.ScimGetUserPreEvent;
import org.apache.gravitino.listener.api.event.scim.ScimListUsersEvent;
import org.apache.gravitino.listener.api.event.scim.ScimListUsersPreEvent;
import org.apache.gravitino.listener.api.event.scim.ScimRemoveUserEvent;
import org.apache.gravitino.listener.api.event.scim.ScimRemoveUserFailureEvent;
import org.apache.gravitino.listener.api.event.scim.ScimRemoveUserPreEvent;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

class TestScimUserEventDispatcher {

  private static final String METALAKE = "test_metalake";

  @BeforeEach
  void setUp() {
    ScimMetalakeContext.setMetalake(METALAKE);
  }

  @AfterEach
  void tearDown() {
    ScimMetalakeContext.clear();
  }

  @Test
  void testCreateDispatchesPreAndSuccess() throws Exception {
    @SuppressWarnings("unchecked")
    Repository<ScimUser> repository = mock(Repository.class);
    EventBus eventBus = mock(EventBus.class);
    ScimUserEventDispatcher scimDispatcher = new ScimUserEventDispatcher(eventBus, repository);

    ScimUser request = scimUser(null, "alice", "ext-1");
    ScimUser created = scimUser("1", "alice", "ext-1");
    when(repository.create(request)).thenReturn(created);

    ScimUser result = scimDispatcher.create(request);
    assertEquals(created, result);

    ArgumentCaptor<BaseEvent> captor = ArgumentCaptor.forClass(BaseEvent.class);
    verify(eventBus, times(2)).dispatchEvent(captor.capture());
    List<BaseEvent> events = captor.getAllValues();
    assertInstanceOf(ScimAddUserPreEvent.class, events.get(0));
    assertInstanceOf(ScimAddUserEvent.class, events.get(1));
    ScimAddUserPreEvent preEvent = (ScimAddUserPreEvent) events.get(0);
    ScimAddUserEvent successEvent = (ScimAddUserEvent) events.get(1);
    assertEquals(OperationType.ADD_USER, preEvent.operationType());
    assertEquals(OperationType.ADD_USER, successEvent.operationType());
    assertEquals("alice", preEvent.userName());
    assertEquals("ext-1", preEvent.externalId());
    assertEquals("1", successEvent.resourceId());
    assertEquals("ext-1", successEvent.externalId());
  }

  @Test
  void testDeleteDispatchesPreAndSuccess() throws Exception {
    @SuppressWarnings("unchecked")
    Repository<ScimUser> repository = mock(Repository.class);
    EventBus eventBus = mock(EventBus.class);
    ScimUserEventDispatcher scimDispatcher = new ScimUserEventDispatcher(eventBus, repository);

    scimDispatcher.delete("1");

    ArgumentCaptor<BaseEvent> captor = ArgumentCaptor.forClass(BaseEvent.class);
    verify(eventBus, times(2)).dispatchEvent(captor.capture());
    List<BaseEvent> events = captor.getAllValues();
    assertInstanceOf(ScimRemoveUserPreEvent.class, events.get(0));
    assertInstanceOf(ScimRemoveUserEvent.class, events.get(1));
    ScimRemoveUserPreEvent preEvent = (ScimRemoveUserPreEvent) events.get(0);
    ScimRemoveUserEvent successEvent = (ScimRemoveUserEvent) events.get(1);
    assertEquals(OperationType.REMOVE_USER, preEvent.operationType());
    assertEquals(OperationType.REMOVE_USER, successEvent.operationType());
    assertEquals("1", preEvent.resourceId());
    assertEquals("unknown", preEvent.userName());
    assertEquals("1", successEvent.resourceId());
    verify(repository).delete("1");
  }

  @Test
  void testCreateDispatchesFailureOnConflict() throws Exception {
    @SuppressWarnings("unchecked")
    Repository<ScimUser> repository = mock(Repository.class);
    EventBus eventBus = mock(EventBus.class);
    ScimUserEventDispatcher scimDispatcher = new ScimUserEventDispatcher(eventBus, repository);

    ScimUser request = scimUser(null, "alice", "ext-1");
    ResourceException conflict = new ResourceException(409, "User already exists");
    when(repository.create(any(ScimUser.class))).thenThrow(conflict);

    assertThrows(ResourceException.class, () -> scimDispatcher.create(request));

    ArgumentCaptor<BaseEvent> captor = ArgumentCaptor.forClass(BaseEvent.class);
    verify(eventBus, times(2)).dispatchEvent(captor.capture());
    List<BaseEvent> events = captor.getAllValues();
    assertInstanceOf(ScimAddUserPreEvent.class, events.get(0));
    assertInstanceOf(ScimAddUserFailureEvent.class, events.get(1));
    ScimAddUserFailureEvent failureEvent = (ScimAddUserFailureEvent) events.get(1);
    assertEquals(OperationType.ADD_USER, failureEvent.operationType());
    assertEquals(conflict, failureEvent.exception());
    assertEquals("alice", failureEvent.userName());
    assertEquals("ext-1", failureEvent.externalId());
  }

  @Test
  void testGetDispatchesPreAndSuccess() throws Exception {
    @SuppressWarnings("unchecked")
    Repository<ScimUser> repository = mock(Repository.class);
    EventBus eventBus = mock(EventBus.class);
    ScimUserEventDispatcher scimDispatcher = new ScimUserEventDispatcher(eventBus, repository);

    ScimUser existing = scimUser("1", "alice", "ext-1");
    when(repository.get("1")).thenReturn(existing);

    ScimUser result = scimDispatcher.get("1");
    assertEquals(existing, result);

    ArgumentCaptor<BaseEvent> captor = ArgumentCaptor.forClass(BaseEvent.class);
    verify(eventBus, times(2)).dispatchEvent(captor.capture());
    List<BaseEvent> events = captor.getAllValues();
    assertInstanceOf(ScimGetUserPreEvent.class, events.get(0));
    assertInstanceOf(ScimGetUserEvent.class, events.get(1));
    ScimGetUserPreEvent preEvent = (ScimGetUserPreEvent) events.get(0);
    ScimGetUserEvent successEvent = (ScimGetUserEvent) events.get(1);
    assertEquals(OperationType.GET_USER_BY_ID, preEvent.operationType());
    assertEquals(OperationType.GET_USER_BY_ID, successEvent.operationType());
    assertEquals("1", preEvent.resourceId());
    assertEquals("alice", successEvent.userName());
    assertEquals("ext-1", successEvent.externalId());
  }

  @Test
  void testGetDispatchesFailure() throws Exception {
    @SuppressWarnings("unchecked")
    Repository<ScimUser> repository = mock(Repository.class);
    EventBus eventBus = mock(EventBus.class);
    ScimUserEventDispatcher scimDispatcher = new ScimUserEventDispatcher(eventBus, repository);

    ResourceException notFound = new ResourceException(404, "User not found");
    when(repository.get("999")).thenThrow(notFound);

    assertThrows(ResourceException.class, () -> scimDispatcher.get("999"));

    ArgumentCaptor<BaseEvent> captor = ArgumentCaptor.forClass(BaseEvent.class);
    verify(eventBus, times(2)).dispatchEvent(captor.capture());
    List<BaseEvent> events = captor.getAllValues();
    assertInstanceOf(ScimGetUserPreEvent.class, events.get(0));
    assertInstanceOf(ScimGetUserFailureEvent.class, events.get(1));
    assertEquals(notFound, ((ScimGetUserFailureEvent) events.get(1)).exception());
  }

  @Test
  void testFindDispatchesPreAndSuccess() throws Exception {
    @SuppressWarnings("unchecked")
    Repository<ScimUser> repository = mock(Repository.class);
    EventBus eventBus = mock(EventBus.class);
    ScimUserEventDispatcher scimDispatcher = new ScimUserEventDispatcher(eventBus, repository);

    PageRequest pageRequest = new PageRequest().setStartIndex(1).setCount(10);
    FilterResponse<ScimUser> response =
        new FilterResponse<>(List.of(scimUser("1", "alice", "ext-1")), pageRequest, 1);
    when(repository.find(any(), any(), any())).thenReturn(response);

    FilterResponse<ScimUser> result = scimDispatcher.find(null, pageRequest, null);
    assertEquals(response, result);

    ArgumentCaptor<BaseEvent> captor = ArgumentCaptor.forClass(BaseEvent.class);
    verify(eventBus, times(2)).dispatchEvent(captor.capture());
    List<BaseEvent> events = captor.getAllValues();
    assertInstanceOf(ScimListUsersPreEvent.class, events.get(0));
    assertInstanceOf(ScimListUsersEvent.class, events.get(1));
    ScimListUsersPreEvent preEvent = (ScimListUsersPreEvent) events.get(0);
    ScimListUsersEvent successEvent = (ScimListUsersEvent) events.get(1);
    assertEquals(OperationType.LIST_USERS_PAGED, preEvent.operationType());
    assertEquals(OperationType.LIST_USERS_PAGED, successEvent.operationType());
    assertEquals(1, preEvent.startIndex());
    assertEquals(10, preEvent.count());
    assertEquals(1, successEvent.resultCount());
    assertEquals(1L, successEvent.totalCount());
  }

  @Test
  void testUpdateDispatchesFailure() throws Exception {
    @SuppressWarnings("unchecked")
    Repository<ScimUser> repository = mock(Repository.class);
    EventBus eventBus = mock(EventBus.class);
    ScimUserEventDispatcher scimDispatcher = new ScimUserEventDispatcher(eventBus, repository);

    ResourceException notFound = new ResourceException(404, "User not found");
    when(repository.update(any(), any(), any(), any(), any())).thenThrow(notFound);

    assertThrows(
        ResourceException.class,
        () -> scimDispatcher.update("999", null, scimUser(null, "alice", null), null, null));

    ArgumentCaptor<BaseEvent> captor = ArgumentCaptor.forClass(BaseEvent.class);
    verify(eventBus, times(2)).dispatchEvent(captor.capture());
    List<BaseEvent> events = captor.getAllValues();
    assertInstanceOf(ScimAlterUserPreEvent.class, events.get(0));
    assertInstanceOf(ScimAlterUserFailureEvent.class, events.get(1));
    ScimAlterUserFailureEvent failureEvent = (ScimAlterUserFailureEvent) events.get(1);
    assertEquals(notFound, failureEvent.exception());
    assertEquals("999", failureEvent.resourceId());
    assertEquals("alice", failureEvent.userName());
  }

  @Test
  void testDeleteDispatchesFailure() throws Exception {
    @SuppressWarnings("unchecked")
    Repository<ScimUser> repository = mock(Repository.class);
    EventBus eventBus = mock(EventBus.class);
    ScimUserEventDispatcher scimDispatcher = new ScimUserEventDispatcher(eventBus, repository);

    ResourceException notFound = new ResourceException(404, "User not found");
    org.mockito.Mockito.doThrow(notFound).when(repository).delete("999");

    assertThrows(ResourceException.class, () -> scimDispatcher.delete("999"));

    ArgumentCaptor<BaseEvent> captor = ArgumentCaptor.forClass(BaseEvent.class);
    verify(eventBus, times(2)).dispatchEvent(captor.capture());
    List<BaseEvent> events = captor.getAllValues();
    assertInstanceOf(ScimRemoveUserPreEvent.class, events.get(0));
    assertInstanceOf(ScimRemoveUserFailureEvent.class, events.get(1));
    ScimRemoveUserFailureEvent failureEvent = (ScimRemoveUserFailureEvent) events.get(1);
    assertEquals(notFound, failureEvent.exception());
    assertEquals("999", failureEvent.resourceId());
    assertEquals("unknown", failureEvent.userName());
  }

  private static ScimUser scimUser(String id, String userName, String externalId) {
    ScimUser user = new ScimUser();
    user.setId(id);
    user.setUserName(userName);
    user.setExternalId(externalId);
    return user;
  }
}
