/*
 * Copyright 2026 Datastrato Pvt Ltd.
 * This software is licensed under the Apache License version 2.
 */
package com.datastrato.gravitino.search.listener;

import com.datastrato.gravitino.search.service.SearchService;
import com.google.common.collect.ImmutableList;
import org.apache.gravitino.Entity.EntityType;
import org.apache.gravitino.NameIdentifier;
import org.apache.gravitino.authorization.User;
import org.apache.gravitino.listener.api.event.AddUserEvent;
import org.apache.gravitino.listener.api.event.AlterUserEvent;
import org.apache.gravitino.listener.api.event.RemoveUserByExternalIdEvent;
import org.apache.gravitino.listener.api.event.RemoveUserByIdEvent;
import org.apache.gravitino.listener.api.event.RemoveUserEvent;
import org.apache.gravitino.listener.api.info.UserInfo;
import org.apache.gravitino.utils.NameIdentifierUtil;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

class TestUserEventHandler {
  private static final String METALAKE = "test";
  private static final String USER_NAME = "alice";

  private SearchService searchService;
  private UserEventHandler handler;
  private UserInfo userInfo;

  @BeforeEach
  void setUp() {
    searchService = Mockito.mock(SearchService.class);
    handler = new UserEventHandler(searchService);
    User user = Mockito.mock(User.class);
    Mockito.when(user.id()).thenReturn(100L);
    Mockito.when(user.name()).thenReturn(USER_NAME);
    Mockito.when(user.roles()).thenReturn(ImmutableList.of());
    userInfo = new UserInfo(user);
  }

  @Test
  void testAddAndAlterUserAreSynchronizedByName() {
    handler.handleEvent(new AddUserEvent("tester", METALAKE, userInfo));
    handler.handleEvent(new AlterUserEvent("tester", METALAKE, null, userInfo));

    Mockito.verify(searchService, Mockito.times(2))
        .synchronizeMetadata(
            NameIdentifierUtil.ofUser(METALAKE, USER_NAME), EntityType.USER, false);
  }

  @Test
  void testRemoveUserByNameIsScopedToUserType() {
    handler.handleEvent(new RemoveUserEvent("tester", METALAKE, USER_NAME, true));

    Mockito.verify(searchService).removeEntityByName(METALAKE, USER_NAME, EntityType.USER);
  }

  @Test
  void testRemoveUserByIdDeletesDocumentDirectly() {
    handler.handleEvent(new RemoveUserByIdEvent("tester", METALAKE, 100L, true));

    Mockito.verify(searchService).delete(METALAKE, ImmutableList.of(100L), EntityType.USER);
  }

  @Test
  void testRemoveUserByExternalIdReconcilesMetalake() {
    handler.handleEvent(new RemoveUserByExternalIdEvent("tester", METALAKE, "external", true));

    Mockito.verify(searchService)
        .synchronizeMetadata(NameIdentifier.of(METALAKE), EntityType.METALAKE, true);
  }

  @Test
  void testNoOpRemoveEventsAreIgnored() {
    handler.handleEvent(new RemoveUserEvent("tester", METALAKE, USER_NAME, false));
    handler.handleEvent(new RemoveUserByIdEvent("tester", METALAKE, 100L, false));
    handler.handleEvent(new RemoveUserByExternalIdEvent("tester", METALAKE, "external", false));

    Mockito.verifyNoInteractions(searchService);
  }
}
