/*
 * Copyright 2024 Datastrato Pvt Ltd.
 * This software is licensed under the Apache License version 2.
 */
package com.datastrato.gravitino.search.listener;

import com.datastrato.gravitino.search.service.SearchService;
import org.apache.gravitino.Entity.EntityType;
import org.apache.gravitino.NameIdentifier;
import org.apache.gravitino.file.FilesetChange;
import org.apache.gravitino.listener.api.event.AlterFilesetEvent;
import org.apache.gravitino.listener.api.event.CreateFilesetEvent;
import org.apache.gravitino.listener.api.event.DropFilesetEvent;
import org.apache.gravitino.listener.api.event.Event;

public class FilesetEventHandler implements EventHandler {
  private final SearchService searchService;

  public FilesetEventHandler(SearchService searchService) {
    this.searchService = searchService;
  }

  @Override
  public void handleEvent(Event event) {
    NameIdentifier identifier = event.identifier();
    if (event instanceof CreateFilesetEvent) {
      searchService.synchronizeMetadata(identifier, EntityType.FILESET, false);
    } else if (event instanceof AlterFilesetEvent) {
      AlterFilesetEvent alterFilesetEvent = (AlterFilesetEvent) event;
      for (FilesetChange change : alterFilesetEvent.filesetChanges()) {
        if (change instanceof FilesetChange.RenameFileset) {
          FilesetChange.RenameFileset rename = (FilesetChange.RenameFileset) change;
          identifier = NameIdentifier.of(identifier.namespace(), rename.getNewName());
        }
      }
      searchService.synchronizeMetadata(identifier, EntityType.FILESET, false);
    } else if (event instanceof DropFilesetEvent) {
      searchService.removeMetadata(identifier, EntityType.FILESET, false);
    }
  }
}
