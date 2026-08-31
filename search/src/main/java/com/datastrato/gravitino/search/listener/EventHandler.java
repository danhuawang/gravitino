/*
 * Copyright 2024 Datastrato Inc.
 */
package com.datastrato.gravitino.search.listener;

import org.apache.gravitino.listener.api.event.Event;

/**
 * Interface for handling events in the Gravitino search module. Implementations of this interface
 * should define how to process different types of events.
 */
public interface EventHandler {

  /**
   * Handles the given event.
   *
   * @param event the event to handle
   */
  void handleEvent(Event event);
}
