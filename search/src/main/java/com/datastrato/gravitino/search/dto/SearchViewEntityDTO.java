/*
 * Copyright 2024 Datastrato Pvt Ltd.
 */
package com.datastrato.gravitino.search.dto;

import com.datastrato.gravitino.search.dto.SearchTableEntityDTO.SearchColumnDTO;
import com.datastrato.gravitino.search.po.SearchViewEntityPO;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.databind.annotation.JsonDeserialize;
import java.util.List;
import lombok.EqualsAndHashCode;
import lombok.Getter;

/**
 * The DTO of a view returned by the search API.
 *
 * <p>The view definition is intentionally absent, see {@link SearchViewEntityPO}.
 */
@Getter
@EqualsAndHashCode(callSuper = true)
@JsonDeserialize(builder = SearchViewEntityDTO.SearchViewEntityDTOBuilder.class)
public class SearchViewEntityDTO extends SearchEntityDTO {

  @JsonProperty("columns")
  private final List<SearchColumnDTO> columns;

  private SearchViewEntityDTO(SearchViewEntityDTOBuilder builder) {
    super(builder);
    this.columns = builder.columns;
  }

  /** The builder of {@link SearchViewEntityDTO}. */
  public static class SearchViewEntityDTOBuilder
      extends Builder<SearchViewEntityDTOBuilder, SearchViewEntityDTO> {
    private List<SearchColumnDTO> columns;

    private SearchViewEntityDTOBuilder() {
      super();
    }

    /**
     * Sets the output columns of the view.
     *
     * @param columns The view output columns.
     * @return This builder.
     */
    public SearchViewEntityDTOBuilder withColumns(List<SearchColumnDTO> columns) {
      this.columns = columns;
      return this;
    }

    /**
     * Creates a new builder.
     *
     * @return A new builder instance.
     */
    public static SearchViewEntityDTOBuilder builder() {
      return new SearchViewEntityDTOBuilder();
    }

    @Override
    protected SearchViewEntityDTO internalBuild() {
      validate();
      return new SearchViewEntityDTO(this);
    }
  }
}
