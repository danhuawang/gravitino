/*
 * Copyright 2026 Datastrato Pvt Ltd.
 * This software is licensed under the Apache License version 2.
 */
package com.datastrato.gravitino.search.listener;

import com.datastrato.gravitino.search.service.SearchService;
import com.datastrato.gravitino.search.utils.PermissionProjectionCache;
import com.google.common.collect.ImmutableList;
import org.apache.gravitino.Entity.EntityType;
import org.apache.gravitino.NameIdentifier;
import org.apache.gravitino.listener.api.event.AddUserEvent;
import org.apache.gravitino.listener.api.event.AlterUserEvent;
import org.apache.gravitino.listener.api.event.Event;
import org.apache.gravitino.listener.api.event.GrantUserRolesEvent;
import org.apache.gravitino.listener.api.event.RemoveUserByExternalIdEvent;
import org.apache.gravitino.listener.api.event.RemoveUserByIdEvent;
import org.apache.gravitino.listener.api.event.RemoveUserEvent;
import org.apache.gravitino.listener.api.event.RevokeUserRolesEvent;
import org.apache.gravitino.utils.NameIdentifierUtil;

/** Keeps the lightweight User search projection synchronized with User mutation events. */
public class UserEventHandler implements EventHandler {
  private final SearchService searchService;

  /**
   * Creates a User event handler.
   *
   * @param searchService The search service to update.
   */
  public UserEventHandler(SearchService searchService) {
    this.searchService = searchService;
  }

  @Override
  public void handleEvent(Event event) {
    String metalake = event.identifier().namespace().level(0);
    if (event instanceof AddUserEvent) {
      AddUserEvent addUserEvent = (AddUserEvent) event;
      synchronize(metalake, addUserEvent.addedUserInfo().name());
    } else if (event instanceof AlterUserEvent) {
      AlterUserEvent alterUserEvent = (AlterUserEvent) event;
      synchronize(metalake, alterUserEvent.updatedUserInfo().name());
    } else if (event instanceof RemoveUserEvent) {
      RemoveUserEvent removeUserEvent = (RemoveUserEvent) event;
      if (removeUserEvent.isExists()) {
        searchService.removeEntityByName(
            metalake, removeUserEvent.removedUserName(), EntityType.USER);
        reconcilePermissions(metalake);
      }
    } else if (event instanceof RemoveUserByIdEvent) {
      RemoveUserByIdEvent removeUserEvent = (RemoveUserByIdEvent) event;
      if (removeUserEvent.isExists()) {
        searchService.delete(metalake, ImmutableList.of(removeUserEvent.userId()), EntityType.USER);
        reconcilePermissions(metalake);
      }
    } else if (event instanceof RemoveUserByExternalIdEvent) {
      RemoveUserByExternalIdEvent removeUserEvent = (RemoveUserByExternalIdEvent) event;
      if (removeUserEvent.isExists()) {
        reconcilePermissions(metalake);
      }
    } else if (event instanceof GrantUserRolesEvent || event instanceof RevokeUserRolesEvent) {
      reconcilePermissions(metalake);
    }
  }

  private void synchronize(String metalake, String userName) {
    searchService.synchronizeMetadata(
        NameIdentifierUtil.ofUser(metalake, userName), EntityType.USER, false);
  }

  private void reconcilePermissions(String metalake) {
    PermissionProjectionCache.invalidate(metalake);
    searchService.synchronizeMetadata(NameIdentifier.of(metalake), EntityType.METALAKE, true);
  }
}
