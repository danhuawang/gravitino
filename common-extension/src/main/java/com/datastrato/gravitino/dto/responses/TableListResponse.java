/*
 * Copyright 2024 Datastrato Pvt Ltd.
 * This software is licensed under the Apache License version 2.
 */
package com.datastrato.gravitino.dto.responses;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.google.common.base.Preconditions;
import java.util.Arrays;
import lombok.EqualsAndHashCode;
import lombok.Getter;
import lombok.ToString;
import org.apache.gravitino.dto.SchemaDTO;
import org.apache.gravitino.dto.function.FunctionDTO;
import org.apache.gravitino.dto.rel.TableDTO;
import org.apache.gravitino.dto.rel.ViewDTO;
import org.apache.gravitino.dto.responses.BaseResponse;

/** Represents a response for a list of tables with their information. */
@Getter
@ToString
@EqualsAndHashCode(callSuper = true)
public class TableListResponse extends BaseResponse {

  @JsonProperty("tables")
  private final TableDTO[] tables;

  @JsonProperty("functions")
  private final FunctionDTO[] functions;

  @JsonProperty("views")
  private final ViewDTO[] views;

  @JsonProperty("schemas")
  private final SchemaDTO[] schemas;

  /**
   * Create a new TableListResponse.
   *
   * @param tables The list of tables.
   * @param functions The list of functions.
   * @param views The list of views.
   * @param schemas The list of child schemas for hierarchical schema support.
   */
  public TableListResponse(
      TableDTO[] tables, FunctionDTO[] functions, ViewDTO[] views, SchemaDTO[] schemas) {
    super(0);
    this.tables = tables;
    this.functions = functions;
    this.views = views;
    this.schemas = schemas;
  }

  /**
   * Create a new TableListResponse.
   *
   * @param tables The list of tables.
   * @param functions The list of functions.
   * @param views The list of views.
   */
  public TableListResponse(TableDTO[] tables, FunctionDTO[] functions, ViewDTO[] views) {
    this(tables, functions, views, new SchemaDTO[0]);
  }

  /**
   * Create a new TableListResponse.
   *
   * @param tables The list of tables.
   * @param functions The list of functions.
   */
  public TableListResponse(TableDTO[] tables, FunctionDTO[] functions) {
    this(tables, functions, new ViewDTO[0]);
  }

  /**
   * Create a new TableListResponse.
   *
   * @param tables The list of tables.
   */
  public TableListResponse(TableDTO[] tables) {
    this(tables, new FunctionDTO[0], new ViewDTO[0]);
  }

  /**
   * This is the constructor that is used by Jackson deserializer to create an instance of
   * TableListResponse.
   */
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
