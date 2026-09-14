/*
 * Copyright 2026 Datastrato Inc.
 */
package com.datastrato.gravitino.dto.responses;

import com.datastrato.gravitino.dto.rel.TableWithColumnTagsDTO;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.google.common.base.Preconditions;
import lombok.EqualsAndHashCode;
import lombok.Getter;
import lombok.ToString;
import org.apache.gravitino.dto.responses.BaseResponse;

/** Represents a response containing a table whose columns include effective tags. */
@Getter
@ToString
@EqualsAndHashCode(callSuper = true)
public class TableWithColumnTagsResponse extends BaseResponse {

  @JsonProperty("table")
  private final TableWithColumnTagsDTO table;

  /**
   * Creates a successful table response.
   *
   * @param table The table with extended columns.
   */
  public TableWithColumnTagsResponse(TableWithColumnTagsDTO table) {
    super(0);
    this.table = Preconditions.checkNotNull(table, "table cannot be null");
  }

  /** Default constructor for Jackson deserialization. */
  protected TableWithColumnTagsResponse() {
    super();
    this.table = null;
  }

  /**
   * Validates the response.
   *
   * @throws IllegalArgumentException If the response or table is invalid.
   */
  @Override
  public void validate() throws IllegalArgumentException {
    super.validate();
    Preconditions.checkArgument(table != null, "table cannot be null");
    table.validate();
  }
}
