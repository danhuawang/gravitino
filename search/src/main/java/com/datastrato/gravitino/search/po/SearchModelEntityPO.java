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

@EqualsAndHashCode(callSuper = true)
@JsonDeserialize(builder = SearchModelEntityPO.SearchModelEntityPOBuilder.class)
public class SearchModelEntityPO extends SearchEntityPO {

  @JsonProperty("latest_version")
  private final int latestVersion;

  @JsonProperty("model_versions")
  private final List<SearchModelVersionPO> modelVersions;

  private SearchModelEntityPO(SearchModelEntityPOBuilder builder) {
    super(builder);
    this.latestVersion = builder.latestVersion;
    this.modelVersions = builder.modelVersions;
  }

  public static class SearchModelEntityPOBuilder
      extends SearchEntityPO.Builder<
          SearchModelEntityPO.SearchModelEntityPOBuilder, SearchModelEntityPO> {
    Integer latestVersion;
    List<SearchModelVersionPO> modelVersions;

    private SearchModelEntityPOBuilder() {
      super();
    }

    public SearchModelEntityPO.SearchModelEntityPOBuilder withModelVersions(
        List<SearchModelVersionPO> modelVersions) {
      this.modelVersions = modelVersions;
      return this;
    }

    public SearchModelEntityPO.SearchModelEntityPOBuilder withLatestVersion(int latestVersion) {
      this.latestVersion = latestVersion;
      return this;
    }

    public static SearchModelEntityPO.SearchModelEntityPOBuilder builder() {
      return new SearchModelEntityPO.SearchModelEntityPOBuilder();
    }

    @Override
    protected void validate() {
      super.validate();
    }

    @Override
    SearchModelEntityPO internalBuild() {
      validate();

      return new SearchModelEntityPO(this);
    }
  }

  @SuppressWarnings("unused")
  @JsonDeserialize(builder = SearchModelVersionPO.Builder.class)
  public static class SearchModelVersionPO {
    @JsonProperty("version")
    private final int version;

    @JsonProperty("uri")
    private final List<String> uri;

    @JsonProperty("aliases")
    private final List<String> aliases;

    private SearchModelVersionPO(Builder builder) {
      this.version = builder.version;
      this.uri = builder.uri;
      this.aliases = builder.aliases;
    }

    public static class Builder {
      private int version;
      private List<String> uri;
      private List<String> aliases;

      public SearchModelVersionPO.Builder withUri(List<String> uri) {
        this.uri = uri;
        return this;
      }

      public SearchModelVersionPO.Builder withVersion(int version) {
        this.version = version;
        return this;
      }

      public SearchModelVersionPO.Builder withAliases(List<String> aliases) {
        this.aliases = aliases;
        return this;
      }

      public SearchModelVersionPO build() {
        Preconditions.checkArgument(
            uri != null && !uri.isEmpty(), "\"uri\" cannot be null or empty");
        Preconditions.checkArgument(
            aliases != null && !aliases.isEmpty(), "\"aliases\" cannot be null or empty");
        return new SearchModelVersionPO(this);
      }
    }
  }
}
