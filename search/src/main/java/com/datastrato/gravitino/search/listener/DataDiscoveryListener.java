/*
 * Copyright 2024 Datastrato Inc.
 */
package com.datastrato.gravitino.search.listener;

import com.datastrato.gravitino.ExtendedDatastratoGravitinoEnv;
import com.datastrato.gravitino.search.service.SearchService;
import com.datastrato.gravitino.search.utils.IcebergEventUtils;
import com.google.common.collect.ImmutableMap;
import java.util.Map;
import org.apache.gravitino.listener.api.EventListenerPlugin;
import org.apache.gravitino.listener.api.event.CatalogEvent;
import org.apache.gravitino.listener.api.event.Event;
import org.apache.gravitino.listener.api.event.FilesetEvent;
import org.apache.gravitino.listener.api.event.GroupEvent;
import org.apache.gravitino.listener.api.event.ModelEvent;
import org.apache.gravitino.listener.api.event.OwnerEvent;
import org.apache.gravitino.listener.api.event.RoleEvent;
import org.apache.gravitino.listener.api.event.SchemaEvent;
import org.apache.gravitino.listener.api.event.TableEvent;
import org.apache.gravitino.listener.api.event.TagEvent;
import org.apache.gravitino.listener.api.event.TopicEvent;
import org.apache.gravitino.listener.api.event.UserEvent;
import org.apache.gravitino.listener.api.event.function.FunctionEvent;
import org.apache.gravitino.listener.api.event.policy.PolicyEvent;
import org.apache.gravitino.listener.api.event.view.ViewEvent;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class DataDiscoveryListener implements EventListenerPlugin {
  private static final Logger LOG = LoggerFactory.getLogger(DataDiscoveryListener.class);

  private SearchService searchService;
  private Map<Class, EventHandler> eventHandlers;
  private IcebergEventHandler icebergEventHandler;

  @Override
  public void init(Map<String, String> properties) throws RuntimeException {
    this.searchService = ExtendedDatastratoGravitinoEnv.getInstance().getSearchService();

    this.eventHandlers =
        ImmutableMap.<Class, EventHandler>builder()
            .put(TableEvent.class, new TableEventHandler(searchService))
            .put(ViewEvent.class, new ViewEventHandler(searchService))
            .put(FunctionEvent.class, new FunctionEventHandler(searchService))
            .put(SchemaEvent.class, new SchemaEventHandler(searchService))
            .put(CatalogEvent.class, new CatalogEventHandler(searchService))
            .put(TagEvent.class, new TagEventHandler(searchService))
            .put(TopicEvent.class, new TopicEventHandler(searchService))
            .put(FilesetEvent.class, new FilesetEventHandler(searchService))
            .put(ModelEvent.class, new ModelEventHandler(searchService))
            .put(PolicyEvent.class, new PolicyEventHandler(searchService))
            .put(OwnerEvent.class, new OwnerEventHandler(searchService))
            .put(UserEvent.class, new UserEventHandler(searchService))
            .put(GroupEvent.class, new GroupEventHandler(searchService))
            .put(RoleEvent.class, new RoleEventHandler(searchService))
            .build();

    this.icebergEventHandler = new IcebergEventHandler(searchService);
  }

  @Override
  public void start() throws RuntimeException {}

  @Override
  public void stop() throws RuntimeException {}

  @Override
  public Mode mode() {
    return Mode.ASYNC_ISOLATED;
  }

  @Override
  public void onPostEvent(Event event) {
    try {

      EventHandler handler = null;
      if (event instanceof TableEvent) {
        handler = eventHandlers.get(TableEvent.class);
      } else if (event instanceof ViewEvent) {
        handler = eventHandlers.get(ViewEvent.class);
      } else if (event instanceof FunctionEvent) {
        handler = eventHandlers.get(FunctionEvent.class);
      } else if (event instanceof SchemaEvent) {
        handler = eventHandlers.get(SchemaEvent.class);
      } else if (event instanceof CatalogEvent) {
        handler = eventHandlers.get(CatalogEvent.class);
      } else if (event instanceof TagEvent) {
        handler = eventHandlers.get(TagEvent.class);
      } else if (event instanceof TopicEvent) {
        handler = eventHandlers.get(TopicEvent.class);
      } else if (event instanceof FilesetEvent) {
        handler = eventHandlers.get(FilesetEvent.class);
      } else if (event instanceof ModelEvent) {
        handler = eventHandlers.get(ModelEvent.class);
      } else if (event instanceof OwnerEvent) {
        handler = eventHandlers.get(OwnerEvent.class);
      } else if (event instanceof UserEvent) {
        handler = eventHandlers.get(UserEvent.class);
      } else if (event instanceof GroupEvent) {
        handler = eventHandlers.get(GroupEvent.class);
      } else if (event instanceof RoleEvent) {
        handler = eventHandlers.get(RoleEvent.class);
      } else if (event instanceof PolicyEvent) {
        handler = eventHandlers.get(PolicyEvent.class);
      }

      // Iceberg REST events are dispatched from the isolated Iceberg REST auxiliary service
      // class loader. Their concrete types are not visible here, so check via class-name walk
      // before falling through to the instanceof chain for Gravitino-native events.
      if (IcebergEventUtils.isSubclassOf(event, IcebergEventUtils.ICEBERG_TABLE_EVENT_CLASS)
          || IcebergEventUtils.isSubclassOf(
              event, IcebergEventUtils.ICEBERG_NAMESPACE_EVENT_CLASS)) {
        handler = icebergEventHandler;
      }

      if (handler != null) {
        handler.handleEvent(event);
      }
    } catch (Exception e) {
      LOG.warn("Failed to handle event {}", event, e);
    }
  }
}
