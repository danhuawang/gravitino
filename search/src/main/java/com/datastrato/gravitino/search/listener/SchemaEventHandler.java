/*
 * Copyright 2024 Datastrato Inc.
 */
package com.datastrato.gravitino.search.listener;

import com.datastrato.gravitino.search.service.SearchService;
import org.apache.gravitino.Entity;
import org.apache.gravitino.listener.api.event.AlterSchemaEvent;
import org.apache.gravitino.listener.api.event.CreateSchemaEvent;
import org.apache.gravitino.listener.api.event.DropSchemaEvent;
import org.apache.gravitino.listener.api.event.Event;

public class SchemaEventHandler implements EventHandler {
  private final SearchService searchService;

  public SchemaEventHandler(SearchService searchService) {
    this.searchService = searchService;
  }

  @Override
  public void handleEvent(Event event) {
    if (event instanceof CreateSchemaEvent) {
      searchService.synchronizeMetadata(event.identifier(), Entity.EntityType.SCHEMA, false);

    } else if (event instanceof AlterSchemaEvent) {
      searchService.synchronizeMetadata(event.identifier(), Entity.EntityType.SCHEMA, false);

    } else if (event instanceof DropSchemaEvent) {
      searchService.removeMetadata(event.identifier(), Entity.EntityType.SCHEMA, true);
    }
  }
}
