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
import org.apache.gravitino.listener.api.event.IcebergRenameTableEvent;
import org.apache.gravitino.listener.api.event.IcebergTableEvent;
import org.apache.gravitino.rel.TableChange;
import org.apache.gravitino.utils.NameIdentifierUtil;
import org.apache.iceberg.catalog.TableIdentifier;

public class TableEventHandler implements EventHandler {

  private final SearchService searchService;

  public TableEventHandler(SearchService searchService) {
    this.searchService = searchService;
  }

  @Override
  public void handleEvent(Event event) {
    NameIdentifier identifier = event.identifier();

    // Gravitino events: use instanceof to access type-specific data (e.g. tableChanges)
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

      // Iceberg events: all extend IcebergTableEvent; dispatch by operationType
    } else if (event instanceof IcebergTableEvent) {
      handleIcebergTableEvent(event, identifier);
    }
  }

  private void handleIcebergTableEvent(Event event, NameIdentifier identifier) {
    switch (event.operationType()) {
      case CREATE_TABLE:
      case REGISTER_TABLE:
      case ALTER_TABLE:
        searchService.synchronizeMetadata(identifier, EntityType.TABLE, false);
        break;
      case RENAME_TABLE:
        IcebergRenameTableEvent renameEvent = (IcebergRenameTableEvent) event;
        TableIdentifier destination = renameEvent.renameTableRequest().destination();

        // Reconstruct the full Gravitino NameIdentifier for the destination table.
        // Gravitino layout:  [metalake, catalog, <iceberg-namespace-levels...>, table-name]
        // The Iceberg namespace levels map to everything after catalog (i.e., schema and beyond).
        // A rename can change both the table name AND the schema (cross-schema rename).
        NameIdentifier newIdentifier =
            NameIdentifierUtil.ofTable(
                identifier.namespace().level(0),
                identifier.namespace().level(1),
                destination.namespace().level(0),
                destination.name());

        searchService.removeMetadata(identifier, EntityType.TABLE, false);
        searchService.synchronizeMetadata(newIdentifier, EntityType.TABLE, false);
        break;
      case DROP_TABLE:
        searchService.removeMetadata(identifier, EntityType.TABLE, false);
        break;
      default:
        break;
    }
  }
}
