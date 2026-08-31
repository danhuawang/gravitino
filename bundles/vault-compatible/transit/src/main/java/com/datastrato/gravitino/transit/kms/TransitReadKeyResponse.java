/*
 * Copyright 2026 Datastrato Inc.
 */
package com.datastrato.gravitino.transit.kms;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;

@JsonIgnoreProperties(ignoreUnknown = true)
final class TransitReadKeyResponse {

  private final TransitKeyData data;

  @JsonCreator
  TransitReadKeyResponse(@JsonProperty("data") TransitKeyData data) {
    this.data = data;
  }

  TransitKeyData data() {
    return data;
  }
}
