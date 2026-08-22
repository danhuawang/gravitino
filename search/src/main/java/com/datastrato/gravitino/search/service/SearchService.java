/*
 * Copyright 2024 Datastrato Pvt Ltd.
 * This software is licensed under the Apache License version 2.
 */
package com.datastrato.gravitino.search.service;

import static com.datastrato.gravitino.search.config.SearchConfig.GRAVITINO_SEARCH_STORAGE_IMPL_MEMORY;
import static com.datastrato.gravitino.search.config.SearchConfig.GRAVITINO_SEARCH_STORAGE_IMPL_OPENSEARCH;
import static com.datastrato.gravitino.search.utils.FilterConditionUtils.createEntityNameQueryCondition;
import static java.util.stream.Collectors.toList;
import static org.apache.gravitino.MetadataObject.Type.METALAKE;

import com.datastrato.gravitino.search.config.SearchConfig;
import com.datastrato.gravitino.search.dto.SearchEntitiesDTO;
import com.datastrato.gravitino.search.dto.SearchEntityDTO;
import com.datastrato.gravitino.search.dto.TaskStatusDTO;
import com.datastrato.gravitino.search.parser.Condition;
import com.datastrato.gravitino.search.parser.ConditionBuilderVisitor;
import com.datastrato.gravitino.search.parser.ConditionBuilderVisitor.QueryCondition;
import com.datastrato.gravitino.search.po.SearchEntityPO;
import com.datastrato.gravitino.search.store.InMemorySearchStorage;
import com.datastrato.gravitino.search.store.SearchDataSource;
import com.datastrato.gravitino.search.store.SearchStorage;
import com.datastrato.gravitino.search.store.WriteContext;
import com.datastrato.gravitino.search.store.opensearch.OpenSearchStorage;
import com.datastrato.gravitino.search.utils.PermissionProjectionCache;
import com.google.common.annotations.VisibleForTesting;
import com.google.common.base.Joiner;
import com.google.common.base.Preconditions;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import com.google.common.util.concurrent.ThreadFactoryBuilder;
import java.io.Closeable;
import java.time.Duration;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Future;
import java.util.concurrent.LinkedBlockingDeque;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import org.apache.commons.lang3.StringUtils;
import org.apache.gravitino.Config;
import org.apache.gravitino.Entity;
import org.apache.gravitino.Entity.EntityType;
import org.apache.gravitino.GravitinoEnv;
import org.apache.gravitino.MetadataObject;
import org.apache.gravitino.MetadataObjects;
import org.apache.gravitino.NameIdentifier;
import org.apache.gravitino.exceptions.NoSuchMetalakeException;
import org.apache.gravitino.utils.NameIdentifierUtil;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class SearchService implements Closeable {

  private static final Logger LOG = LoggerFactory.getLogger(SearchService.class);
  private static final int REMOVE_METADATA_THREAD_NUM = 1;
  private static final int THREAD_POOL_QUEUE_SIZE = 100;

  private final int maxQueueSize;
  private final int backoffTime;
  private final int maxSyncMetadataThreadNum;
  private final int entityProcessBatchSize;

  @VisibleForTesting protected final SearchStorage storage;

  private final TaskStatusStorage taskStatusStorage;

  private final Deque<SyncTask> syncTasks = new ArrayDeque<>();
  private final Object backoffLock = new Object();

  private static final Map<String, Class<? extends SearchStorage>> STORAGE_MAP =
      ImmutableMap.of(
          GRAVITINO_SEARCH_STORAGE_IMPL_MEMORY, InMemorySearchStorage.class,
          GRAVITINO_SEARCH_STORAGE_IMPL_OPENSEARCH, OpenSearchStorage.class);

  private final ExecutorService executorService;
  boolean stop = false;

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
      int maxThreadNum = searchConfig.getMaxBackgroundThread();
      this.maxSyncMetadataThreadNum = maxThreadNum - REMOVE_METADATA_THREAD_NUM;
      this.backoffTime = searchConfig.getMaxBackoffMs();

      this.storage = clazz.getDeclaredConstructor().newInstance();
      this.storage.initialize(config);

      this.executorService =
          new ThreadPoolExecutor(
              maxThreadNum,
              maxThreadNum,
              0L,
              TimeUnit.MILLISECONDS,
              new LinkedBlockingDeque<>(THREAD_POOL_QUEUE_SIZE),
              new ThreadFactoryBuilder()
                  .setNameFormat("SearchService-SyncMetadataExecutor-%d")
                  .setDaemon(true)
                  .build());

      for (int i = 0; i < maxSyncMetadataThreadNum; i++) {
        executorService.execute(this::handleSyncTask);
      }

      // Initialize the task status storage, currently, we use in-memory storage for
      // simplicity, but it can be replaced with a persistent storage like a database.
      this.taskStatusStorage = new InMemoryTaskStatusStorage();
      taskStatusStorage.initialize(searchConfig);
    } catch (Exception e) {
      String errorMessage = "Failed to initialize SearchService";
      LOG.error(errorMessage, e);
      throw new RuntimeException(errorMessage, e);
    }
  }

  public int getMaxSyncMetadataThreadNum() {
    return maxSyncMetadataThreadNum;
  }

  public int getEntityProcessBatchSize() {
    return entityProcessBatchSize;
  }

  @VisibleForTesting
  public TaskStatusStorage getTaskStatusStorage() {
    return taskStatusStorage;
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
    return synchronizeMetadata(
        metalake,
        metadataObject,
        cascade,
        SyncTaskOptions.DEFAULT,
        cascade ? new RemoveDeletedMetadata() : null);
  }

  private SyncTask synchronizeMetadata(
      String metalake,
      MetadataObject metadataObject,
      boolean cascade,
      SyncTaskOptions syncTaskOptions,
      SyncTaskFinishedHandler finishedHandler) {
    // Load the metalake first to make sure the metalake exists.
    boolean metalakeExists =
        GravitinoEnv.getInstance().metalakeDispatcher().metalakeExists(NameIdentifier.of(metalake));
    if (!metalakeExists) {
      throw new NoSuchMetalakeException("The metalake '%s' does not exist.", metalake);
    }

    Preconditions.checkArgument(metadataObject != null, "The metadata object cannot be null.");

    if (cascade && metadataObject.type() == METALAKE) {
      PermissionProjectionCache.invalidate(metalake);
    }

    checkSyncTaskQueueSize();

    SyncTask syncTask =
        new SyncTask(metalake, metadataObject, cascade, this, syncTaskOptions, finishedHandler);
    LOG.info("TaskId: {}, start synchronize metadata...", syncTask.getTaskId());
    addTask(syncTask);

    return syncTask;
  }

  public SyncTask synchronizeMetadata(
      NameIdentifier nameIdentifier, Entity.EntityType type, boolean cascade) {
    if (type == EntityType.USER || type == EntityType.GROUP) {
      String metalake = NameIdentifierUtil.getMetalake(nameIdentifier);
      boolean metalakeExists =
          GravitinoEnv.getInstance()
              .metalakeDispatcher()
              .metalakeExists(NameIdentifier.of(metalake));
      if (!metalakeExists) {
        throw new NoSuchMetalakeException("The metalake '%s' does not exist.", metalake);
      }

      SearchEntityIdentifier identifier = SearchEntityIdentifier.of(nameIdentifier, type);
      SearchEntitySource source = SearchEntitySource.createSearchEntitySource(identifier, false);
      SyncTask syncTask = new SyncTask(identifier, source, this);
      LOG.info("TaskId: {}, start synchronize metadata...", syncTask.getTaskId());
      addTask(syncTask);
      return syncTask;
    }

    String metalake = NameIdentifierUtil.getMetalake(nameIdentifier);
    MetadataObject metadataObject = NameIdentifierUtil.toMetadataObject(nameIdentifier, type);
    return synchronizeMetadata(
        metalake,
        metadataObject,
        cascade,
        SyncTaskOptions.DEFAULT,
        cascade ? new RemoveDeletedMetadata() : null);
  }

  public SyncTask synchronizeEntityDataByTag(String metalake, String tagName) {
    return synchronizeEntityDataByTag(
        metalake,
        tagName,
        TagAssociationSearchEntitySource.ofAssociatedEntities(metalake, tagName));
  }

  public Future<?> removeMetadata(
      NameIdentifier nameIdentifier, Entity.EntityType entityType, boolean cascade) {
    return executorService.submit(
        () -> {
          try {
            String metalake = NameIdentifierUtil.getMetalake(nameIdentifier);
            removeMetadataByQuery(
                metalake,
                entityType == Entity.EntityType.METALAKE
                    ? null
                    : createEntityNameQueryCondition(nameIdentifier, cascade));

          } catch (Exception e) {
            LOG.error("Failed to remove metadata for {}: {}", nameIdentifier, e.getMessage(), e);
          }
        });
  }

  /**
   * Re-synchronizes every entity that carried the given tag, so that their indexed documents drop a
   * tag that no longer exists.
   *
   * <p>The entities cannot be discovered through Gravitino at this point, the tag is already gone
   * and {@code listMetadataObjectsForTag} would not report anything, so they are looked up in the
   * search index by the tag they still carry.
   *
   * @param metalake The metalake owning the deleted tag.
   * @param tagName The name of the deleted tag.
   * @return The synchronization task queued for the entities that carried the tag.
   */
  public SyncTask resyncMetadataByTag(String metalake, String tagName) {
    return synchronizeEntityDataByTag(
        metalake,
        tagName,
        TagAssociationSearchEntitySource.ofIndexedEntities(metalake, tagName, storage));
  }

  private SyncTask synchronizeEntityDataByTag(
      String metalake, String tagName, TagAssociationSearchEntitySource source) {
    checkSyncTaskQueueSize();

    // As Tag is not a metadata object, we create a SearchEntityIdentifier using the metalake name.
    SearchEntityIdentifier searchEntityIdentifier =
        SearchEntityIdentifier.of(NameIdentifier.of(metalake), EntityType.METALAKE);
    SyncTask syncTask = new SyncTask(searchEntityIdentifier, source, this);
    LOG.info(
        "TaskId: {}, start synchronizing metadata by tag {} ...", syncTask.getTaskId(), tagName);
    addTask(syncTask);
    return syncTask;
  }

  private void checkSyncTaskQueueSize() {
    synchronized (this) {
      if (syncTasks.size() >= maxQueueSize) {
        throw new RuntimeException(
            String.format(
                "The number of sync tasks is too large, "
                    + "please wait for the previous tasks to finish. Current size: %d MaxQueueSize: %d",
                syncTasks.size(), maxQueueSize));
      }
    }
  }

  /**
   * Removes a top-level search entity identified by its type and name.
   *
   * <p>User and Group are not {@link MetadataObject}s, while the lightweight Role mapping also has
   * no fully qualified name. Their removal events use this scoped query instead of the regular
   * metadata hierarchy query.
   *
   * @param metalake The metalake containing the entity.
   * @param entityName The entity name.
   * @param entityType The entity type.
   * @return A future representing the asynchronous removal.
   */
  public Future<?> removeEntityByName(String metalake, String entityName, EntityType entityType) {
    return executorService.submit(
        () -> {
          try {
            Condition condition =
                new Condition.AndCondition(
                    ImmutableList.of(
                        new Condition.TermCondition(
                            "entity_type", entityType.name().toLowerCase(Locale.ROOT)),
                        new Condition.TermCondition("entity_name.keyword", entityName)));
            removeMetadataByQuery(metalake, condition);
          } catch (Exception e) {
            LOG.error(
                "Failed to remove {} named {} from metalake {}",
                entityType,
                entityName,
                metalake,
                e);
          }
        });
  }

  public void removeMetadataByQuery(String metalake, Condition condition) {
    // TODO we could only fetch entity_id instead of getting all fields.
    SearchDataSource source = storage.search(metalake, null, condition, ImmutableList.of());

    SearchDataSource.Result result = source.nextBatch();
    while (!result.isEmpty()) {
      Entity.EntityType resultEntityType = result.entityType();
      List<Long> entityIds =
          result.entities().stream().map(SearchEntityDTO::getEntityId).collect(toList());
      storage.delete(metalake, entityIds, resultEntityType);

      result = source.nextBatch();
    }
  }

  public List<SearchEntitiesDTO> query(
      String metalake, String query, int pageNumber, int pageSize) {
    String keyword = null;
    Condition condition = null;
    if (StringUtils.isNotBlank(query)) {
      QueryCondition queryCondition = ConditionBuilderVisitor.buildQueryCondition(query);
      keyword = Joiner.on(" ").skipNulls().join(queryCondition.getKeywords());
      condition = queryCondition.getCondition();
    }

    return storage.search(metalake, keyword, condition, ImmutableList.of(), pageSize, pageNumber);
  }

  public List<SearchEntitiesDTO> query(
      String metalake,
      String keywords,
      Condition condition,
      List<String> fields,
      int pageNumber,
      int pageSize) {
    return storage.search(metalake, keywords, condition, fields, pageSize, pageNumber);
  }

  public SearchDataSource query(
      String metalake, String keywords, Condition condition, List<String> fields) {
    return storage.search(metalake, null, condition, ImmutableList.of());
  }

  protected void addTask(SyncTask syncTask) {
    syncTask.saveTaskStatus();
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
          syncTask.prepare();
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
      LOG.error("Sync task handler thread interrupted");
    } catch (Exception e) {
      LOG.error("Failed to handle sync task", e);
    }
  }

  private boolean needBackoff() {
    // todo Work on later
    return false;
  }

  public TaskStatusDTO getTaskStatus(String taskId) {
    TaskStatus taskStatus = taskStatusStorage.getTaskStatus(taskId);
    if (taskStatus == null) {
      return null;
    }

    // Convert TaskStatus to TaskStatusDTO
    return TaskStatusDTO.builder()
        .withTaskId(taskStatus.getTaskId())
        .withSubTaskNum(taskStatus.getSubTaskNum())
        .withTaskStatus(taskStatus.getTaskStatus())
        .withMessage(taskStatus.getMessage())
        .withTaskCreateTime(taskStatus.getTaskCreateTime())
        .withTaskUpdateTime(taskStatus.getTaskUpdateTime())
        .withMetadataObject(taskStatus.getMetadataObject())
        .withMetalake(taskStatus.getMetalake())
        .withCascade(taskStatus.isCascade())
        .build();
  }

  @Override
  public void close() {
    try {
      if (storage != null) {
        storage.close();
      }
      stop = true;
      executorService.shutdownNow();
    } catch (Exception e) {
      LOG.error("Failed to close SearchService", e);
    }
  }

  public SyncTask rebuildMetadata(String metalake) throws Exception {
    // Should add limit that only one transaction can be active at a time for a metalake.
    long txId = storage.beginTransaction(metalake);

    // synchronizeMetadata will handle the transaction commit/rollback.
    SyncTaskOptions syncTaskOptions = SyncTaskOptions.builder().withTransactionId(txId).build();
    return synchronizeMetadata(
        metalake,
        MetadataObjects.parse(metalake, METALAKE),
        true,
        syncTaskOptions,
        new RebuildMetadataFinishHandler());
  }

  public void delete(String metalake, List<Long> entityIds, EntityType resultEntityType) {
    storage.delete(metalake, entityIds, resultEntityType);
  }

  public void write(List<SearchEntityPO> allEntities, WriteContext build) {
    storage.write(allEntities, build);
  }

  public void commit(long transId) {
    storage.commit(transId);
  }

  public void rollback(long transId) {
    storage.rollback(transId);
  }
}
