/*
 * Copyright 2026 Datastrato Pvt Ltd.
 * This software is licensed under the Apache License version 2.
 */
package com.datastrato.gravitino.dto.requests;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.google.common.base.Preconditions;
import java.util.Map;
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

  @JsonProperty("properties")
  private final Map<String, String> properties;

  /**
   * Creates a credential provider test request.
   *
   * @param path The storage path used to generate a scoped credential.
   * @param properties The properties used to initialize the credential provider.
   */
  @JsonCreator
  public CredentialProviderTestRequest(
      @JsonProperty("path") String path,
      @JsonProperty("properties") Map<String, String> properties) {
    this.path = path;
    this.properties = properties;
  }

  /** Validates the request. */
  @Override
  public void validate() throws IllegalArgumentException {
    Preconditions.checkArgument(
        StringUtils.isNotBlank(path), "\"path\" field is required and cannot be empty");
    Preconditions.checkArgument(properties != null, "\"properties\" field is required");
  }
}
