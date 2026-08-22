/*
 * Copyright 2026 Datastrato Pvt Ltd.
 */
package com.datastrato.gravitino.search.listener;

import com.datastrato.gravitino.search.service.SearchService;
import com.google.common.collect.ImmutableList;
import org.apache.gravitino.Entity.EntityType;
import org.apache.gravitino.NameIdentifier;
import org.apache.gravitino.authorization.Group;
import org.apache.gravitino.listener.api.event.AddGroupEvent;
import org.apache.gravitino.listener.api.event.AlterGroupEvent;
import org.apache.gravitino.listener.api.event.RemoveGroupByExternalIdEvent;
import org.apache.gravitino.listener.api.event.RemoveGroupByIdEvent;
import org.apache.gravitino.listener.api.event.RemoveGroupEvent;
import org.apache.gravitino.listener.api.info.GroupInfo;
import org.apache.gravitino.utils.NameIdentifierUtil;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

class TestGroupEventHandler {
  private static final String METALAKE = "test";
  private static final String GROUP_NAME = "engineers";

  private SearchService searchService;
  private GroupEventHandler handler;
  private GroupInfo groupInfo;

  @BeforeEach
  void setUp() {
    searchService = Mockito.mock(SearchService.class);
    handler = new GroupEventHandler(searchService);
    Group group = Mockito.mock(Group.class);
    Mockito.when(group.id()).thenReturn(101L);
    Mockito.when(group.name()).thenReturn(GROUP_NAME);
    Mockito.when(group.roles()).thenReturn(ImmutableList.of());
    groupInfo = new GroupInfo(group);
  }

  @Test
  void testAddAndAlterGroupAreSynchronizedByName() {
    handler.handleEvent(new AddGroupEvent("tester", METALAKE, groupInfo));
    handler.handleEvent(new AlterGroupEvent("tester", METALAKE, null, groupInfo));

    Mockito.verify(searchService, Mockito.times(2))
        .synchronizeMetadata(
            NameIdentifierUtil.ofGroup(METALAKE, GROUP_NAME), EntityType.GROUP, false);
  }

  @Test
  void testRemoveGroupByNameIsScopedToGroupType() {
    handler.handleEvent(new RemoveGroupEvent("tester", METALAKE, GROUP_NAME, true));

    Mockito.verify(searchService).removeEntityByName(METALAKE, GROUP_NAME, EntityType.GROUP);
  }

  @Test
  void testRemoveGroupByIdDeletesDocumentDirectly() {
    handler.handleEvent(new RemoveGroupByIdEvent("tester", METALAKE, 101L, true));

    Mockito.verify(searchService).delete(METALAKE, ImmutableList.of(101L), EntityType.GROUP);
  }

  @Test
  void testRemoveGroupByExternalIdReconcilesMetalake() {
    handler.handleEvent(new RemoveGroupByExternalIdEvent("tester", METALAKE, "external", true));

    Mockito.verify(searchService)
        .synchronizeMetadata(NameIdentifier.of(METALAKE), EntityType.METALAKE, true);
  }

  @Test
  void testNoOpRemoveEventsAreIgnored() {
    handler.handleEvent(new RemoveGroupEvent("tester", METALAKE, GROUP_NAME, false));
    handler.handleEvent(new RemoveGroupByIdEvent("tester", METALAKE, 101L, false));
    handler.handleEvent(new RemoveGroupByExternalIdEvent("tester", METALAKE, "external", false));

    Mockito.verifyNoInteractions(searchService);
  }
}
