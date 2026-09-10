/*
 * Copyright 2026 Datastrato Inc.
 */
package com.datastrato.gravitino.search.listener;

import com.datastrato.gravitino.search.service.SearchService;
import org.apache.gravitino.Entity.EntityType;
import org.apache.gravitino.listener.api.event.Event;
import org.apache.gravitino.listener.api.event.job.AlterJobTemplateEvent;
import org.apache.gravitino.listener.api.event.job.DeleteJobTemplateEvent;
import org.apache.gravitino.listener.api.event.job.RegisterJobTemplateEvent;

/** Synchronizes the searchable job template projections after successful mutations. */
public class JobTemplateEventHandler implements EventHandler {
  private final SearchService searchService;

  /**
   * Creates a job template event handler.
   *
   * @param searchService the search service to update
   */
  public JobTemplateEventHandler(SearchService searchService) {
    this.searchService = searchService;
  }

  @Override
  public void handleEvent(Event event) {
    if (event instanceof RegisterJobTemplateEvent || event instanceof AlterJobTemplateEvent) {
      // A rename retains the entity ID, so indexing the new projection replaces the old name.
      searchService.synchronizeMetadata(event.identifier(), EntityType.JOB_TEMPLATE, false);
    } else if (event instanceof DeleteJobTemplateEvent) {
      searchService.removeMetadata(event.identifier(), EntityType.JOB_TEMPLATE, false);
    }
  }
}
