/*
 * Copyright 2026 Datastrato Pvt Ltd.
 */
package com.datastrato.gravitino.dto.requests;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.google.common.base.Preconditions;
import lombok.EqualsAndHashCode;
import lombok.Getter;
import org.apache.commons.lang3.StringUtils;
import org.apache.gravitino.rest.RESTRequest;

/** Represents a request to test a credential provider. */
@Getter
@EqualsAndHashCode
public class CredentialProviderTestRequest implements RESTRequest {

  @JsonProperty("path")
  private final String path;

  /**
   * Creates a credential provider test request.
   *
   * @param path The storage path used to generate a scoped credential.
   */
  @JsonCreator
  public CredentialProviderTestRequest(@JsonProperty("path") String path) {
    this.path = path;
  }

  /** Validates the request. */
  @Override
  public void validate() throws IllegalArgumentException {
    Preconditions.checkArgument(
        StringUtils.isNotBlank(path), "\"path\" field is required and cannot be empty");
  }
}
