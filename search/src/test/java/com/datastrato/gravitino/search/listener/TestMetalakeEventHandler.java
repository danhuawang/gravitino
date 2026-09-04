/*
 * Copyright 2026 Datastrato Inc.
 */
package com.datastrato.gravitino.search.listener;

import com.datastrato.gravitino.search.service.SearchService;
import com.google.common.collect.ImmutableMap;
import java.lang.reflect.Field;
import org.apache.gravitino.Entity.EntityType;
import org.apache.gravitino.MetalakeChange;
import org.apache.gravitino.NameIdentifier;
import org.apache.gravitino.listener.api.event.AlterMetalakeEvent;
import org.apache.gravitino.listener.api.event.CreateMetalakeEvent;
import org.apache.gravitino.listener.api.event.DisableMetalakeEvent;
import org.apache.gravitino.listener.api.event.DropMetalakeEvent;
import org.apache.gravitino.listener.api.event.EnableMetalakeEvent;
import org.apache.gravitino.listener.api.event.MetalakeEvent;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

class TestMetalakeEventHandler {
  private static final String USER = "tester";
  private static final NameIdentifier METALAKE_IDENTIFIER = NameIdentifier.of("metalake");

  private SearchService searchService;
  private MetalakeEventHandler handler;

  @BeforeEach
  void setUp() {
    searchService = Mockito.mock(SearchService.class);
    handler = new MetalakeEventHandler(searchService);
  }

  @Test
  void testCreateMetalakeQueuesFullSynchronization() {
    handler.handleEvent(new CreateMetalakeEvent(USER, METALAKE_IDENTIFIER, null));

    Mockito.verify(searchService).synchronizeMetalake(METALAKE_IDENTIFIER);
    Mockito.verifyNoMoreInteractions(searchService);
  }

  @Test
  void testDropMetalakeDeletesItsSearchIndices() {
    handler.handleEvent(new DropMetalakeEvent(USER, METALAKE_IDENTIFIER, true));

    Mockito.verify(searchService).removeMetadata(METALAKE_IDENTIFIER, EntityType.METALAKE, true);
    Mockito.verifyNoMoreInteractions(searchService);
  }

  @Test
  void testDisableMetalakeMarksAllIndexedEntitiesNotInUse() {
    handler.handleEvent(new DisableMetalakeEvent(USER, METALAKE_IDENTIFIER));

    Mockito.verify(searchService).updateMetalakeInUse(METALAKE_IDENTIFIER, false);
    Mockito.verifyNoMoreInteractions(searchService);
  }

  @Test
  void testEnableMetalakeResynchronizesAllIndexedEntities() {
    handler.handleEvent(new EnableMetalakeEvent(USER, METALAKE_IDENTIFIER));

    Mockito.verify(searchService).synchronizeMetalake(METALAKE_IDENTIFIER);
    Mockito.verifyNoMoreInteractions(searchService);
  }

  @Test
  void testRenameMetalakeDeletesOldIndicesAndSynchronizesNewName() {
    NameIdentifier newIdentifier = NameIdentifier.of("renamed_metalake");
    handler.handleEvent(
        new AlterMetalakeEvent(
            USER,
            METALAKE_IDENTIFIER,
            new MetalakeChange[] {MetalakeChange.rename(newIdentifier.name())},
            null));

    Mockito.verify(searchService).removeMetadata(METALAKE_IDENTIFIER, EntityType.METALAKE, true);
    Mockito.verify(searchService).synchronizeMetalake(newIdentifier);
    Mockito.verifyNoMoreInteractions(searchService);
  }

  @Test
  void testNonRenameMetalakeAlterDoesNotTouchSearchIndices() {
    handler.handleEvent(
        new AlterMetalakeEvent(
            USER,
            METALAKE_IDENTIFIER,
            new MetalakeChange[] {MetalakeChange.updateComment("new comment")},
            null));

    Mockito.verifyNoInteractions(searchService);
  }

  @Test
  void testDataDiscoveryListenerRoutesMetalakeEvents() throws Exception {
    DataDiscoveryListener listener = new DataDiscoveryListener();
    EventHandler mockHandler = Mockito.mock(EventHandler.class);
    Field handlersField = DataDiscoveryListener.class.getDeclaredField("eventHandlers");
    handlersField.setAccessible(true);
    handlersField.set(listener, ImmutableMap.of(MetalakeEvent.class, mockHandler));
    DropMetalakeEvent event = new DropMetalakeEvent(USER, METALAKE_IDENTIFIER, true);

    listener.onPostEvent(event);

    Mockito.verify(mockHandler).handleEvent(event);
  }
}
