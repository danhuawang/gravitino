/*
 * Copyright 2024 Datastrato Pvt Ltd.
 * This software is licensed under the Apache License version 2.
 */
package com.datastrato.gravitino.search.service;

import lombok.Builder;
import lombok.Getter;

@Getter
@Builder(toBuilder = true, setterPrefix = "with")
public class TaskStatus {

  public enum TaskStatusEnum {
    SUBMITTED,
    RUNNING,
    COMPLETED,
    FAILED,
    INTERRUPTED;
  }

  private final String taskId;

  // Number of subtasks that this task has.
  private final int subTaskNum;

  private final String taskStatus;

  // Store more extra message other than the status, such as error
  // message or progress information
  private final String message;

  private final String metalake;

  private final String metadataObject;

  private boolean cascade;

  private Long taskCreateTime;

  private Long taskUpdateTime;
}
