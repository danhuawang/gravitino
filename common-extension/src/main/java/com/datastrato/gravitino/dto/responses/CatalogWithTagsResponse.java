/*
 * Copyright 2024 Datastrato Inc.
 */
package com.datastrato.gravitino.dto.responses;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.google.common.base.Preconditions;
import lombok.EqualsAndHashCode;
import lombok.Getter;
import lombok.ToString;
import org.apache.gravitino.dto.CatalogDTO;
import org.apache.gravitino.dto.responses.CatalogResponse;

/** Represents a response containing catalog information. */
@Getter
@ToString
@EqualsAndHashCode(callSuper = true)
public class CatalogWithTagsResponse extends CatalogResponse {

  @JsonProperty("tags")
  private final String[] tags;

  /**
   * Constructor for CatalogResponse.
   *
   * @param catalogDTO The catalog data transfer object.
   * @param tags The tags for the catalog.
   */
  public CatalogWithTagsResponse(CatalogDTO catalogDTO, String[] tags) {
    super(catalogDTO);
    this.tags = tags;
  }

  /** Default constructor for CatalogResponse. (Used for Jackson deserialization.) */
  public CatalogWithTagsResponse() {
    super();
    this.tags = null;
  }

  /**
   * Validates the response data.
   *
   * @throws IllegalArgumentException if the catalog name, type or audit is not set.
   */
  @Override
  public void validate() throws IllegalArgumentException {
    Preconditions.checkArgument(tags != null, "\"tags\" must not be null");
    super.validate();
  }
}
