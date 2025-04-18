/*
 * Copyright 2024 Datastrato Pvt Ltd.
 * This software is licensed under the Apache License version 2.
 */
package com.datastrato.gravitino.search.service;

import java.io.Closeable;
import java.io.IOException;
import javax.annotation.Nullable;
import org.apache.gravitino.Config;
import org.apache.gravitino.GravitinoEnv;
import org.apache.gravitino.MetadataObject;

public class SearchService implements Closeable {

  private static volatile SearchService searchService;

  public static SearchService getSearchService() {
    if (searchService == null) {
      synchronized (SearchService.class) {
        if (searchService == null) {
          searchService = new SearchService(GravitinoEnv.getInstance().config());
        }
      }
    }
    return searchService;
  }

  public SearchService(Config config) {
    // Initialize the storage
  }

  /**
   * This method is used to synchronize the data from the Gravitino environment to the search
   * storage. If the metadataObject is null, then the value of cascade will be ignored, it will
   * synchronize all the metadata under the given metalake. If metadataObject is not null, it will
   * synchronize the metadata object and all its sub-entities in the hierarchy if cascade is true.
   * If cascade is false, it will only synchronize the entity itself. For example, if the metadata
   * object is a schema, it will synchronize the schema and all the tables in the schema with
   * cascade true. If cascade is false, it will only synchronize the schema.
   *
   * <p>Note: This method could not handle the scenario where an entity is removed from Gravitino,
   * to handle this scenario, we need to implement add a delete method in the storage or recreate
   * the table/indices.
   *
   * <p>Another point is that the method is completely asynchronous, so the caller should wait for
   * the completion of the tasks if needed.
   *
   * @param metalake the name of the metalake that the metadata object belongs to. It cannot be
   *     null.
   * @param metadataObject the metadata object that needs to be synchronized. If null, it will
   *     synchronize all the metadata under the given metalake.
   * @param cascade if true, it will synchronize all the sub-entities in the hierarchy. If false, it
   *     will only synchronize the entity itself.
   * @return the task id of the synchronization task. The caller can use this task id to check the
   *     status of the task.
   */
  public String synchronizeMetadata(
      String metalake, @Nullable MetadataObject metadataObject, boolean cascade) {
    // TODO implement this method
    return null;
  }

  @Override
  public void close() throws IOException {}
}
