/*
 * Copyright 2024 Datastrato Inc.
 */
package com.datastrato.gravitino.dto.responses;

import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.EqualsAndHashCode;
import lombok.Getter;
import lombok.ToString;
import org.apache.gravitino.dto.SchemaDTO;
import org.apache.gravitino.dto.responses.SchemaResponse;

/** Represents a response containing schema information with tags */
@Getter
@ToString
@EqualsAndHashCode(callSuper = true)
public class SchemaWithTagsResponse extends SchemaResponse {

  @JsonProperty("tags")
  private final String[] tags;

  /**
   * Constructor for SchemaResponse.
   *
   * @param schemaDTO The schema data transfer object.
   * @param tags The tags for the schema.
   */
  public SchemaWithTagsResponse(SchemaDTO schemaDTO, String[] tags) {
    super(schemaDTO);
    this.tags = tags;
  }

  /** Default constructor for SchemaResponse. (Used for Jackson deserialization.) */
  public SchemaWithTagsResponse() {
    super();
    this.tags = null;
  }

  /**
   * Validates the response data.
   *
   * @throws IllegalArgumentException if the schema name, type or audit is not set.
   */
  @Override
  public void validate() throws IllegalArgumentException {
    if (tags == null) {
      throw new IllegalArgumentException("\"tags\" must not be null");
    }
    super.validate();
  }
}
