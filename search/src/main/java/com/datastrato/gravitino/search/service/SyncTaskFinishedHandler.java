/*
 * Copyright 2024 Datastrato Pvt Ltd.
 * This software is licensed under the Apache License version 2.
 */
package com.datastrato.gravitino.search.service;

/**
 * Interface for handling the completion of a sync task. Implementations should define what happens
 * when a sync task finishes.
 */
interface SyncTaskFinishedHandler {
  /**
   * Called when a sync task has finished.
   *
   * @param task the sync task that has finished
   * @return true if the task was handled successfully, false otherwise
   */
  boolean onTaskFinished(SyncTask task);
}
