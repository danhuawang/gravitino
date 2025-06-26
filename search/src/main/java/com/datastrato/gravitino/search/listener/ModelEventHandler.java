/*
 * Copyright 2024 Datastrato Pvt Ltd.
 * This software is licensed under the Apache License version 2.
 */
package com.datastrato.gravitino.search.listener;

import com.datastrato.gravitino.search.service.SearchService;
import org.apache.gravitino.Entity.EntityType;
import org.apache.gravitino.NameIdentifier;
import org.apache.gravitino.listener.api.event.AlterModelEvent;
import org.apache.gravitino.listener.api.event.AlterModelVersionEvent;
import org.apache.gravitino.listener.api.event.DeleteModelEvent;
import org.apache.gravitino.listener.api.event.DeleteModelVersionEvent;
import org.apache.gravitino.listener.api.event.Event;
import org.apache.gravitino.listener.api.event.LinkModelVersionEvent;
import org.apache.gravitino.listener.api.event.RegisterAndLinkModelEvent;
import org.apache.gravitino.listener.api.event.RegisterModelEvent;
import org.apache.gravitino.model.ModelChange;

public class ModelEventHandler implements EventHandler {
  private final SearchService searchService;

  public ModelEventHandler(SearchService searchService) {
    this.searchService = searchService;
  }

  @Override
  public void handleEvent(Event event) {
    NameIdentifier identifier = event.identifier();
    if (shouldRefreshModel(event)) {
      searchService.synchronizeMetadata(identifier, EntityType.MODEL, false);
    } else if (event instanceof AlterModelEvent) {
      AlterModelEvent alterModelEvent = (AlterModelEvent) event;
      for (ModelChange change : alterModelEvent.modelChanges()) {
        if (change instanceof ModelChange.RenameModel) {
          ModelChange.RenameModel rename = (ModelChange.RenameModel) change;
          identifier = NameIdentifier.of(identifier.namespace(), rename.newName());
        }
      }
      searchService.synchronizeMetadata(identifier, EntityType.MODEL, false);
    } else if (event instanceof DeleteModelEvent) {
      searchService.removeMetadata(identifier, EntityType.MODEL, false);
    }
  }

  private boolean shouldRefreshModel(Event event) {
    return event instanceof RegisterModelEvent
        || event instanceof RegisterAndLinkModelEvent
        || event instanceof LinkModelVersionEvent
        || event instanceof AlterModelVersionEvent
        || event instanceof DeleteModelVersionEvent;
  }
}
