/*
 * Copyright 2024 Datastrato Pvt Ltd.
 */
package com.datastrato.gravitino.search.po;

import com.datastrato.gravitino.search.po.SearchTableEntityPO.SearchColumn;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.databind.annotation.JsonDeserialize;
import java.util.List;
import lombok.EqualsAndHashCode;
import lombok.Getter;

/**
 * The persistent object of a view stored in the search backend.
 *
 * <p>Only the metadata needed to <em>discover</em> a view is indexed. The view definition itself
 * (its SQL representations and the default catalog/schema used to resolve it) is deliberately left
 * out: search does not match on it, and the Gravitino view API is the place to read it, where the
 * caller's view visibility is enforced.
 */
@Getter
@EqualsAndHashCode(callSuper = true)
@JsonDeserialize(builder = SearchViewEntityPO.SearchViewEntityPOBuilder.class)
public class SearchViewEntityPO extends SearchEntityPO {

  @JsonProperty("columns")
  private final List<SearchColumn> columns;

  private SearchViewEntityPO(SearchViewEntityPOBuilder builder) {
    super(builder);
    this.columns = builder.columns;
  }

  /** The builder of {@link SearchViewEntityPO}. */
  public static class SearchViewEntityPOBuilder
      extends SearchEntityPO.Builder<SearchViewEntityPOBuilder, SearchViewEntityPO> {
    private List<SearchColumn> columns;

    private SearchViewEntityPOBuilder() {
      super();
    }

    /**
     * Sets the output columns of the view.
     *
     * @param columns The view output columns.
     * @return This builder.
     */
    public SearchViewEntityPOBuilder withColumns(List<SearchColumn> columns) {
      this.columns = columns;
      return this;
    }

    /**
     * Creates a new builder.
     *
     * @return A new builder instance.
     */
    public static SearchViewEntityPOBuilder builder() {
      return new SearchViewEntityPOBuilder();
    }

    @Override
    SearchViewEntityPO internalBuild() {
      validate();
      return new SearchViewEntityPO(this);
    }
  }
}
