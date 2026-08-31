/*
 * Copyright 2024 Datastrato Inc.
 */
package com.datastrato.gravitino.search.dto;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.databind.annotation.JsonDeserialize;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;

@Builder(toBuilder = true, setterPrefix = "with")
@Getter
@AllArgsConstructor
@JsonDeserialize(builder = TaskStatusDTO.TaskStatusDTOBuilder.class)
public class TaskStatusDTO {

  @JsonProperty("taskId")
  private final String taskId;

  @JsonProperty("subTaskNum")
  private final int subTaskNum;

  @JsonProperty("taskStatus")
  private final String taskStatus;

  @JsonProperty("message")
  private final String message;

  @JsonProperty("metalake")
  private final String metalake;

  @JsonProperty("metadataObject")
  private final String metadataObject;

  @JsonProperty("cascade")
  private final boolean cascade;

  @JsonProperty("taskCreateTime")
  private final Long taskCreateTime;

  @JsonProperty("taskUpdateTime")
  private final Long taskUpdateTime;
}
