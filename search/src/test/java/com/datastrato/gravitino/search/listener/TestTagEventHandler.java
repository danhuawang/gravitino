/*
 * Copyright 2024 Datastrato Inc.
 */
package com.datastrato.gravitino.search.listener;

import static org.mockito.Mockito.verify;

import com.datastrato.gravitino.search.service.SearchService;
import com.google.common.collect.ImmutableMap;
import org.apache.gravitino.Entity;
import org.apache.gravitino.listener.api.event.AlterTagEvent;
import org.apache.gravitino.listener.api.event.CreateTagEvent;
import org.apache.gravitino.listener.api.event.DeleteTagEvent;
import org.apache.gravitino.listener.api.info.TagInfo;
import org.apache.gravitino.tag.TagChange;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

class TestTagEventHandler {
  private SearchService searchService;
  private TagEventHandler handler;

  @BeforeEach
  void setUp() {
    searchService = Mockito.mock(SearchService.class);
    handler = new TagEventHandler(searchService);
  }

  @Test
  void testCreateTagSynchronizesTagEntity() {
    CreateTagEvent event =
        new CreateTagEvent(
            "user", "metalake", new TagInfo("sensitive", "sensitive data", ImmutableMap.of()));

    handler.handleEvent(event);

    verify(searchService).synchronizeMetadata(event.identifier(), Entity.EntityType.TAG, false);
  }

  @Test
  void testAlterTagSynchronizesTagAndAssociatedEntities() {
    AlterTagEvent event =
        new AlterTagEvent(
            "user",
            "metalake",
            new TagChange[] {TagChange.updateComment("updated")},
            new TagInfo("sensitive", "updated", ImmutableMap.of()));

    handler.handleEvent(event);

    verify(searchService).synchronizeMetadata(event.identifier(), Entity.EntityType.TAG, false);
    verify(searchService).synchronizeEntityDataByTag("metalake", "sensitive");
  }

  @Test
  void testDeleteTagRemovesTagAndResynchronizesTaggedEntities() {
    DeleteTagEvent event = new DeleteTagEvent("user", "metalake", "sensitive", true);

    handler.handleEvent(event);

    verify(searchService).removeMetadata(event.identifier(), Entity.EntityType.TAG, false);
    verify(searchService).resyncMetadataByTag("metalake", "sensitive");
  }
}
