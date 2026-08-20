/*
 * Copyright 2024 Datastrato Pvt Ltd.
 * This software is licensed under the Apache License version 2.
 */
package com.datastrato.gravitino.search.listener;

import com.datastrato.gravitino.search.service.SearchService;
import org.apache.gravitino.Entity.EntityType;
import org.apache.gravitino.NameIdentifier;
import org.apache.gravitino.listener.api.event.Event;
import org.apache.gravitino.listener.api.event.function.AlterFunctionEvent;
import org.apache.gravitino.listener.api.event.function.DropFunctionEvent;
import org.apache.gravitino.listener.api.event.function.RegisterFunctionEvent;

/** Keeps the indexed function metadata in sync with the function events emitted by the server. */
public class FunctionEventHandler implements EventHandler {
  private final SearchService searchService;

  /**
   * Creates a handler writing into the given search service.
   *
   * @param searchService The service that indexes the function metadata.
   */
  public FunctionEventHandler(SearchService searchService) {
    this.searchService = searchService;
  }

  @Override
  public void handleEvent(Event event) {
    NameIdentifier identifier = event.identifier();
    // Functions cannot be renamed, so an alteration always keeps the original identifier.
    if (event instanceof RegisterFunctionEvent || event instanceof AlterFunctionEvent) {
      searchService.synchronizeMetadata(identifier, EntityType.FUNCTION, false);
    } else if (event instanceof DropFunctionEvent) {
      searchService.removeMetadata(identifier, EntityType.FUNCTION, false);
    }
  }
}
