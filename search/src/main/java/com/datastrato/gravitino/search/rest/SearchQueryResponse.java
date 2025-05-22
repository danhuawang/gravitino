/*
 * Copyright 2024 Datastrato Pvt Ltd.
 * This software is licensed under the Apache License version 2.
 */
package com.datastrato.gravitino.search.rest;

import com.datastrato.gravitino.search.dto.SearchEntitiesDTO;
import com.fasterxml.jackson.annotation.JsonProperty;
import java.util.List;
import lombok.EqualsAndHashCode;
import lombok.Getter;
import lombok.ToString;
import org.apache.gravitino.dto.responses.BaseResponse;

@Getter
@ToString
@EqualsAndHashCode(callSuper = true)
public class SearchQueryResponse extends BaseResponse {

  @JsonProperty("entities")
  private final List<SearchEntitiesDTO> entities;

  public SearchQueryResponse(List<SearchEntitiesDTO> entities) {
    super(0);
    this.entities = entities;
  }

  public SearchQueryResponse() {
    super(0);
    this.entities = null;
  }
}
