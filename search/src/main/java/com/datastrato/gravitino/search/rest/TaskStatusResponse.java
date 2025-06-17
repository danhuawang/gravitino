package com.datastrato.gravitino.search.rest;
/*
 * Copyright 2024 Datastrato Pvt Ltd.
 * This software is licensed under the Apache License version 2.
 */
import com.datastrato.gravitino.search.dto.TaskStatusDTO;
import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.EqualsAndHashCode;
import lombok.Getter;
import lombok.ToString;
import org.apache.gravitino.dto.responses.BaseResponse;

@Getter
@ToString
@EqualsAndHashCode(callSuper = true)
public class TaskStatusResponse extends BaseResponse {

  @JsonProperty("taskStatus")
  private final TaskStatusDTO taskStatusDTO;

  public TaskStatusResponse(TaskStatusDTO taskStatusDTO) {
    super(0);
    this.taskStatusDTO = taskStatusDTO;
  }

  public TaskStatusResponse() {
    super(0);
    this.taskStatusDTO = null;
  }
}
