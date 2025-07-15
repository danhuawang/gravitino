package com.datastrato.gravitino.search.listener;
/*
 * Copyright 2024 Datastrato Pvt Ltd.
 * This software is licensed under the Apache License version 2.
 */

import static org.apache.gravitino.utils.MetadataObjectUtil.toEntityType;

import com.datastrato.gravitino.search.service.SearchService;
import org.apache.gravitino.Entity;
import org.apache.gravitino.NameIdentifier;
import org.apache.gravitino.listener.api.event.Event;
import org.apache.gravitino.listener.api.event.SetOwnerEvent;

public class OwnerEventHandler implements EventHandler {
  private final SearchService searchService;

  public OwnerEventHandler(SearchService searchService) {
    this.searchService = searchService;
  }

  @Override
  public void handleEvent(Event event) {
    if (event instanceof SetOwnerEvent) {
      SetOwnerEvent setOwnerEvent = (SetOwnerEvent) event;
      NameIdentifier identifier = setOwnerEvent.identifier();
      Entity.EntityType type = toEntityType(setOwnerEvent.metadataObjectType());

      // Change the owner of parents' metadata will not affect children's metadata.
      searchService.synchronizeMetadata(identifier, type, false);
    }
  }
}
