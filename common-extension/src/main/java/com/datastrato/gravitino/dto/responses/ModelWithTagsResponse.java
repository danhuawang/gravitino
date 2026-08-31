/*
 * Copyright 2024 Datastrato Inc.
 */
package com.datastrato.gravitino.dto.responses;

import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.EqualsAndHashCode;
import lombok.Getter;
import lombok.ToString;
import org.apache.gravitino.dto.model.ModelDTO;
import org.apache.gravitino.dto.responses.ModelResponse;

/** Represents a response containing model information with tags. */
@Getter
@ToString
@EqualsAndHashCode(callSuper = true)
public class ModelWithTagsResponse extends ModelResponse {

  @JsonProperty("tags")
  private final String[] tags;

  /** Default constructor for ModelWithTagsResponse. (Used for Jackson deserialization.) */
  public ModelWithTagsResponse() {
    super();
    this.tags = null;
  }

  /**
   * Constructor for ModelWithTagsResponse.
   *
   * @param modelDTO The model data transfer object.
   * @param tags The tags for the model.
   */
  public ModelWithTagsResponse(ModelDTO modelDTO, String[] tags) {
    super(modelDTO);
    this.tags = tags;
  }

  /**
   * Validates the response data.
   *
   * @throws IllegalArgumentException if the model name, type or audit is not set.
   */
  @Override
  public void validate() throws IllegalArgumentException {
    if (tags == null) {
      throw new IllegalArgumentException("\"tags\" must not be null");
    }
    super.validate();
  }
}
