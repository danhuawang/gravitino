/*
 * Copyright 2024 Datastrato Inc.
 */
package com.datastrato.gravitino.search.po;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.databind.annotation.JsonDeserialize;
import com.google.common.base.Preconditions;
import java.util.Collections;
import java.util.List;
import lombok.EqualsAndHashCode;
import org.apache.gravitino.Catalog;

@EqualsAndHashCode(callSuper = true)
@JsonDeserialize(builder = SearchCatalogEntityPO.SearchCatalogEntityPOBuilder.class)
public class SearchCatalogEntityPO extends SearchEntityPO {

  @JsonProperty("provider")
  private final String provider;

  @JsonProperty("type")
  private final Catalog.Type type;

  private SearchCatalogEntityPO(SearchCatalogEntityPOBuilder builder) {
    super(builder);
    this.provider = builder.provider;
    this.type = builder.type;
  }

  public static class SearchCatalogEntityPOBuilder
      extends SearchEntityPO.Builder<SearchCatalogEntityPOBuilder, SearchCatalogEntityPO> {
    private String provider;
    private Catalog.Type type;

    private SearchCatalogEntityPOBuilder() {
      super();
      super.withEntityProperties(Collections.emptyList());
    }

    public SearchCatalogEntityPOBuilder withProvider(String provider) {
      this.provider = provider;
      return this;
    }

    public SearchCatalogEntityPOBuilder withType(Catalog.Type type) {
      this.type = type;
      return this;
    }

    /**
     * Discards catalog properties because they may contain credentials that must not enter the
     * search index.
     *
     * @param entityProperties catalog properties to discard
     * @return this builder
     */
    @Override
    public SearchCatalogEntityPOBuilder withEntityProperties(List<PropertyPO> entityProperties) {
      return super.withEntityProperties(Collections.emptyList());
    }

    public static SearchCatalogEntityPOBuilder builder() {
      return new SearchCatalogEntityPOBuilder();
    }

    @Override
    protected void validate() {
      super.validate();
      Preconditions.checkArgument(provider != null, "\"provider\" cannot be null");
      Preconditions.checkArgument(type != null, "\"type\" cannot be null");
    }

    @Override
    SearchCatalogEntityPO internalBuild() {
      validate();
      return new SearchCatalogEntityPO(this);
    }
  }
}
