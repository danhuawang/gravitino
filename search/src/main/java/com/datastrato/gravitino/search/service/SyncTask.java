/*
 * Copyright 2024 Datastrato Pvt Ltd.
 * This software is licensed under the Apache License version 2.
 */
package com.datastrato.gravitino.search.service;

import com.datastrato.gravitino.search.po.SearchEntityPO;
import java.util.ArrayList;
import java.util.List;
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

  public SyncTask(
      String taskId,
      String metalake,
      MetadataObject metadataObject,
      boolean cascade,
      SearchService service) {
    this.taskId = taskId;
    this.service = service;
    this.isRootTask = true;

    this.searchEntityIdentifier = new SearchEntityIdentifier(metadataObject, metalake);
    this.source = SearchEntitySource.createSearchEntitySource(searchEntityIdentifier, cascade);

    splitTask();
  }

  private SyncTask(
      String taskId,
      SearchEntityIdentifier identifier,
      SearchEntitySource source,
      SearchService service) {
    this.taskId = taskId;
    this.searchEntityIdentifier = identifier;
    this.source = source;
    this.service = service;
    this.isRootTask = false;
  }

  public void splitTask() {
    // split the task into subtasks by entity count
    int entityCount = source.approximateEntityCount(searchEntityIdentifier);
    int numChildTask =
        Math.min(entityCount / service.getEntityProcessBatchSize(), service.getMaxThreadNnm() - 1);

    if (numChildTask == 0) {
      // entityCount is not enough, no need to split child sync task, just process it in this task
      return;
    }

    for (int i = 0; i < numChildTask; i++) {
      SyncTask syncTask = new SyncTask(taskId + "_" + i, searchEntityIdentifier, source, service);
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
      return;
    }

    for (SyncTask subTask : subTasks) {
      if (!subTask.finished()) {
        try {
          subTask.waitToFinished();
        } catch (InterruptedException e) {
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
    LOG.info(stringBuilder.toString());
  }

  public void waitToFinished() throws InterruptedException {
    synchronized (finishedLock) {
      finishedLock.wait();
    }
  }
}
