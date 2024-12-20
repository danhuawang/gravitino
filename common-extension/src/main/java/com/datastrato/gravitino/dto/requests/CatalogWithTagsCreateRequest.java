/*
 * Copyright 2024 Datastrato Pvt Ltd.
 * This software is licensed under the Apache License version 2.
 */
package com.datastrato.gravitino.dto.requests;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.google.common.base.Preconditions;
import java.util.Map;
import javax.annotation.Nullable;
import lombok.EqualsAndHashCode;
import lombok.Getter;
import lombok.ToString;
import org.apache.commons.lang3.StringUtils;
import org.apache.gravitino.Catalog;
import org.apache.gravitino.dto.requests.CatalogCreateRequest;

@Getter
@EqualsAndHashCode(callSuper = false)
@ToString
public class CatalogWithTagsCreateRequest extends CatalogCreateRequest {

  @Nullable
  @JsonProperty("tagsToAdd")
  private final String[] tagsToAdd;

  /**
   * Creates a new CatalogWithTagCreateRequest.
   *
   * @param name The name of the catalog.
   * @param type The type of the catalog.
   * @param provider The provider of the catalog.
   * @param comment The comment for the catalog.
   * @param properties The properties for the catalog.
   * @param tagsToAdd The tags to add.
   */
  public CatalogWithTagsCreateRequest(
      String name,
      Catalog.Type type,
      String provider,
      String comment,
      Map<String, String> properties,
      String[] tagsToAdd) {
    super(name, type, provider, comment, properties);
    this.tagsToAdd = tagsToAdd;
  }

  /** This is the constructor that is used by Jackson deserializer */
  public CatalogWithTagsCreateRequest() {
    this(null, null, null, null, null, null);
  }

  /**
   * Validates the request.
   *
   * @throws IllegalArgumentException If the request is invalid, this exception is thrown.
   */
  @Override
  public void validate() throws IllegalArgumentException {
    super.validate();

    if (tagsToAdd != null) {
      for (String tag : tagsToAdd) {
        Preconditions.checkArgument(
            StringUtils.isNotBlank(tag), "tagsToAdd must not contain null or empty tag names");
      }
    }
  }
}
