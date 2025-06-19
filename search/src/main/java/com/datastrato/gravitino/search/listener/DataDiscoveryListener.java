/*
 * Copyright 2024 Datastrato Pvt Ltd.
 * This software is licensed under the Apache License version 2.
 */
package com.datastrato.gravitino.search.listener;

import com.datastrato.gravitino.ExtendedDatastratoGravitinoEnv;
import com.datastrato.gravitino.search.service.SearchService;
import com.google.common.collect.ImmutableMap;
import java.util.Map;
import org.apache.gravitino.listener.api.EventListenerPlugin;
import org.apache.gravitino.listener.api.event.CatalogEvent;
import org.apache.gravitino.listener.api.event.Event;
import org.apache.gravitino.listener.api.event.SchemaEvent;
import org.apache.gravitino.listener.api.event.TableEvent;
import org.apache.gravitino.listener.api.event.TagEvent;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class DataDiscoveryListener implements EventListenerPlugin {
  private static final Logger LOG = LoggerFactory.getLogger(DataDiscoveryListener.class);

  private SearchService searchService;
  private Map<Class, EventHandler> eventHandlers;

  @Override
  public void init(Map<String, String> properties) throws RuntimeException {
    this.searchService = ExtendedDatastratoGravitinoEnv.getInstance().getSearchService();

    this.eventHandlers =
        ImmutableMap.of(
            TableEvent.class, new TableEventHandler(searchService),
            SchemaEvent.class, new SchemaEventHandler(searchService),
            CatalogEvent.class, new CatalogEventHandler(searchService),
            TagEvent.class, new TagEventHandler(searchService));
  }

  @Override
  public void start() throws RuntimeException {}

  @Override
  public void stop() throws RuntimeException {}

  @Override
  public void onPostEvent(Event event) {
    try {
      EventHandler handler = null;
      if (event instanceof TableEvent) {
        handler = eventHandlers.get(TableEvent.class);
      } else if (event instanceof SchemaEvent) {
        handler = eventHandlers.get(SchemaEvent.class);
      } else if (event instanceof CatalogEvent) {
        handler = eventHandlers.get(CatalogEvent.class);
      } else if (event instanceof TagEvent) {
        handler = eventHandlers.get(TableEvent.class);
      }
      if (handler != null) {
        handler.handleEvent(event);
      }
    } catch (Exception e) {
      LOG.warn("Failed to handle event {}", event, e);
    }
  }
}
