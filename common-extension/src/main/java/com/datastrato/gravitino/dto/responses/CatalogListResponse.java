/*
 * Copyright 2024 Datastrato Pvt Ltd.
 * This software is licensed under the Apache License version 2.
 */
package com.datastrato.gravitino.dto.responses;

import com.datastrato.gravitino.dto.ExtendedCatalogDTO;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.google.common.base.Preconditions;
import java.util.Arrays;
import lombok.EqualsAndHashCode;
import lombok.Getter;
import lombok.ToString;
import org.apache.gravitino.dto.responses.BaseResponse;

/** Represents a response for a list of extended catalogs. */
@Getter
@ToString
@EqualsAndHashCode(callSuper = true)
public class CatalogListResponse extends BaseResponse {

  @JsonProperty("catalogs")
  private final ExtendedCatalogDTO[] catalogs;

  /**
   * Creates a new CatalogListResponse.
   *
   * @param catalogs The list of extended catalogs.
   */
  public CatalogListResponse(ExtendedCatalogDTO[] catalogs) {
    super(0);
    this.catalogs = catalogs;
  }

  /** Default constructor for Jackson deserialization. */
  public CatalogListResponse() {
    super();
    this.catalogs = null;
  }

  @Override
  public void validate() throws IllegalArgumentException {
    super.validate();

    Preconditions.checkArgument(catalogs != null, "\"catalogs\" cannot be null");
    Arrays.stream(catalogs)
        .forEach(
            catalog -> {
              Preconditions.checkArgument(catalog != null, "catalog cannot be null");
              catalog.validate();
            });
  }
}
