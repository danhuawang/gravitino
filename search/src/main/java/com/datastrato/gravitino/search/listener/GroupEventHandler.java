/*
 * Copyright 2026 Datastrato Pvt Ltd.
 * This software is licensed under the Apache License version 2.
 */
package com.datastrato.gravitino.search.listener;

import com.datastrato.gravitino.search.service.SearchService;
import com.google.common.collect.ImmutableList;
import org.apache.gravitino.Entity.EntityType;
import org.apache.gravitino.NameIdentifier;
import org.apache.gravitino.listener.api.event.AddGroupEvent;
import org.apache.gravitino.listener.api.event.AlterGroupEvent;
import org.apache.gravitino.listener.api.event.Event;
import org.apache.gravitino.listener.api.event.RemoveGroupByExternalIdEvent;
import org.apache.gravitino.listener.api.event.RemoveGroupByIdEvent;
import org.apache.gravitino.listener.api.event.RemoveGroupEvent;
import org.apache.gravitino.utils.NameIdentifierUtil;

/** Keeps the lightweight Group search projection synchronized with Group mutation events. */
public class GroupEventHandler implements EventHandler {
  private final SearchService searchService;

  /**
   * Creates a Group event handler.
   *
   * @param searchService The search service to update.
   */
  public GroupEventHandler(SearchService searchService) {
    this.searchService = searchService;
  }

  @Override
  public void handleEvent(Event event) {
    String metalake = event.identifier().namespace().level(0);
    if (event instanceof AddGroupEvent) {
      AddGroupEvent addGroupEvent = (AddGroupEvent) event;
      synchronize(metalake, addGroupEvent.addedGroupInfo().name());
    } else if (event instanceof AlterGroupEvent) {
      AlterGroupEvent alterGroupEvent = (AlterGroupEvent) event;
      synchronize(metalake, alterGroupEvent.updatedGroupInfo().name());
    } else if (event instanceof RemoveGroupEvent) {
      RemoveGroupEvent removeGroupEvent = (RemoveGroupEvent) event;
      if (removeGroupEvent.isExists()) {
        searchService.removeEntityByName(
            metalake, removeGroupEvent.removedGroupName(), EntityType.GROUP);
      }
    } else if (event instanceof RemoveGroupByIdEvent) {
      RemoveGroupByIdEvent removeGroupEvent = (RemoveGroupByIdEvent) event;
      if (removeGroupEvent.isExists()) {
        searchService.delete(
            metalake, ImmutableList.of(removeGroupEvent.groupId()), EntityType.GROUP);
      }
    } else if (event instanceof RemoveGroupByExternalIdEvent) {
      RemoveGroupByExternalIdEvent removeGroupEvent = (RemoveGroupByExternalIdEvent) event;
      if (removeGroupEvent.isExists()) {
        searchService.synchronizeMetadata(NameIdentifier.of(metalake), EntityType.METALAKE, true);
      }
    }
  }

  private void synchronize(String metalake, String groupName) {
    searchService.synchronizeMetadata(
        NameIdentifierUtil.ofGroup(metalake, groupName), EntityType.GROUP, false);
  }
}
