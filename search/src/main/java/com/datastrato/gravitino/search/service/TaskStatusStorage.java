/*
 * Copyright 2024 Datastrato Pvt Ltd.
 * This software is licensed under the Apache License version 2.
 */
package com.datastrato.gravitino.search.service;

import org.apache.gravitino.Config;

public interface TaskStatusStorage {

  /**
   * Initializes the task storage with the given configuration.
   *
   * @param config The configuration to be used for initialization.
   */
  void initialize(Config config);

  /**
   * Updates the task status in the storage.
   *
   * @param taskStatus The task status to be updated.
   */
  void update(TaskStatus taskStatus);

  /**
   * Saves the task status in the storage. If overwrite is true, it will overwrite the existing task
   * status with the same taskId.
   *
   * @param taskStatus The task status to be saved.
   * @param overwrite If true, it will overwrite the existing task status with the same taskId.
   */
  void save(TaskStatus taskStatus, boolean overwrite);

  /**
   * Removes the task status from the storage.
   *
   * @param taskId The task ID of the task status to be removed.
   * @return true if the task status was successfully removed, false otherwise.
   */
  boolean remove(String taskId);

  /**
   * Retrieves the task status from the storage.
   *
   * @param taskId The task ID of the task status to be retrieved.
   * @return the TaskStatus object if found, null otherwise.
   */
  TaskStatus getTaskStatus(String taskId);
}
