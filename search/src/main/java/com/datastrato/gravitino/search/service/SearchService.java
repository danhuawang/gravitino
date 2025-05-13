/*
 * Copyright 2024 Datastrato Pvt Ltd.
 * This software is licensed under the Apache License version 2.
 */
package com.datastrato.gravitino.search.service;

import static com.datastrato.gravitino.search.config.SearchConfig.GRAVITINO_SEARCH_STORAGE_IMPL_MEMORY;
import static com.datastrato.gravitino.search.config.SearchConfig.GRAVITINO_SEARCH_STORAGE_IMPL_OPENSEARCH;

import com.datastrato.gravitino.search.config.SearchConfig;
import com.datastrato.gravitino.search.store.InMemorySearchStorage;
import com.datastrato.gravitino.search.store.SearchStorage;
import com.datastrato.gravitino.search.store.opensearch.OpenSearchStorage;
import com.google.common.annotations.VisibleForTesting;
import com.google.common.base.Preconditions;
import com.google.common.collect.ImmutableMap;
import com.google.common.util.concurrent.ThreadFactoryBuilder;
import java.io.Closeable;
import java.time.Duration;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.LinkedBlockingDeque;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import org.apache.gravitino.Config;
import org.apache.gravitino.GravitinoEnv;
import org.apache.gravitino.MetadataObject;
import org.apache.gravitino.NameIdentifier;
import org.apache.gravitino.exceptions.NoSuchMetalakeException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class SearchService implements Closeable {

  private static final Logger LOG = LoggerFactory.getLogger(SearchService.class);

  private final int maxQueueSize;
  private final int backoffTime;
  private final int maxThreadNum;
  private final int entityProcessBatchSize;

  @VisibleForTesting final SearchStorage storage;

  private final Deque<SyncTask> syncTasks = new ArrayDeque<>();
  private final Object backoffLock = new Object();

  private static volatile SearchService searchService;

  private static final Map<String, Class<? extends SearchStorage>> STORAGE_MAP =
      ImmutableMap.of(
          GRAVITINO_SEARCH_STORAGE_IMPL_MEMORY, InMemorySearchStorage.class,
          GRAVITINO_SEARCH_STORAGE_IMPL_OPENSEARCH, OpenSearchStorage.class);

  private final ExecutorService syncMetadataExecutor;
  boolean stop = false;

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
    try {
      SearchConfig searchConfig = new SearchConfig(config.getAllConfig());
      String storageType = searchConfig.getStorageImpl();
      Class<? extends SearchStorage> clazz = STORAGE_MAP.get(storageType);
      if (clazz == null) {
        throw new IllegalArgumentException("Unsupported storage type: " + storageType);
      }

      this.maxQueueSize = searchConfig.getMaxTaskQueueSize();
      this.entityProcessBatchSize = searchConfig.getSyncBatchSize();
      this.maxThreadNum = searchConfig.getMaxBackgroundThread();
      this.backoffTime = searchConfig.getMaxBackoffMs();

      this.storage = clazz.getDeclaredConstructor().newInstance();
      this.storage.initialize(config);

      this.syncMetadataExecutor =
          new ThreadPoolExecutor(
              maxThreadNum,
              maxThreadNum,
              0L,
              TimeUnit.MILLISECONDS,
              new LinkedBlockingDeque<>(maxThreadNum),
              new ThreadFactoryBuilder()
                  .setNameFormat("SearchService-SyncMetadataExecutor-%d")
                  .setDaemon(true)
                  .build());

      for (int i = 0; i < maxThreadNum; i++) {
        syncMetadataExecutor.execute(this::handleSyncTask);
      }

    } catch (Exception e) {
      throw new RuntimeException("Failed to create storage instance", e);
    }
  }

  public int getMaxThreadNnm() {
    return maxThreadNum;
  }

  public int getEntityProcessBatchSize() {
    return entityProcessBatchSize;
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
  public SyncTask synchronizeMetadata(
      String metalake, MetadataObject metadataObject, boolean cascade) {
    // Load the metalake first to make sure the metalake exists.
    boolean metalakeExists =
        GravitinoEnv.getInstance().metalakeDispatcher().metalakeExists(NameIdentifier.of(metalake));
    if (!metalakeExists) {
      throw new NoSuchMetalakeException(
          String.format("The metalake '%s' does not exist.", metalake));
    }

    Preconditions.checkArgument(metadataObject != null, "The metadata object cannot be null.");

    if (syncTasks.size() > maxQueueSize) {
      throw new RuntimeException(
          String.format(
              "The number of sync tasks is too large, "
                  + "please wait for the previous tasks to finish. Current size: %d MaxQueueSize: %d",
              syncTasks.size(), maxQueueSize));
    }

    String taskId =
        String.format("Task-%s.%s-%s", metalake, metadataObject.fullName(), UUID.randomUUID());
    LOG.info("TaskId: {}, start synchronize metadata...", taskId);

    SyncTask syncTask = new SyncTask(taskId, metalake, metadataObject, cascade, this);
    addTask(syncTask);
    return syncTask;
  }

  protected void addTask(SyncTask syncTask) {
    synchronized (this) {
      syncTasks.add(syncTask);
      notify();
    }
  }

  // The function called by multiple threads to handle SyncTasks
  private void handleSyncTask() {
    try {
      while (!stop) {
        SyncTask syncTask;
        synchronized (this) {
          if (syncTasks.isEmpty()) {
            this.wait(Duration.ofMillis(5).toMillis());
            continue;
          }
          syncTask = syncTasks.poll();
        }

        try {
          while (syncTask.processOnBatch()) {
            if (needBackoff()) {
              synchronized (backoffLock) {
                backoffLock.wait(backoffTime);
              }
            }
          }
        } catch (Exception e) {
          LOG.error("Failed during sync task execution", e);
        }
      }
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
      LOG.error("Sync task handler thread interrupted", e);
    } catch (Exception e) {
      LOG.error("Failed to handle sync task", e);
    }
  }

  private boolean needBackoff() {
    // todo Work on later
    return false;
  }

  @Override
  public void close() {
    try {
      if (storage != null) {
        storage.close();
      }
      stop = true;
      syncMetadataExecutor.shutdownNow();
    } catch (Exception e) {
      LOG.error("Failed to close SearchService", e);
    }
  }
}
