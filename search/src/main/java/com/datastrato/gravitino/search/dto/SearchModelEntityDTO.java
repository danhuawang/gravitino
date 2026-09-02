/*
 * Copyright 2024 Datastrato Inc.
 */

package com.datastrato.gravitino.search.dto;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.databind.annotation.JsonDeserialize;
import com.google.common.base.Preconditions;
import com.google.common.collect.ImmutableList;
import java.util.List;
import lombok.EqualsAndHashCode;

@EqualsAndHashCode(callSuper = true)
@JsonDeserialize(builder = SearchModelEntityDTO.SearchModelEntityDTOBuilder.class)
public class SearchModelEntityDTO extends SearchEntityDTO {

  @JsonProperty("latest_version")
  private final int latestVersion;

  @JsonProperty("model_versions")
  private final List<SearchModelVersionDTO> modelVersions;

  private SearchModelEntityDTO(SearchModelEntityDTOBuilder builder) {
    super(builder);
    this.latestVersion = builder.latestVersion;
    this.modelVersions = builder.modelVersions;
  }

  public static class SearchModelEntityDTOBuilder
      extends Builder<SearchModelEntityDTOBuilder, SearchModelEntityDTO> {
    Integer latestVersion;
    List<SearchModelVersionDTO> modelVersions;

    private SearchModelEntityDTOBuilder() {
      super();
    }

    public SearchModelEntityDTOBuilder withModelVersions(
        List<SearchModelVersionDTO> modelVersions) {
      this.modelVersions = modelVersions;
      return this;
    }

    public SearchModelEntityDTOBuilder withLatestVersion(int latestVersion) {
      this.latestVersion = latestVersion;
      return this;
    }

    public static SearchModelEntityDTOBuilder builder() {
      return new SearchModelEntityDTOBuilder();
    }

    @Override
    protected void validate() {
      super.validate();
    }

    @Override
    protected SearchModelEntityDTO internalBuild() {
      validate();

      return new SearchModelEntityDTO(this);
    }
  }

  @JsonDeserialize(builder = SearchModelVersionDTO.Builder.class)
  @SuppressWarnings("unused") // Fields are accessed by Jackson via reflection
  public static class SearchModelVersionDTO {
    @JsonProperty("version")
    private final int version;

    @JsonProperty("uri")
    private final List<String> uri;

    @JsonProperty("aliases")
    private final List<String> aliases;

    private SearchModelVersionDTO(Builder builder) {
      this.version = builder.version;
      this.uri = builder.uri;
      this.aliases = builder.aliases;
    }

    public static class Builder {
      private int version;
      private List<String> uri;
      private List<String> aliases = ImmutableList.of();

      public Builder withUri(List<String> uri) {
        this.uri = uri;
        return this;
      }

      public Builder withVersion(int version) {
        this.version = version;
        return this;
      }

      public Builder withAliases(List<String> aliases) {
        this.aliases = aliases;
        return this;
      }

      public SearchModelVersionDTO build() {
        Preconditions.checkArgument(
            uri != null && !uri.isEmpty(), "\"uri\" cannot be null or empty");
        Preconditions.checkArgument(aliases != null, "\"aliases\" cannot be null");
        return new SearchModelVersionDTO(this);
      }
    }
  }
}
