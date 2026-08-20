/*
 * Copyright 2024 Datastrato Pvt Ltd.
 * This software is licensed under the Apache License version 2.
 */
package com.datastrato.gravitino.search.listener;

import com.datastrato.gravitino.search.service.SearchService;
import org.apache.gravitino.Entity.EntityType;
import org.apache.gravitino.NameIdentifier;
import org.apache.gravitino.listener.api.event.view.AlterViewEvent;
import org.apache.gravitino.listener.api.event.view.CreateViewEvent;
import org.apache.gravitino.listener.api.event.view.DropViewEvent;
import org.apache.gravitino.listener.api.event.view.LoadViewEvent;
import org.apache.gravitino.rel.ViewChange;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

class TestViewEventHandler {

  private static final String USER = "tester";
  private static final NameIdentifier VIEW_IDENT =
      NameIdentifier.of("test_metalake", "c1", "s1", "v1");

  private SearchService searchService;
  private ViewEventHandler handler;

  @BeforeEach
  void setUp() {
    searchService = Mockito.mock(SearchService.class);
    handler = new ViewEventHandler(searchService);
  }

  @Test
  void testCreateViewIsSynchronized() {
    handler.handleEvent(new CreateViewEvent(USER, VIEW_IDENT, null));

    Mockito.verify(searchService).synchronizeMetadata(VIEW_IDENT, EntityType.VIEW, false);
    Mockito.verifyNoMoreInteractions(searchService);
  }

  @Test
  void testAlterViewIsSynchronized() {
    handler.handleEvent(
        new AlterViewEvent(
            USER,
            VIEW_IDENT,
            new ViewChange[] {ViewChange.setProperty("comment", "new comment")},
            null));

    Mockito.verify(searchService).synchronizeMetadata(VIEW_IDENT, EntityType.VIEW, false);
  }

  @Test
  void testRenamedViewIsSynchronizedUnderTheNewName() {
    handler.handleEvent(
        new AlterViewEvent(USER, VIEW_IDENT, new ViewChange[] {ViewChange.rename("v2")}, null));

    Mockito.verify(searchService)
        .synchronizeMetadata(
            NameIdentifier.of(VIEW_IDENT.namespace(), "v2"), EntityType.VIEW, false);
  }

  @Test
  void testDropViewIsRemoved() {
    handler.handleEvent(new DropViewEvent(USER, VIEW_IDENT, true));

    Mockito.verify(searchService).removeMetadata(VIEW_IDENT, EntityType.VIEW, false);
    Mockito.verifyNoMoreInteractions(searchService);
  }

  @Test
  void testReadOnlyEventIsIgnored() {
    handler.handleEvent(new LoadViewEvent(USER, VIEW_IDENT, null));

    Mockito.verifyNoInteractions(searchService);
  }
}
