/*
 * Copyright 2024 Datastrato Inc.
 */
package com.datastrato.gravitino.search.rest;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.google.common.base.Preconditions;
import lombok.Builder;
import lombok.EqualsAndHashCode;
import lombok.Getter;
import lombok.ToString;
import lombok.extern.jackson.Jacksonized;
import org.apache.commons.lang3.StringUtils;
import org.apache.gravitino.rest.RESTRequest;

@Getter
@EqualsAndHashCode
@ToString
@Builder
@Jacksonized
public class SynMetadataRequest implements RESTRequest {
  @JsonProperty("metadataFullName")
  private String metadataFullName;

  @JsonProperty("metadataType")
  private String metadataType;

  @JsonProperty("cascade")
  private boolean cascade;

  @Override
  public void validate() throws IllegalArgumentException {
    Preconditions.checkArgument(
        !(StringUtils.isBlank(metadataType) && StringUtils.isNotBlank(metadataFullName)),
        "Metadata type is required when metadata full name is provided");
    Preconditions.checkArgument(
        !(StringUtils.isNotBlank(metadataType) && StringUtils.isBlank(metadataFullName)),
        "Metadata full name is required when metadata type is provided");
  }
}
