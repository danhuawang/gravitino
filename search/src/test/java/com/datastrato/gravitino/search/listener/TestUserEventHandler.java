/*
 * Copyright 2026 Datastrato Inc.
 */
package com.datastrato.gravitino.search.listener;

import com.datastrato.gravitino.search.service.SearchService;
import com.google.common.collect.ImmutableList;
import org.apache.gravitino.Entity.EntityType;
import org.apache.gravitino.NameIdentifier;
import org.apache.gravitino.authorization.User;
import org.apache.gravitino.listener.api.event.AddUserEvent;
import org.apache.gravitino.listener.api.event.GrantUserRolesEvent;
import org.apache.gravitino.listener.api.event.RemoveUserEvent;
import org.apache.gravitino.listener.api.event.RevokeUserRolesEvent;
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
  void testAddUserIsSynchronizedByName() {
    handler.handleEvent(new AddUserEvent("tester", METALAKE, userInfo));

    Mockito.verify(searchService)
        .synchronizeMetadata(
            NameIdentifierUtil.ofUser(METALAKE, USER_NAME), EntityType.USER, false);
  }

  @Test
  void testRemoveUserByNameIsScopedToUserType() {
    handler.handleEvent(new RemoveUserEvent("tester", METALAKE, USER_NAME, true));

    Mockito.verify(searchService).removeEntityByName(METALAKE, USER_NAME, EntityType.USER);
    Mockito.verify(searchService)
        .synchronizeMetadata(NameIdentifier.of(METALAKE), EntityType.METALAKE, true);
  }

  @Test
  void testNoOpRemoveEventsAreIgnored() {
    handler.handleEvent(new RemoveUserEvent("tester", METALAKE, USER_NAME, false));

    Mockito.verifyNoInteractions(searchService);
  }

  @Test
  void testUserRoleChangesReconcilePermissionDocuments() {
    handler.handleEvent(
        new GrantUserRolesEvent("tester", METALAKE, userInfo, ImmutableList.of("reader")));
    handler.handleEvent(
        new RevokeUserRolesEvent("tester", METALAKE, userInfo, ImmutableList.of("reader")));

    Mockito.verify(searchService, Mockito.times(2))
        .synchronizeMetadata(NameIdentifier.of(METALAKE), EntityType.METALAKE, true);
  }
}
