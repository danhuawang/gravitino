/*
 * Copyright 2024 Datastrato Pvt Ltd.
 * This software is licensed under the Apache License version 2.
 */
package com.datastrato.gravitino.search.dto;

import com.fasterxml.jackson.databind.annotation.JsonDeserialize;
import com.google.common.base.Preconditions;
import java.util.List;
import lombok.EqualsAndHashCode;

@EqualsAndHashCode(callSuper = true)
@JsonDeserialize(builder = SearchTableEntityDTO.SearchTableEntityDTOBuilder.class)
public class SearchTableEntityDTO extends SearchEntityDTO {

  private final List<SearchColumnDTO> columns;

  private SearchTableEntityDTO(SearchTableEntityDTOBuilder builder) {
    super(builder);
    this.columns = builder.columns;
  }

  public static class SearchTableEntityDTOBuilder
      extends SearchTableEntityDTO.Builder<SearchTableEntityDTOBuilder, SearchTableEntityDTO> {
    private List<SearchColumnDTO> columns;

    private SearchTableEntityDTOBuilder() {
      super();
    }

    public SearchTableEntityDTOBuilder withColumns(List<SearchColumnDTO> columns) {
      this.columns = columns;
      return this;
    }

    public static SearchTableEntityDTOBuilder builder() {
      return new SearchTableEntityDTOBuilder();
    }

    @Override
    protected void validate() {
      super.validate();
      Preconditions.checkArgument(
          columns != null && !columns.isEmpty(), "\"columns\" cannot be null or empty");
    }

    @Override
    protected SearchTableEntityDTO internalBuild() {
      validate();
      return new SearchTableEntityDTO(this);
    }
  }

  @JsonDeserialize(builder = SearchColumnDTO.Builder.class)
  public static class SearchColumnDTO {
    private String columnName;

    private String columnComment;

    public static class Builder {
      private String columnName;
      private String columnComment;

      public Builder withColumnName(String columnName) {
        this.columnName = columnName;
        return this;
      }

      public Builder withColumnComment(String columnComment) {
        this.columnComment = columnComment;
        return this;
      }

      public SearchColumnDTO build() {
        Preconditions.checkArgument(
            columnName != null && !columnName.isEmpty(), "columnName cannot be null or empty");
        return new SearchColumnDTO(this);
      }
    }

    private SearchColumnDTO(Builder builder) {
      this.columnName = builder.columnName;
      this.columnComment = builder.columnComment;
    }
  }
}
