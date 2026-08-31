/*
 * Copyright 2024 Datastrato Inc.
 */
package com.datastrato.gravitino.dto.requests;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.google.common.base.Preconditions;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;
import javax.annotation.Nullable;
import lombok.EqualsAndHashCode;
import lombok.Getter;
import lombok.ToString;
import org.apache.commons.lang3.StringUtils;
import org.apache.gravitino.dto.rel.ColumnDTO;
import org.apache.gravitino.dto.rel.DistributionDTO;
import org.apache.gravitino.dto.rel.SortOrderDTO;
import org.apache.gravitino.dto.rel.expressions.FunctionArg;
import org.apache.gravitino.dto.rel.indexes.IndexDTO;
import org.apache.gravitino.dto.rel.partitioning.Partitioning;
import org.apache.gravitino.rest.RESTRequest;

/**
 * Represents a request to create a table with tags.
 *
 * <p>Note: Cannot extend TableCreateRequest directly because it uses @Builder and @Jacksonized
 * annotations, which cannot ignore the unrecognized fields in the subclasses.
 */
@Getter
@EqualsAndHashCode
@ToString
public class TableWithTagsCreateRequest implements RESTRequest {

  @JsonProperty("name")
  private final String name;

  @Nullable
  @JsonProperty("comment")
  private final String comment;

  @JsonProperty("columns")
  private final ColumnDTO[] columns;

  @Nullable
  @JsonProperty("properties")
  private final Map<String, String> properties;

  @JsonProperty("sortOrders")
  @Nullable
  private final SortOrderDTO[] sortOrders;

  @JsonProperty("distribution")
  @Nullable
  private final DistributionDTO distribution;

  @Nullable
  @JsonProperty("partitioning")
  private final Partitioning[] partitioning;

  @Nullable
  @JsonProperty("indexes")
  private final IndexDTO[] indexes;

  @Nullable
  @JsonProperty("tagsToAdd")
  private final String[] tagsToAdd;

  @Nullable
  @JsonProperty("columnTags")
  private final Map<String, String[]> columnTags;

  /**
   * Create a new TableWithTagsCreateRequest.
   *
   * @param name The name of the table.
   * @param comment The comment of the table.
   * @param columns The columns of the table.
   * @param properties The properties of the table.
   * @param sortOrders The sort orders of the table.
   * @param distribution The distribution of the table.
   * @param partitioning The partitioning of the table.
   * @param indexes The indexes of the table.
   * @param tagsToAdd The tags to add.
   */
  public TableWithTagsCreateRequest(
      String name,
      @Nullable String comment,
      ColumnDTO[] columns,
      @Nullable Map<String, String> properties,
      @Nullable SortOrderDTO[] sortOrders,
      @Nullable DistributionDTO distribution,
      @Nullable Partitioning[] partitioning,
      @Nullable IndexDTO[] indexes,
      @Nullable String[] tagsToAdd,
      @Nullable Map<String, String[]> columnTags) {
    this.name = name;
    this.columns = columns;
    this.comment = comment;
    this.properties = properties;
    this.sortOrders = sortOrders;
    this.distribution = distribution;
    this.partitioning = partitioning;
    this.indexes = indexes;
    this.tagsToAdd = tagsToAdd;
    this.columnTags = columnTags;
  }

  /** This is the constructor that is used by Jackson deserializer */
  public TableWithTagsCreateRequest() {
    this(null, null, null, null, null, null, null, null, null, null);
  }

  /**
   * Validates the request.
   *
   * @throws IllegalArgumentException If the request is invalid, this exception is thrown.
   */
  @Override
  public void validate() throws IllegalArgumentException {
    validateTableRequest();

    if (tagsToAdd != null) {
      for (String tag : tagsToAdd) {
        Preconditions.checkArgument(
            StringUtils.isNotBlank(tag), "tagsToAdd must not contain null or empty tag names");
      }
    }

    if (columnTags != null) {
      Set<String> columnNames =
          Arrays.stream(getColumns()).map(ColumnDTO::name).collect(Collectors.toSet());
      for (Map.Entry<String, String[]> entry : columnTags.entrySet()) {
        Preconditions.checkArgument(
            StringUtils.isNotBlank(entry.getKey()),
            "columnTags must not contain null or empty column names");
        Preconditions.checkArgument(
            columnNames.contains(entry.getKey()),
            "columnTags must contain only valid column names, got: " + entry.getKey());
        for (String tag : entry.getValue()) {
          Preconditions.checkArgument(
              StringUtils.isNotBlank(tag), "columnTags must not contain null or empty tag names");
        }
      }
    }
  }

  private void validateTableRequest() {
    Preconditions.checkArgument(
        StringUtils.isNotBlank(name), "\"name\" field is required and cannot be empty");
    Preconditions.checkArgument(
        columns != null && columns.length != 0,
        "\"columns\" field is required and cannot be empty");

    if (sortOrders != null) {
      Arrays.stream(sortOrders).forEach(sortOrder -> sortOrder.validate(columns));
    }

    if (distribution != null) {
      Arrays.stream((FunctionArg[]) distribution.expressions())
          .forEach(expression -> expression.validate(columns));
    }

    if (partitioning != null) {
      Arrays.stream(partitioning).forEach(p -> p.validate(columns));
    }

    List<ColumnDTO> autoIncrementCols =
        Arrays.stream(columns)
            .map(
                column -> {
                  column.validate();
                  return column;
                })
            .filter(ColumnDTO::autoIncrement)
            .collect(Collectors.toList());
    String autoIncrementColsStr =
        autoIncrementCols.stream().map(ColumnDTO::name).collect(Collectors.joining(",", "[", "]"));
    Preconditions.checkArgument(
        autoIncrementCols.size() <= 1,
        "Only one column can be auto-incremented. There are multiple auto-increment columns in your table: "
            + autoIncrementColsStr);

    if (indexes != null && indexes.length > 0) {
      Arrays.stream(indexes)
          .forEach(
              index -> {
                Preconditions.checkArgument(index.type() != null, "Index type cannot be null");
                Preconditions.checkArgument(
                    index.fieldNames().length > 0, "Index field names cannot be null");
              });
    }
  }
}
