/*
 * Copyright 2024 Datastrato Pvt Ltd.
 * This software is licensed under the Apache License version 2.
 */
package com.datastrato.gravitino.search.service;

import com.datastrato.gravitino.search.po.SearchEntityPO;
import com.datastrato.gravitino.search.service.TaskStatus.TaskStatusEnum;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.stream.Collectors;
import org.apache.gravitino.MetadataObject;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class SyncTask {
  private static final Logger LOG = LoggerFactory.getLogger(SyncTask.class);

  private final String taskId;
  private final SearchService service;
  private final SearchEntitySource source;
  private final SearchEntityIdentifier searchEntityIdentifier;

  private final Object finishedLock = new Object();
  private boolean finished = false;

  // root task can be split into subtasks to process in parallel
  private final boolean isRootTask;

  private final List<SyncTask> subTasks = new ArrayList<>();

  private final TaskStatus taskStatus;

  public SyncTask(
      String metalake, MetadataObject metadataObject, boolean cascade, SearchService service) {
    this.searchEntityIdentifier = new SearchEntityIdentifier(metadataObject, metalake);
    this.taskId = createTaskId();
    this.service = service;
    this.isRootTask = true;
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
            service.getMaxSyncMetadataThreadNnm() - 1);

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
        subTasks.stream().map(SyncTask::getTaskId).collect(Collectors.joining()));
  }

  public boolean processOnBatch() {
    if (source.finished()) {
      handleTaskFinished();
      synchronized (finishedLock) {
        finished = true;
        finishedLock.notifyAll();
      }
      return false;
    }

    List<SearchEntityPO> searchEntityPOs = source.nextBatch(service.getEntityProcessBatchSize());
    if (!searchEntityPOs.isEmpty()) {
      service.storage.write(searchEntityPOs);
    }
    return true;
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

    StringBuilder stringBuilder = new StringBuilder();
    stringBuilder.append(String.format("Task %s process finished.", taskId));
    if (!source.getProcessFailedEntities().isEmpty()) {
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
}
