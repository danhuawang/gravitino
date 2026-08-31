/*
 * Copyright 2024 Datastrato Inc.
 */
package com.datastrato.gravitino.dto.responses;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.google.common.base.Preconditions;
import java.util.Map;
import javax.annotation.Nullable;
import lombok.EqualsAndHashCode;
import lombok.Getter;
import lombok.ToString;
import org.apache.commons.lang3.StringUtils;
import org.apache.gravitino.dto.rel.ColumnDTO;
import org.apache.gravitino.dto.responses.BaseResponse;

/** Represents a response containing data preview result information. */
@Getter
@ToString
@EqualsAndHashCode(callSuper = true)
public class DataPreviewResponse extends BaseResponse {
  @JsonProperty("statusCode")
  private int statusCode;

  @JsonProperty("message")
  private String message;

  @Nullable
  @JsonProperty("columns")
  private ColumnDTO[] columns;

  @Nullable
  @JsonInclude
  @JsonProperty("results")
  private Map<String, Object>[] results;

  /**
   * Creates a new PreviewResponse.
   *
   * @param statusCode The code of the data preview result. 0 represents that succeed to preview the
   *     data, other code represents different cases, such as preview a data sensitive table or
   *     preview data is preparing, etc.
   * @param message The human-readable message to help users realize the result status.
   * @param columns The columns of the relational metadata object.
   * @param results The data preview result of the relational metadata object.
   */
  public DataPreviewResponse(
      int statusCode, String message, ColumnDTO[] columns, Map<String, Object>[] results) {
    super();
    this.statusCode = statusCode;
    this.message = message;
    this.columns = columns;
    this.results = results;
  }

  /**
   * This is the constructor that is used by Jackson deserializer to create an instance of
   * PreviewResponse.
   */
  public DataPreviewResponse() {
    super();
    this.statusCode = 0;
    this.message = null;
    this.columns = null;
    this.results = null;
  }

  @Override
  public void validate() throws IllegalArgumentException {
    super.validate();

    Preconditions.checkArgument(StringUtils.isNotBlank(message), "\"message\" can't be blank");
  }
}
