/*
 * Copyright 2024 Datastrato Pvt Ltd.
 * This software is licensed under the Apache License version 2.
 */
package com.datastrato.gravitino.search.listener;

import com.datastrato.gravitino.search.service.SearchService;
import org.apache.gravitino.Entity.EntityType;
import org.apache.gravitino.NameIdentifier;
import org.apache.gravitino.listener.api.event.AlterTableEvent;
import org.apache.gravitino.listener.api.event.CreateTableEvent;
import org.apache.gravitino.listener.api.event.DropTableEvent;
import org.apache.gravitino.listener.api.event.Event;
import org.apache.gravitino.rel.TableChange;

public class TableEventHandler implements EventHandler {
  private final SearchService searchService;

  public TableEventHandler(SearchService searchService) {
    this.searchService = searchService;
  }

  @Override
  public void handleEvent(Event event) {
    NameIdentifier identifier = event.identifier();
    if (event instanceof CreateTableEvent) {
      searchService.synchronizeMetadata(identifier, EntityType.TABLE, false);
    } else if (event instanceof AlterTableEvent) {
      AlterTableEvent alterTableEvent = (AlterTableEvent) event;
      for (TableChange change : alterTableEvent.tableChanges()) {
        if (change instanceof TableChange.RenameTable) {
          TableChange.RenameTable rename = (TableChange.RenameTable) change;
          identifier = NameIdentifier.of(identifier.namespace(), rename.getNewName());
        }
      }
      searchService.synchronizeMetadata(identifier, EntityType.TABLE, false);
    } else if (event instanceof DropTableEvent) {
      searchService.removeMetadata(identifier, EntityType.TABLE, false);
    }
  }
}
