/*
 * Copyright 2024 Datastrato Pvt Ltd.
 * This software is licensed under the Apache License version 2.
 */
package com.datastrato.gravitino.search.service;

import static com.datastrato.gravitino.search.utils.FilterConditionUtils.createRemovedEntityCondition;
import static com.datastrato.gravitino.search.utils.FilterConditionUtils.createUpdateTimeCondition;

import com.datastrato.gravitino.search.dto.SearchEntitiesDTO;
import com.datastrato.gravitino.search.dto.SearchEntityDTO;
import com.datastrato.gravitino.search.po.SearchEntityPO;
import com.datastrato.gravitino.search.service.TaskStatus.TaskStatusEnum;
import com.google.common.collect.Lists;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.stream.Collectors;
import lombok.Getter;
import org.apache.gravitino.Entity;
import org.apache.gravitino.MetadataObject;
import org.apache.gravitino.NameIdentifier;
import org.apache.gravitino.utils.NameIdentifierUtil;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class SyncTask {
  private static final Logger LOG = LoggerFactory.getLogger(SyncTask.class);

  static final int MAX_DELETE_BATCH_SIZE = 10000;

  private final String taskId;
  private final SearchService service;
  private final SearchEntitySource source;
  private final SearchEntityIdentifier searchEntityIdentifier;

  private final Object finishedLock = new Object();
  private boolean finished = false;

  // root task can be split into subtasks to process in parallel
  private final boolean isRootTask;

  private final List<SyncTask> subTasks = new ArrayList<>();

  @Getter private final TaskStatus taskStatus;

  private final long createTime;

  private boolean cascade = false;

  public SyncTask(
      String metalake, MetadataObject metadataObject, boolean cascade, SearchService service) {
    this.searchEntityIdentifier = new SearchEntityIdentifier(metadataObject, metalake);
    this.taskId = createTaskId();
    this.createTime = System.currentTimeMillis();
    this.service = service;
    this.isRootTask = true;
    this.cascade = cascade;
    this.source = SearchEntitySource.createSearchEntitySource(searchEntityIdentifier, cascade);

    splitTask();

    this.taskStatus =
        TaskStatus.builder()
            .withTaskId(taskId)
            .withMetadataObject(metadataObject.toString())
            .withMetalake(metalake)
            .withSubTaskNum(subTasks.size())
            .withCascade(cascade)
            .withTaskStatus(TaskStatusEnum.SUBMITTED.name())
            .withMessage("Task created")
            .withTaskCreateTime(System.currentTimeMillis())
            .withTaskUpdateTime(System.currentTimeMillis())
            .build();
  }

  public SyncTask(
      SearchEntityIdentifier identifier, SearchEntitySource source, SearchService service) {
    this.searchEntityIdentifier = identifier;
    this.taskId = createTaskId();
    this.service = service;
    this.isRootTask = true;
    this.source = source;
    this.createTime = System.currentTimeMillis();

    this.taskStatus =
        TaskStatus.builder()
            .withTaskId(taskId)
            .withMetadataObject(identifier.entityIdent().toString())
            .withMetalake(identifier.metalake())
            .withSubTaskNum(subTasks.size())
            .withCascade(true)
            .withTaskStatus(TaskStatusEnum.SUBMITTED.name())
            .withMessage("Task created")
            .withTaskCreateTime(System.currentTimeMillis())
            .withTaskUpdateTime(System.currentTimeMillis())
            .build();

    splitTask();
  }

  private SyncTask(
      String taskId,
      SearchEntityIdentifier identifier,
      SearchEntitySource source,
      SearchService service,
      TaskStatus taskStatus) {
    this.taskId = taskId;
    this.searchEntityIdentifier = identifier;
    this.source = source;
    this.service = service;
    this.isRootTask = false;
    this.taskStatus = taskStatus;
    this.createTime = System.currentTimeMillis();
  }

  private String createTaskId() {
    return String.format(
        "Task-%s.%s-%s",
        searchEntityIdentifier.metalake(), searchEntityIdentifier.fullName(), UUID.randomUUID());
  }

  public void splitTask() {
    // split the task into subtasks by entity count
    int entityCount = source.approximateEntityCount();
    int numChildTask =
        Math.min(
            entityCount / service.getEntityProcessBatchSize(),
            service.getMaxSyncMetadataThreadNum() - 1);

    if (numChildTask == 0) {
      // entityCount is not enough, no need to split child sync task, just process it in this task
      return;
    }

    for (int i = 0; i < numChildTask; i++) {
      String taskId = this.taskId + "_" + i;
      TaskStatus taskStatus =
          TaskStatus.builder()
              .withTaskId(taskId)
              .withTaskCreateTime(System.currentTimeMillis())
              .withTaskUpdateTime(System.currentTimeMillis())
              .withSubTaskNum(0)
              .build();

      SyncTask syncTask = new SyncTask(taskId, searchEntityIdentifier, source, service, taskStatus);
      subTasks.add(syncTask);
      service.addTask(syncTask);
    }
    LOG.info(
        "Task {} split into {} subtasks, Sub tasks: {}",
        taskId,
        subTasks.size(),
        subTasks.stream().map(SyncTask::getTaskId).collect(Collectors.joining(",")));
  }

  public boolean processOnBatch() {
    try {
      if (source.finished()) {
        handleTaskFinished();
        synchronized (finishedLock) {
          finished = true;
          LOG.debug("Task {} success and notify finished", taskId);
          finishedLock.notifyAll();
        }
        return false;
      }

      List<SearchEntityPO> searchEntityPOs = source.nextBatch(service.getEntityProcessBatchSize());

      if (LOG.isDebugEnabled()) {
        StringBuilder sb = new StringBuilder();
        sb.append("Task ").append(taskId).append(" Processing entities: ");
        for (int i = 0; i < searchEntityPOs.size(); i++) {
          sb.append(searchEntityPOs.get(i).getFullQualifiedName());
          if (i < searchEntityPOs.size() - 1) {
            sb.append(", ");
          }
        }
        LOG.debug(sb.toString());
      }

      if (!searchEntityPOs.isEmpty()) {
        service.storage.write(searchEntityPOs);
      }
      return true;

    } catch (Exception e) {
      LOG.error("Error processing batch for task {}", taskId, e);
      // todo need to handle retry logic
      handleTaskFinished();
      synchronized (finishedLock) {
        finished = true;
        LOG.debug("Task {} failed and notify finished", taskId);
        finishedLock.notifyAll();
      }
      return false;
    }
  }

  public void prepare() {
    updateTaskStatus(TaskStatusEnum.RUNNING, "Task is prepared and running.");
  }

  public String getTaskId() {
    return taskId;
  }

  public boolean finished() {
    synchronized (finishedLock) {
      return finished;
    }
  }

  private void handleTaskFinished() {
    if (!isRootTask) {
      updateTaskStatus(TaskStatusEnum.COMPLETED, "Subtask finished.");
      return;
    }

    TaskStatus.TaskStatusEnum status = TaskStatus.TaskStatusEnum.COMPLETED;
    for (SyncTask subTask : subTasks) {
      if (!subTask.finished()) {
        try {
          subTask.waitToFinished();
        } catch (InterruptedException e) {
          // Handle exception
          status = TaskStatusEnum.INTERRUPTED;
          LOG.error("Error waiting for subtask to finish", e);
        }
      }
    }

    boolean syncDeleteSuccess = true;
    if (isRootTask && cascade) {
      // If cascade is true and is the root task, then we need to remove the deleted data from
      // storage.
      syncDeleteSuccess = removeDeleteDataFromStorage();
    }

    StringBuilder stringBuilder = new StringBuilder();
    stringBuilder.append(String.format("Task %s process finished.", taskId));
    List<SearchEntityIdentifier> totalFailedEntities =
        Lists.newArrayList(source.getProcessFailedEntities());
    if (!syncDeleteSuccess) {
      totalFailedEntities.add(searchEntityIdentifier);
    }

    if (!totalFailedEntities.isEmpty()) {
      stringBuilder.append(" Failed entities:");
      for (SearchEntityIdentifier metadata : source.getProcessFailedEntities()) {
        stringBuilder.append("\n\t");
        stringBuilder.append(metadata.entityIdent().toString());
        source.getProcessFailedEntities();
      }
    }

    // Add to be polished, this is rather hacky and should be improved later.
    String message = stringBuilder.toString();

    updateTaskStatus(status, message);

    LOG.info(message);
  }

  public void waitToFinished() throws InterruptedException {
    synchronized (finishedLock) {
      if (!finished) {
        finishedLock.wait();
        LOG.debug("Task {} wait finished", taskId);
      }
    }
  }

  public void saveTaskStatus() {
    service.getTaskStatusStorage().save(taskStatus, true);
  }

  private void updateTaskStatus(TaskStatusEnum taskStatusEnum, String message) {
    TaskStatus newStatus =
        taskStatus
            .toBuilder()
            .withTaskStatus(taskStatusEnum.name())
            .withMessage(message)
            .withTaskUpdateTime(System.currentTimeMillis())
            .build();
    service.getTaskStatusStorage().update(newStatus);
  }

  private boolean removeDeleteDataFromStorage() {
    try {
      if (!source.getProcessFailedEntities().isEmpty()) {
        LOG.warn(
            "Task {} finished with failure, do not remove the metadata for {}",
            taskId,
            searchEntityIdentifier);
        return true;
      }

      NameIdentifier nameIdentifier = searchEntityIdentifier.entityIdent();
      Entity.EntityType entityType = searchEntityIdentifier.entityType();
      String metalake = NameIdentifierUtil.getMetalake(nameIdentifier);

      List<SearchEntitiesDTO> entities;

      do {
        entities =
            service.storage.search(
                metalake,
                null,
                entityType == Entity.EntityType.METALAKE
                    ? createUpdateTimeCondition(createTime)
                    : createRemovedEntityCondition(nameIdentifier, cascade, createTime),
                null,
                MAX_DELETE_BATCH_SIZE,
                0);

        for (SearchEntitiesDTO searchEntitiesDTO : entities) {
          List<Long> entityIds = new ArrayList<>();
          for (SearchEntityDTO entity : searchEntitiesDTO.getEntities()) {
            entityIds.add(entity.getEntityId());
          }
          service.storage.delete(metalake, entityIds, searchEntitiesDTO.getType());
        }
      } while (!entities.isEmpty());
    } catch (Exception e) {
      LOG.error(
          "Error removing deleted data from storage for task {}: {}",
          taskId,
          searchEntityIdentifier,
          e);
      return false;
    }

    return true;
  }
}
