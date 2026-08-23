/*
 * Copyright 2024 Datastrato Pvt Ltd.
 * This software is licensed under the Apache License version 2.
 */
package com.datastrato.gravitino.dto.responses;

import com.datastrato.gravitino.dto.ExtendedSchemaDTO;
import com.datastrato.gravitino.dto.function.ExtendedFunctionDTO;
import com.datastrato.gravitino.dto.rel.ExtendedTableDTO;
import com.datastrato.gravitino.dto.rel.ExtendedViewDTO;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.google.common.base.Preconditions;
import java.util.Arrays;
import lombok.EqualsAndHashCode;
import lombok.Getter;
import lombok.ToString;
import org.apache.gravitino.dto.responses.BaseResponse;

/** Represents a response for a list of extended tables with their information. */
@Getter
@ToString
@EqualsAndHashCode(callSuper = true)
public class TableListResponse extends BaseResponse {

  @JsonProperty("tables")
  private final ExtendedTableDTO[] tables;

  @JsonProperty("functions")
  private final ExtendedFunctionDTO[] functions;

  @JsonProperty("views")
  private final ExtendedViewDTO[] views;

  @JsonProperty("schemas")
  private final ExtendedSchemaDTO[] schemas;

  /**
   * Create a new TableListResponse.
   *
   * @param tables The list of extended tables.
   * @param functions The list of extended functions.
   * @param views The list of extended views.
   * @param schemas The list of extended child schemas for hierarchical schema support.
   */
  public TableListResponse(
      ExtendedTableDTO[] tables,
      ExtendedFunctionDTO[] functions,
      ExtendedViewDTO[] views,
      ExtendedSchemaDTO[] schemas) {
    super(0);
    this.tables = tables;
    this.functions = functions;
    this.views = views;
    this.schemas = schemas;
  }

  /**
   * Create a new TableListResponse.
   *
   * @param tables The list of extended tables.
   * @param functions The list of extended functions.
   * @param views The list of extended views.
   */
  public TableListResponse(
      ExtendedTableDTO[] tables, ExtendedFunctionDTO[] functions, ExtendedViewDTO[] views) {
    this(tables, functions, views, new ExtendedSchemaDTO[0]);
  }

  /**
   * Create a new TableListResponse.
   *
   * @param tables The list of extended tables.
   * @param functions The list of extended functions.
   */
  public TableListResponse(ExtendedTableDTO[] tables, ExtendedFunctionDTO[] functions) {
    this(tables, functions, new ExtendedViewDTO[0]);
  }

  /**
   * Create a new TableListResponse.
   *
   * @param tables The list of extended tables.
   */
  public TableListResponse(ExtendedTableDTO[] tables) {
    this(tables, new ExtendedFunctionDTO[0], new ExtendedViewDTO[0]);
  }

  /** Default constructor for Jackson deserialization. */
  public TableListResponse() {
    super();
    this.tables = null;
    this.functions = null;
    this.views = null;
    this.schemas = null;
  }

  @Override
  public void validate() throws IllegalArgumentException {
    super.validate();

    Preconditions.checkArgument(tables != null, "\"tables\" cannot be null");
    Arrays.stream(tables)
        .forEach(table -> Preconditions.checkArgument(table != null, "table cannot be null"));
    Preconditions.checkArgument(functions != null, "\"functions\" cannot be null");
    Arrays.stream(functions)
        .forEach(
            function -> Preconditions.checkArgument(function != null, "function cannot be null"));
    Preconditions.checkArgument(views != null, "\"views\" cannot be null");
    Arrays.stream(views)
        .forEach(view -> Preconditions.checkArgument(view != null, "view cannot be null"));
    Preconditions.checkArgument(schemas != null, "\"schemas\" cannot be null");
    Arrays.stream(schemas)
        .forEach(schema -> Preconditions.checkArgument(schema != null, "schema cannot be null"));
  }
}
