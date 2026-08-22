/*
 * Copyright 2024 Datastrato Pvt Ltd.
 * This software is licensed under the Apache License version 2.
 */
package com.datastrato.gravitino.search.listener;

import static org.apache.gravitino.utils.MetadataObjectUtil.toEntityType;

import com.datastrato.gravitino.search.service.SearchService;
import org.apache.gravitino.Entity;
import org.apache.gravitino.NameIdentifier;
import org.apache.gravitino.listener.api.event.AlterTagEvent;
import org.apache.gravitino.listener.api.event.AssociateTagsForMetadataObjectEvent;
import org.apache.gravitino.listener.api.event.CreateTagEvent;
import org.apache.gravitino.listener.api.event.DeleteTagEvent;
import org.apache.gravitino.listener.api.event.Event;

public class TagEventHandler implements EventHandler {
  private final SearchService searchService;

  public TagEventHandler(SearchService searchService) {
    this.searchService = searchService;
  }

  @Override
  public void handleEvent(Event event) {
    if (event instanceof AssociateTagsForMetadataObjectEvent) {
      AssociateTagsForMetadataObjectEvent tagEvent = (AssociateTagsForMetadataObjectEvent) event;
      NameIdentifier identifier = tagEvent.identifier();
      Entity.EntityType type = toEntityType(tagEvent.objectType());
      searchService.synchronizeMetadata(identifier, type, true);

    } else if (event instanceof CreateTagEvent) {
      searchService.synchronizeMetadata(event.identifier(), Entity.EntityType.TAG, false);

    } else if (event instanceof DeleteTagEvent) {
      String metalake = event.identifier().namespace().level(0);
      searchService.removeMetadata(event.identifier(), Entity.EntityType.TAG, false);
      // The entities that carried the tag stay searchable, only the tag has to drop off them.
      searchService.resyncMetadataByTag(metalake, event.identifier().name());

    } else if (event instanceof AlterTagEvent) {
      AlterTagEvent alterTag = (AlterTagEvent) event;
      String metalake = alterTag.identifier().namespace().level(0);

      searchService.synchronizeMetadata(alterTag.identifier(), Entity.EntityType.TAG, false);
      searchService.synchronizeEntityDataByTag(metalake, alterTag.identifier().name());
    }
  }
}
