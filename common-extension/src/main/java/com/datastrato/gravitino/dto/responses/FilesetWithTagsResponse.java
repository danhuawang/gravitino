/*
 * Copyright 2024 Datastrato Inc.
 */
package com.datastrato.gravitino.dto.responses;

import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.EqualsAndHashCode;
import lombok.Getter;
import lombok.ToString;
import org.apache.gravitino.dto.file.FilesetDTO;
import org.apache.gravitino.dto.responses.FilesetResponse;

/** Represents a response containing fileset information with tags. */
@Getter
@ToString
@EqualsAndHashCode(callSuper = true)
public class FilesetWithTagsResponse extends FilesetResponse {

  @JsonProperty("tags")
  private final String[] tags;

  /**
   * Constructor for FilesetWithTagsResponse.
   *
   * @param filesetDTO The fileset data transfer object.
   * @param tags The tags for the fileset.
   */
  public FilesetWithTagsResponse(FilesetDTO filesetDTO, String[] tags) {
    super(filesetDTO);
    this.tags = tags;
  }

  /** Default constructor for FilesetWithTagsResponse. (Used for Jackson deserialization.) */
  public FilesetWithTagsResponse() {
    super();
    this.tags = null;
  }

  /**
   * Validates the response data.
   *
   * @throws IllegalArgumentException if the fileset name, type or audit is not set.
   */
  @Override
  public void validate() throws IllegalArgumentException {
    if (tags == null) {
      throw new IllegalArgumentException("\"tags\" must not be null");
    }
    super.validate();
  }
}
