/*
 * Copyright 2024 Datastrato Inc.
 */
package com.datastrato.gravitino.search.service;

import java.util.concurrent.ConcurrentHashMap;
import org.apache.gravitino.Config;

public class InMemoryTaskStatusStorage implements TaskStatusStorage {

  private final ConcurrentHashMap<String, TaskStatus> taskStatusMap = new ConcurrentHashMap<>();

  @Override
  public void initialize(Config config) {
    // Do nothing for in-memory storage
  }

  @Override
  public void update(TaskStatus taskStatus) {
    taskStatusMap.put(taskStatus.getTaskId(), taskStatus);
  }

  @Override
  public void save(TaskStatus taskStatus, boolean overwrite) {
    if (overwrite) {
      taskStatusMap.put(taskStatus.getTaskId(), taskStatus);
    } else {
      taskStatusMap.putIfAbsent(taskStatus.getTaskId(), taskStatus);
    }
  }

  @Override
  public boolean remove(String taskId) {
    return taskStatusMap.remove(taskId) != null;
  }

  @Override
  public TaskStatus getTaskStatus(String taskId) {
    return taskStatusMap.get(taskId);
  }
}
