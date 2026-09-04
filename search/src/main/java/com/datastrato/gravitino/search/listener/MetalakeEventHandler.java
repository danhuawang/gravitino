/*
 * Copyright 2026 Datastrato Inc.
 */
package com.datastrato.gravitino.search.listener;

import com.datastrato.gravitino.search.service.SearchService;
import org.apache.gravitino.Entity.EntityType;
import org.apache.gravitino.MetalakeChange;
import org.apache.gravitino.NameIdentifier;
import org.apache.gravitino.listener.api.event.AlterMetalakeEvent;
import org.apache.gravitino.listener.api.event.CreateMetalakeEvent;
import org.apache.gravitino.listener.api.event.DisableMetalakeEvent;
import org.apache.gravitino.listener.api.event.DropMetalakeEvent;
import org.apache.gravitino.listener.api.event.EnableMetalakeEvent;
import org.apache.gravitino.listener.api.event.Event;

/** Keeps all search indices belonging to a metalake synchronized with metalake lifecycle events. */
public class MetalakeEventHandler implements EventHandler {
  private final SearchService searchService;

  /**
   * Creates a metalake event handler.
   *
   * @param searchService The search service to update.
   */
  public MetalakeEventHandler(SearchService searchService) {
    this.searchService = searchService;
  }

  @Override
  public void handleEvent(Event event) {
    NameIdentifier identifier = event.identifier();
    if (event instanceof CreateMetalakeEvent) {
      searchService.synchronizeMetalake(identifier);
    } else if (event instanceof DropMetalakeEvent) {
      searchService.removeMetadata(identifier, EntityType.METALAKE, true);
    } else if (event instanceof DisableMetalakeEvent) {
      searchService.updateMetalakeInUse(identifier, false);
    } else if (event instanceof EnableMetalakeEvent) {
      searchService.synchronizeMetalake(identifier);
    } else if (event instanceof AlterMetalakeEvent) {
      handleAlterMetalake((AlterMetalakeEvent) event);
    }
  }

  private void handleAlterMetalake(AlterMetalakeEvent event) {
    for (MetalakeChange change : event.metalakeChanges()) {
      if (change instanceof MetalakeChange.RenameMetalake) {
        String newName = ((MetalakeChange.RenameMetalake) change).getNewName();
        searchService.removeMetadata(event.identifier(), EntityType.METALAKE, true);
        searchService.synchronizeMetalake(NameIdentifier.of(newName));
        return;
      }
    }
  }
}
