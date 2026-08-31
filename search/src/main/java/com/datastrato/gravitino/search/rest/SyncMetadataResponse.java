/*
 * Copyright 2024 Datastrato Inc.
 */
package com.datastrato.gravitino.search.rest;

import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.EqualsAndHashCode;
import lombok.Getter;
import lombok.ToString;
import org.apache.gravitino.dto.responses.BaseResponse;

@Getter
@EqualsAndHashCode(callSuper = true)
@ToString
public class SyncMetadataResponse extends BaseResponse {

  @JsonProperty private final String taskId;

  public SyncMetadataResponse(String taskId) {
    super(0);
    this.taskId = taskId;
  }

  public SyncMetadataResponse() {
    super(0);
    this.taskId = null;
  }
}
