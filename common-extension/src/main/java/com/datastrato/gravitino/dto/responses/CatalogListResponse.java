/*
 * Copyright 2024 Datastrato Pvt Ltd.
 * This software is licensed under the Apache License version 2.
 */
package com.datastrato.gravitino.dto.responses;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.google.common.base.Preconditions;
import com.google.common.collect.ImmutableMap;
import java.util.Arrays;
import java.util.Map;
import lombok.EqualsAndHashCode;
import lombok.Getter;
import lombok.ToString;
import org.apache.commons.lang3.StringUtils;
import org.apache.gravitino.dto.CatalogDTO;
import org.apache.gravitino.dto.responses.BaseResponse;

/** Represents a response for catalogs and their visible direct child counts. */
@Getter
@ToString
@EqualsAndHashCode(callSuper = true)
public class CatalogListResponse extends BaseResponse {

  @JsonProperty("catalogs")
  private final CatalogDTO[] catalogs;

  @JsonProperty("directChildCounts")
  private final Map<String, Long> directChildCounts;

  /**
   * Creates a new CatalogListResponse.
   *
   * @param catalogs The list of catalogs.
   * @param directChildCounts The visible direct schema count keyed by catalog name.
   */
  public CatalogListResponse(CatalogDTO[] catalogs, Map<String, Long> directChildCounts) {
    super(0);
    this.catalogs = catalogs;
    this.directChildCounts = ImmutableMap.copyOf(directChildCounts);
  }

  /**
   * Creates a new CatalogListResponse without direct child counts.
   *
   * @param catalogs The list of catalogs.
   */
  public CatalogListResponse(CatalogDTO[] catalogs) {
    this(catalogs, ImmutableMap.of());
  }

  /** Default constructor for Jackson deserialization. */
  public CatalogListResponse() {
    super();
    this.catalogs = null;
    this.directChildCounts = null;
  }

  @Override
  public void validate() throws IllegalArgumentException {
    super.validate();

    Preconditions.checkArgument(catalogs != null, "\"catalogs\" cannot be null");
    Arrays.stream(catalogs)
        .forEach(
            catalog -> {
              Preconditions.checkArgument(catalog != null, "catalog cannot be null");
              Preconditions.checkArgument(
                  StringUtils.isNotBlank(catalog.name()), "catalog name cannot be blank");
            });
    validateDirectChildCounts();
  }

  private void validateDirectChildCounts() {
    Preconditions.checkArgument(directChildCounts != null, "\"directChildCounts\" cannot be null");
    directChildCounts.forEach(
        (name, count) -> {
          Preconditions.checkArgument(
              StringUtils.isNotBlank(name), "direct child count name cannot be blank");
          Preconditions.checkArgument(count != null, "direct child count cannot be null");
          Preconditions.checkArgument(count >= 0, "direct child count cannot be negative");
        });
  }
}
