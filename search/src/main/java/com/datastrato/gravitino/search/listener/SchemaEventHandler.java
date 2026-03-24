/*
 * Copyright 2024 Datastrato Pvt Ltd.
 * This software is licensed under the Apache License version 2.
 */
package com.datastrato.gravitino.search.listener;

import com.datastrato.gravitino.search.service.SearchService;
import org.apache.gravitino.Entity;
import org.apache.gravitino.listener.api.event.AlterSchemaEvent;
import org.apache.gravitino.listener.api.event.CreateSchemaEvent;
import org.apache.gravitino.listener.api.event.DropSchemaEvent;
import org.apache.gravitino.listener.api.event.Event;
import org.apache.gravitino.listener.api.event.IcebergNamespaceEvent;

public class SchemaEventHandler implements EventHandler {
  private final SearchService searchService;

  public SchemaEventHandler(SearchService searchService) {
    this.searchService = searchService;
  }

  @Override
  public void handleEvent(Event event) {
    // Gravitino events: use instanceof for type-safe dispatch
    if (event instanceof CreateSchemaEvent) {
      searchService.synchronizeMetadata(event.identifier(), Entity.EntityType.SCHEMA, false);
    } else if (event instanceof AlterSchemaEvent) {
      searchService.synchronizeMetadata(event.identifier(), Entity.EntityType.SCHEMA, false);
    } else if (event instanceof DropSchemaEvent) {
      searchService.removeMetadata(event.identifier(), Entity.EntityType.SCHEMA, true);

      // Iceberg namespace events map to Gravitino schemas; dispatch by operationType
    } else if (event instanceof IcebergNamespaceEvent) {
      switch (event.operationType()) {
        case CREATE_SCHEMA:
        case ALTER_SCHEMA:
          searchService.synchronizeMetadata(event.identifier(), Entity.EntityType.SCHEMA, false);
          break;
        case DROP_SCHEMA:
          searchService.removeMetadata(event.identifier(), Entity.EntityType.SCHEMA, true);
          break;
        default:
          break;
      }
    }
  }
}
