/*
 * Copyright 2024 Datastrato Pvt Ltd.
 * This software is licensed under the Apache License version 2.
 */
package com.datastrato.gravitino.search.po;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.databind.annotation.JsonDeserialize;
import com.google.common.base.Preconditions;
import java.util.List;
import lombok.EqualsAndHashCode;
import org.apache.commons.lang3.StringUtils;

@EqualsAndHashCode(callSuper = true)
@JsonDeserialize(builder = SearchTableEntityPO.SearchTableEntityPOBuilder.class)
public class SearchTableEntityPO extends SearchEntityPO {

  @JsonProperty("columns")
  private final List<SearchColumn> columns;

  private SearchTableEntityPO(SearchTableEntityPOBuilder builder) {
    super(builder);
    this.columns = builder.columns;
  }

  public static class SearchTableEntityPOBuilder
      extends SearchEntityPO.Builder<SearchTableEntityPOBuilder, SearchTableEntityPO> {
    private List<SearchColumn> columns;

    private SearchTableEntityPOBuilder() {
      super();
    }

    public SearchTableEntityPOBuilder withColumns(List<SearchColumn> columns) {
      this.columns = columns;
      return this;
    }

    public static SearchTableEntityPOBuilder builder() {
      return new SearchTableEntityPOBuilder();
    }

    @Override
    protected void validate() {
      super.validate();
      Preconditions.checkArgument(
          columns != null && !columns.isEmpty(), "\"columns\" cannot be null or empty");
    }

    @Override
    SearchTableEntityPO internalBuild() {
      validate();

      return new SearchTableEntityPO(this);
    }
  }

  @SuppressWarnings("unused")
  @JsonDeserialize(builder = SearchColumn.Builder.class)
  public static class SearchColumn {
    @JsonProperty("column_name")
    private final String columnName;

    @JsonProperty("column_comment")
    private final String columnComment;

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

      public SearchColumn build() {
        Preconditions.checkArgument(
            StringUtils.isNotBlank(columnName), "\"columnName\" cannot be null or empty");
        return new SearchColumn(this);
      }
    }

    private SearchColumn(Builder builder) {
      this.columnName = builder.columnName;
      this.columnComment = builder.columnComment;
    }

    public static Builder builder() {
      return new Builder();
    }
  }
}
