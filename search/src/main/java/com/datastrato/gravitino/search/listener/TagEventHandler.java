/*
 * Copyright 2024 Datastrato Pvt Ltd.
 * This software is licensed under the Apache License version 2.
 */
package com.datastrato.gravitino.search.listener;

import com.datastrato.gravitino.search.service.SearchService;
import org.apache.gravitino.Entity;
import org.apache.gravitino.MetadataObject;
import org.apache.gravitino.NameIdentifier;
import org.apache.gravitino.exceptions.GravitinoRuntimeException;
import org.apache.gravitino.listener.api.event.AlterTagEvent;
import org.apache.gravitino.listener.api.event.AssociateTagsForMetadataObjectEvent;
import org.apache.gravitino.listener.api.event.DeleteTagEvent;
import org.apache.gravitino.listener.api.event.Event;
import org.apache.gravitino.tag.TagChange;

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

    } else if (event instanceof DeleteTagEvent) {
      String metalake = event.identifier().namespace().level(0);
      searchService.removeMetadataByTag(metalake, event.identifier().name());

    } else if (event instanceof AlterTagEvent) {
      AlterTagEvent alterTag = (AlterTagEvent) event;
      String metalake = alterTag.identifier().namespace().level(0);

      // handle rename tag operation
      for (TagChange change : alterTag.changes()) {
        if (change instanceof TagChange.RenameTag) {
          TagChange.RenameTag rename = (TagChange.RenameTag) change;
          NameIdentifier newId =
              NameIdentifier.of(alterTag.identifier().namespace(), rename.getNewName());
          searchService.synchronizeEntityDataByTag(metalake, newId.name());
          break;
        }
      }
    }
  }

  private static Entity.EntityType toEntityType(MetadataObject.Type type) {
    switch (type) {
      case METALAKE:
        return Entity.EntityType.METALAKE;
      case CATALOG:
        return Entity.EntityType.CATALOG;
      case SCHEMA:
        return Entity.EntityType.SCHEMA;
      case TABLE:
        return Entity.EntityType.TABLE;
      case TOPIC:
        return Entity.EntityType.TOPIC;
      case FILESET:
        return Entity.EntityType.FILESET;
      case MODEL:
        return Entity.EntityType.MODEL;
    }
    throw new GravitinoRuntimeException("Unsupported metadata object type: " + type);
  }
}
