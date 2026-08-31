/*
 * Copyright 2024 Datastrato Inc.
 */
package com.datastrato.gravitino.search.listener;

import com.datastrato.gravitino.search.service.SearchService;
import org.apache.gravitino.Entity.EntityType;
import org.apache.gravitino.NameIdentifier;
import org.apache.gravitino.listener.api.event.AlterTopicEvent;
import org.apache.gravitino.listener.api.event.CreateTopicEvent;
import org.apache.gravitino.listener.api.event.DropTopicEvent;
import org.apache.gravitino.listener.api.event.Event;

public class TopicEventHandler implements EventHandler {
  private final SearchService searchService;

  public TopicEventHandler(SearchService searchService) {
    this.searchService = searchService;
  }

  @Override
  public void handleEvent(Event event) {
    NameIdentifier identifier = event.identifier();
    if (event instanceof CreateTopicEvent || event instanceof AlterTopicEvent) {
      searchService.synchronizeMetadata(identifier, EntityType.TOPIC, false);
    } else if (event instanceof DropTopicEvent) {
      searchService.removeMetadata(identifier, EntityType.TOPIC, false);
    }
  }
}
