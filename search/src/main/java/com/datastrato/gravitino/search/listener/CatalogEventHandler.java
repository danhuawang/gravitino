/*
 * Copyright 2024 Datastrato Inc.
 */
package com.datastrato.gravitino.search.listener;

import com.datastrato.gravitino.search.service.SearchService;
import org.apache.gravitino.CatalogChange;
import org.apache.gravitino.Entity;
import org.apache.gravitino.NameIdentifier;
import org.apache.gravitino.listener.api.event.AlterCatalogEvent;
import org.apache.gravitino.listener.api.event.CreateCatalogEvent;
import org.apache.gravitino.listener.api.event.DropCatalogEvent;
import org.apache.gravitino.listener.api.event.Event;

public class CatalogEventHandler implements EventHandler {
  private final SearchService searchService;

  public CatalogEventHandler(SearchService searchService) {
    this.searchService = searchService;
  }

  @Override
  public void handleEvent(Event event) {
    if (event instanceof CreateCatalogEvent) {
      searchService.synchronizeMetadata(event.identifier(), Entity.EntityType.CATALOG, true);

    } else if (event instanceof AlterCatalogEvent) {
      AlterCatalogEvent alterCatalog = (AlterCatalogEvent) event;
      NameIdentifier identifier = alterCatalog.identifier();
      boolean isRename = false;

      // Handle rename catalog operation
      for (CatalogChange change : alterCatalog.catalogChanges()) {
        if (change instanceof CatalogChange.RenameCatalog) {
          CatalogChange.RenameCatalog rename = (CatalogChange.RenameCatalog) change;
          identifier = NameIdentifier.of(identifier.namespace(), rename.getNewName());
          isRename = true;
        }
      }

      boolean cascade = isRename;
      searchService.synchronizeMetadata(identifier, Entity.EntityType.CATALOG, cascade);
    } else if (event instanceof DropCatalogEvent) {
      searchService.removeMetadata(event.identifier(), Entity.EntityType.CATALOG, true);
    }
  }
}
