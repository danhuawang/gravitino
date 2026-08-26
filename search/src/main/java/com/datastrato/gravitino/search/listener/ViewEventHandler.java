/*
 * Copyright 2024 Datastrato Pvt Ltd.
 */
package com.datastrato.gravitino.search.listener;

import com.datastrato.gravitino.search.service.SearchService;
import org.apache.gravitino.Entity.EntityType;
import org.apache.gravitino.NameIdentifier;
import org.apache.gravitino.listener.api.event.Event;
import org.apache.gravitino.listener.api.event.view.AlterViewEvent;
import org.apache.gravitino.listener.api.event.view.CreateViewEvent;
import org.apache.gravitino.listener.api.event.view.DropViewEvent;
import org.apache.gravitino.rel.ViewChange;

/** Keeps the indexed view metadata in sync with the view events emitted by the server. */
public class ViewEventHandler implements EventHandler {
  private final SearchService searchService;

  /**
   * Creates a handler writing into the given search service.
   *
   * @param searchService The service that indexes the view metadata.
   */
  public ViewEventHandler(SearchService searchService) {
    this.searchService = searchService;
  }

  @Override
  public void handleEvent(Event event) {
    NameIdentifier identifier = event.identifier();
    if (event instanceof CreateViewEvent) {
      searchService.synchronizeMetadata(identifier, EntityType.VIEW, false);
    } else if (event instanceof AlterViewEvent) {
      AlterViewEvent alterViewEvent = (AlterViewEvent) event;
      for (ViewChange change : alterViewEvent.viewChanges()) {
        if (change instanceof ViewChange.RenameView) {
          ViewChange.RenameView rename = (ViewChange.RenameView) change;
          identifier = NameIdentifier.of(identifier.namespace(), rename.getNewName());
        }
      }
      searchService.synchronizeMetadata(identifier, EntityType.VIEW, false);
    } else if (event instanceof DropViewEvent) {
      searchService.removeMetadata(identifier, EntityType.VIEW, false);
    }
  }
}
