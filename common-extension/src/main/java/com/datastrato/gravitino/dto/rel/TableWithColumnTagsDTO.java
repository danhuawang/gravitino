/*
 * Copyright 2026 Datastrato Inc.
 */
package com.datastrato.gravitino.dto.rel;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.annotation.JsonUnwrapped;
import com.google.common.base.Preconditions;
import java.util.Arrays;
import javax.annotation.Nullable;
import lombok.EqualsAndHashCode;
import lombok.Getter;
import lombok.ToString;
import org.apache.commons.lang3.StringUtils;
import org.apache.gravitino.dto.rel.TableDTO;
import org.apache.gravitino.dto.util.DTOConverters;

/** Represents a table DTO whose columns include effective tags. */
@Getter
@ToString
@EqualsAndHashCode
public class TableWithColumnTagsDTO {

  @JsonUnwrapped
  @JsonIgnoreProperties("columns")
  @Nullable
  private final TableDTO tableDTO;

  @JsonProperty("columns")
  @Nullable
  private final ExtendedColumnDTO[] columns;

  /** Default constructor for Jackson deserialization. */
  protected TableWithColumnTagsDTO() {
    this.tableDTO = null;
    this.columns = null;
  }

  /**
   * Creates a table DTO with extended columns.
   *
   * @param tableDTO The base table DTO.
   * @param columns The extended columns.
   */
  public TableWithColumnTagsDTO(TableDTO tableDTO, ExtendedColumnDTO[] columns) {
    this.tableDTO = withoutColumns(Preconditions.checkNotNull(tableDTO, "tableDTO cannot be null"));
    this.columns = Preconditions.checkNotNull(columns, "columns cannot be null");
  }

  /**
   * Returns the table name.
   *
   * @return The table name, or {@code null} when the DTO has not been deserialized yet.
   */
  @Nullable
  public String name() {
    return tableDTO == null ? null : tableDTO.name();
  }

  /**
   * Validates the table DTO and its extended columns.
   *
   * @throws IllegalArgumentException If required table or column data is absent.
   */
  public void validate() throws IllegalArgumentException {
    Preconditions.checkArgument(tableDTO != null, "tableDTO cannot be null");
    Preconditions.checkArgument(
        StringUtils.isNotBlank(tableDTO.name()), "table name cannot be null or empty");
    Preconditions.checkArgument(tableDTO.auditInfo() != null, "table audit cannot be null");
    Preconditions.checkArgument(columns != null, "columns cannot be null");
    Arrays.stream(columns)
        .forEach(
            column -> {
              Preconditions.checkArgument(column != null, "column cannot be null");
              column.validate();
            });
  }

  private static TableDTO withoutColumns(TableDTO tableDTO) {
    return TableDTO.builder()
        .withName(tableDTO.name())
        .withComment(tableDTO.comment())
        .withProperties(tableDTO.properties())
        .withAudit(tableDTO.auditInfo())
        .withDistribution(DTOConverters.toDTO(tableDTO.distribution()))
        .withSortOrders(DTOConverters.toDTOs(tableDTO.sortOrder()))
        .withPartitioning(DTOConverters.toDTOs(tableDTO.partitioning()))
        .withIndex(DTOConverters.toDTOs(tableDTO.index()))
        .build();
  }
}
