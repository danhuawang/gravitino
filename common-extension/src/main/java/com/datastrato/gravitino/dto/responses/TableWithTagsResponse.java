/*
 * Copyright 2024 Datastrato Inc.
 */
package com.datastrato.gravitino.dto.responses;

import com.fasterxml.jackson.annotation.JsonProperty;
import java.util.Map;
import lombok.EqualsAndHashCode;
import lombok.Getter;
import lombok.ToString;
import org.apache.gravitino.dto.rel.TableDTO;
import org.apache.gravitino.dto.responses.TableResponse;

/** Represents a response containing table information and table tags and column tags. */
@Getter
@ToString
@EqualsAndHashCode(callSuper = true)
public class TableWithTagsResponse extends TableResponse {

  @JsonProperty("tags")
  private final String[] tags;

  @JsonProperty("columnTags")
  private final Map<String, String[]> columnTags;

  /**
   * Constructor for TableWithTagsResponse.
   *
   * @param tableDTO The table data transfer object.
   * @param tags The tags for the table.
   * @param columnTags The tags for the columns.
   */
  public TableWithTagsResponse(TableDTO tableDTO, String[] tags, Map<String, String[]> columnTags) {
    super(tableDTO);
    this.tags = tags;
    this.columnTags = columnTags;
  }

  /** Default constructor for TableWithTagsResponse. (Used for Jackson deserialization.) */
  public TableWithTagsResponse() {
    super();
    this.tags = null;
    this.columnTags = null;
  }

  /**
   * Validates the response data.
   *
   * @throws IllegalArgumentException if the table name, columns or audit is not set.
   */
  @Override
  public void validate() throws IllegalArgumentException {
    if (tags == null) {
      throw new IllegalArgumentException("\"tags\" must not be null");
    }
    if (columnTags == null) {
      throw new IllegalArgumentException("\"columnTags\" must not be null");
    }
    super.validate();
  }
}
