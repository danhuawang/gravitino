/*
 * Copyright 2026 Datastrato Inc.
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
import org.apache.directory.scim.spec.resources.ScimGroup;
import org.apache.gravitino.listener.EventBus;
import org.apache.gravitino.listener.api.event.BaseEvent;
import org.apache.gravitino.listener.api.event.OperationType;
import org.apache.gravitino.listener.api.event.scim.ScimAddGroupEvent;
import org.apache.gravitino.listener.api.event.scim.ScimAddGroupFailureEvent;
import org.apache.gravitino.listener.api.event.scim.ScimAddGroupPreEvent;
import org.apache.gravitino.listener.api.event.scim.ScimAlterGroupFailureEvent;
import org.apache.gravitino.listener.api.event.scim.ScimAlterGroupPreEvent;
import org.apache.gravitino.listener.api.event.scim.ScimGetGroupEvent;
import org.apache.gravitino.listener.api.event.scim.ScimGetGroupFailureEvent;
import org.apache.gravitino.listener.api.event.scim.ScimGetGroupPreEvent;
import org.apache.gravitino.listener.api.event.scim.ScimListGroupsEvent;
import org.apache.gravitino.listener.api.event.scim.ScimListGroupsPreEvent;
import org.apache.gravitino.listener.api.event.scim.ScimRemoveGroupEvent;
import org.apache.gravitino.listener.api.event.scim.ScimRemoveGroupFailureEvent;
import org.apache.gravitino.listener.api.event.scim.ScimRemoveGroupPreEvent;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

class TestScimGroupEventDispatcher {

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
    Repository<ScimGroup> repository = mock(Repository.class);
    EventBus eventBus = mock(EventBus.class);
    ScimGroupEventDispatcher scimDispatcher = new ScimGroupEventDispatcher(eventBus, repository);

    ScimGroup request = scimGroup(null, "engineering", "ext-1");
    ScimGroup created = scimGroup("1", "engineering", "ext-1");
    when(repository.create(request)).thenReturn(created);

    ScimGroup result = scimDispatcher.create(request);
    assertEquals(created, result);

    ArgumentCaptor<BaseEvent> captor = ArgumentCaptor.forClass(BaseEvent.class);
    verify(eventBus, times(2)).dispatchEvent(captor.capture());
    List<BaseEvent> events = captor.getAllValues();
    assertInstanceOf(ScimAddGroupPreEvent.class, events.get(0));
    assertInstanceOf(ScimAddGroupEvent.class, events.get(1));
    ScimAddGroupPreEvent preEvent = (ScimAddGroupPreEvent) events.get(0);
    ScimAddGroupEvent successEvent = (ScimAddGroupEvent) events.get(1);
    assertEquals(OperationType.ADD_GROUP, preEvent.operationType());
    assertEquals(OperationType.ADD_GROUP, successEvent.operationType());
    assertEquals("engineering", preEvent.groupName());
    assertEquals("ext-1", preEvent.externalId());
    assertEquals("1", successEvent.resourceId());
    assertEquals("ext-1", successEvent.externalId());
  }

  @Test
  void testDeleteDispatchesPreAndSuccess() throws Exception {
    @SuppressWarnings("unchecked")
    Repository<ScimGroup> repository = mock(Repository.class);
    EventBus eventBus = mock(EventBus.class);
    ScimGroupEventDispatcher scimDispatcher = new ScimGroupEventDispatcher(eventBus, repository);

    when(repository.get("1")).thenReturn(scimGroup("1", "engineering", "ext-1"));
    scimDispatcher.delete("1");

    ArgumentCaptor<BaseEvent> captor = ArgumentCaptor.forClass(BaseEvent.class);
    verify(eventBus, times(2)).dispatchEvent(captor.capture());
    List<BaseEvent> events = captor.getAllValues();
    assertInstanceOf(ScimRemoveGroupPreEvent.class, events.get(0));
    assertInstanceOf(ScimRemoveGroupEvent.class, events.get(1));
    ScimRemoveGroupPreEvent preEvent = (ScimRemoveGroupPreEvent) events.get(0);
    ScimRemoveGroupEvent successEvent = (ScimRemoveGroupEvent) events.get(1);
    assertEquals(OperationType.REMOVE_GROUP, preEvent.operationType());
    assertEquals(OperationType.REMOVE_GROUP, successEvent.operationType());
    assertEquals("1", preEvent.resourceId());
    assertEquals("engineering", preEvent.groupName());
    assertEquals("ext-1", preEvent.externalId());
    assertEquals("1", successEvent.resourceId());
    assertEquals("engineering", successEvent.groupName());
    assertEquals("ext-1", successEvent.externalId());
    verify(repository).get("1");
    verify(repository).delete("1");
  }

  @Test
  void testCreateDispatchesFailureOnConflict() throws Exception {
    @SuppressWarnings("unchecked")
    Repository<ScimGroup> repository = mock(Repository.class);
    EventBus eventBus = mock(EventBus.class);
    ScimGroupEventDispatcher scimDispatcher = new ScimGroupEventDispatcher(eventBus, repository);

    ScimGroup request = scimGroup(null, "engineering", "ext-1");
    ResourceException conflict = new ResourceException(409, "Group already exists");
    when(repository.create(any(ScimGroup.class))).thenThrow(conflict);

    assertThrows(ResourceException.class, () -> scimDispatcher.create(request));

    ArgumentCaptor<BaseEvent> captor = ArgumentCaptor.forClass(BaseEvent.class);
    verify(eventBus, times(2)).dispatchEvent(captor.capture());
    List<BaseEvent> events = captor.getAllValues();
    assertInstanceOf(ScimAddGroupPreEvent.class, events.get(0));
    assertInstanceOf(ScimAddGroupFailureEvent.class, events.get(1));
    ScimAddGroupFailureEvent failureEvent = (ScimAddGroupFailureEvent) events.get(1);
    assertEquals(OperationType.ADD_GROUP, failureEvent.operationType());
    assertEquals(conflict, failureEvent.exception());
    assertEquals("engineering", failureEvent.groupName());
    assertEquals("ext-1", failureEvent.externalId());
  }

  @Test
  void testGetDispatchesPreAndSuccess() throws Exception {
    @SuppressWarnings("unchecked")
    Repository<ScimGroup> repository = mock(Repository.class);
    EventBus eventBus = mock(EventBus.class);
    ScimGroupEventDispatcher scimDispatcher = new ScimGroupEventDispatcher(eventBus, repository);

    ScimGroup existing = scimGroup("1", "engineering", "ext-1");
    when(repository.get("1")).thenReturn(existing);

    ScimGroup result = scimDispatcher.get("1");
    assertEquals(existing, result);

    ArgumentCaptor<BaseEvent> captor = ArgumentCaptor.forClass(BaseEvent.class);
    verify(eventBus, times(2)).dispatchEvent(captor.capture());
    List<BaseEvent> events = captor.getAllValues();
    assertInstanceOf(ScimGetGroupPreEvent.class, events.get(0));
    assertInstanceOf(ScimGetGroupEvent.class, events.get(1));
    ScimGetGroupPreEvent preEvent = (ScimGetGroupPreEvent) events.get(0);
    ScimGetGroupEvent successEvent = (ScimGetGroupEvent) events.get(1);
    assertEquals(OperationType.GET_GROUP_BY_ID, preEvent.operationType());
    assertEquals(OperationType.GET_GROUP_BY_ID, successEvent.operationType());
    assertEquals("1", preEvent.resourceId());
    assertEquals("engineering", successEvent.groupName());
    assertEquals("ext-1", successEvent.externalId());
  }

  @Test
  void testGetDispatchesFailure() throws Exception {
    @SuppressWarnings("unchecked")
    Repository<ScimGroup> repository = mock(Repository.class);
    EventBus eventBus = mock(EventBus.class);
    ScimGroupEventDispatcher scimDispatcher = new ScimGroupEventDispatcher(eventBus, repository);

    ResourceException notFound = new ResourceException(404, "Group not found");
    when(repository.get("999")).thenThrow(notFound);

    assertThrows(ResourceException.class, () -> scimDispatcher.get("999"));

    ArgumentCaptor<BaseEvent> captor = ArgumentCaptor.forClass(BaseEvent.class);
    verify(eventBus, times(2)).dispatchEvent(captor.capture());
    List<BaseEvent> events = captor.getAllValues();
    assertInstanceOf(ScimGetGroupPreEvent.class, events.get(0));
    assertInstanceOf(ScimGetGroupFailureEvent.class, events.get(1));
    assertEquals(notFound, ((ScimGetGroupFailureEvent) events.get(1)).exception());
  }

  @Test
  void testFindDispatchesPreAndSuccess() throws Exception {
    @SuppressWarnings("unchecked")
    Repository<ScimGroup> repository = mock(Repository.class);
    EventBus eventBus = mock(EventBus.class);
    ScimGroupEventDispatcher scimDispatcher = new ScimGroupEventDispatcher(eventBus, repository);

    PageRequest pageRequest = new PageRequest().setStartIndex(1).setCount(10);
    FilterResponse<ScimGroup> response =
        new FilterResponse<>(List.of(scimGroup("1", "engineering", "ext-1")), pageRequest, 1);
    when(repository.find(any(), any(), any())).thenReturn(response);

    FilterResponse<ScimGroup> result = scimDispatcher.find(null, pageRequest, null);
    assertEquals(response, result);

    ArgumentCaptor<BaseEvent> captor = ArgumentCaptor.forClass(BaseEvent.class);
    verify(eventBus, times(2)).dispatchEvent(captor.capture());
    List<BaseEvent> events = captor.getAllValues();
    assertInstanceOf(ScimListGroupsPreEvent.class, events.get(0));
    assertInstanceOf(ScimListGroupsEvent.class, events.get(1));
    ScimListGroupsPreEvent preEvent = (ScimListGroupsPreEvent) events.get(0);
    ScimListGroupsEvent successEvent = (ScimListGroupsEvent) events.get(1);
    assertEquals(OperationType.LIST_GROUPS_PAGED, preEvent.operationType());
    assertEquals(OperationType.LIST_GROUPS_PAGED, successEvent.operationType());
    assertEquals(1, preEvent.startIndex());
    assertEquals(10, preEvent.count());
    assertEquals(1, successEvent.pageSize());
    assertEquals("1", successEvent.customInfo().get("count"));
    assertEquals(1L, successEvent.totalCount());
  }

  @Test
  void testUpdateDispatchesFailure() throws Exception {
    @SuppressWarnings("unchecked")
    Repository<ScimGroup> repository = mock(Repository.class);
    EventBus eventBus = mock(EventBus.class);
    ScimGroupEventDispatcher scimDispatcher = new ScimGroupEventDispatcher(eventBus, repository);

    ResourceException notFound = new ResourceException(404, "Group not found");
    when(repository.update(any(), any(), any(), any(), any())).thenThrow(notFound);

    assertThrows(
        ResourceException.class,
        () -> scimDispatcher.update("999", null, scimGroup(null, "engineering", null), null, null));

    ArgumentCaptor<BaseEvent> captor = ArgumentCaptor.forClass(BaseEvent.class);
    verify(eventBus, times(2)).dispatchEvent(captor.capture());
    List<BaseEvent> events = captor.getAllValues();
    assertInstanceOf(ScimAlterGroupPreEvent.class, events.get(0));
    assertInstanceOf(ScimAlterGroupFailureEvent.class, events.get(1));
    ScimAlterGroupFailureEvent failureEvent = (ScimAlterGroupFailureEvent) events.get(1);
    assertEquals(notFound, failureEvent.exception());
    assertEquals("999", failureEvent.resourceId());
    assertEquals("engineering", failureEvent.groupName());
  }

  @Test
  void testDeleteDispatchesFailure() throws Exception {
    @SuppressWarnings("unchecked")
    Repository<ScimGroup> repository = mock(Repository.class);
    EventBus eventBus = mock(EventBus.class);
    ScimGroupEventDispatcher scimDispatcher = new ScimGroupEventDispatcher(eventBus, repository);

    ResourceException notFound = new ResourceException(404, "Group not found");
    org.mockito.Mockito.doThrow(notFound).when(repository).delete("999");

    assertThrows(ResourceException.class, () -> scimDispatcher.delete("999"));

    ArgumentCaptor<BaseEvent> captor = ArgumentCaptor.forClass(BaseEvent.class);
    verify(eventBus, times(2)).dispatchEvent(captor.capture());
    List<BaseEvent> events = captor.getAllValues();
    assertInstanceOf(ScimRemoveGroupPreEvent.class, events.get(0));
    assertInstanceOf(ScimRemoveGroupFailureEvent.class, events.get(1));
    ScimRemoveGroupFailureEvent failureEvent = (ScimRemoveGroupFailureEvent) events.get(1);
    assertEquals(notFound, failureEvent.exception());
    assertEquals("999", failureEvent.resourceId());
    assertEquals("unknown", failureEvent.groupName());
  }

  private static ScimGroup scimGroup(String id, String groupName, String externalId) {
    ScimGroup group = new ScimGroup();
    group.setId(id);
    group.setDisplayName(groupName);
    group.setExternalId(externalId);
    return group;
  }
}
