/*
 * Copyright 2024 Datastrato Pvt Ltd.
 * This software is licensed under the Apache License version 2.
 */
package com.datastrato.gravitino.search.dto;

import com.fasterxml.jackson.databind.annotation.JsonDeserialize;
import com.google.common.base.Preconditions;
import lombok.EqualsAndHashCode;
import lombok.Getter;
import org.apache.commons.lang3.StringUtils;
import org.apache.gravitino.Catalog;

@EqualsAndHashCode(callSuper = true)
@Getter
@JsonDeserialize(builder = SearchCatalogEntityDTO.SearchCatalogEntityDTOBuilder.class)
public class SearchCatalogEntityDTO extends SearchEntityDTO {

  private final String provider;

  private final Catalog.Type type;

  private SearchCatalogEntityDTO(SearchCatalogEntityDTOBuilder builder) {
    super(builder);
    this.provider = builder.provider;
    this.type = builder.type;
  }

  public static class SearchCatalogEntityDTOBuilder
      extends SearchEntityDTO.Builder<SearchCatalogEntityDTOBuilder, SearchCatalogEntityDTO> {

    private String provider;
    private Catalog.Type type;

    private SearchCatalogEntityDTOBuilder() {
      super();
    }

    public SearchCatalogEntityDTOBuilder withProvider(String provider) {
      this.provider = provider;
      return this;
    }

    public SearchCatalogEntityDTOBuilder withType(Catalog.Type type) {
      this.type = type;
      return this;
    }

    public static SearchCatalogEntityDTOBuilder builder() {
      return new SearchCatalogEntityDTOBuilder();
    }

    @Override
    protected void validate() {
      super.validate();
      Preconditions.checkArgument(StringUtils.isNotBlank(provider), "\"provider\" cannot be blank");
      Preconditions.checkArgument(type != null, "\"type\" cannot be null");
    }

    @Override
    protected SearchCatalogEntityDTO internalBuild() {
      validate();
      return new SearchCatalogEntityDTO(this);
    }
  }
}
