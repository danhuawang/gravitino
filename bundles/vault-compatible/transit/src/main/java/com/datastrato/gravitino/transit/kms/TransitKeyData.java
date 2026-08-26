/*
 * Copyright 2026 Datastrato Pvt Ltd.
 */
package com.datastrato.gravitino.transit.kms;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;

@JsonIgnoreProperties(ignoreUnknown = true)
final class TransitKeyData {

  private final Boolean supportsEncryption;
  private final Boolean supportsDecryption;
  private final Boolean softDeleted;

  @JsonCreator
  TransitKeyData(
      @JsonProperty("supports_encryption") Boolean supportsEncryption,
      @JsonProperty("supports_decryption") Boolean supportsDecryption,
      @JsonProperty("soft_deleted") Boolean softDeleted) {
    this.supportsEncryption = supportsEncryption;
    this.supportsDecryption = supportsDecryption;
    this.softDeleted = softDeleted;
  }

  Boolean supportsEncryption() {
    return supportsEncryption;
  }

  Boolean supportsDecryption() {
    return supportsDecryption;
  }

  Boolean softDeleted() {
    return softDeleted;
  }
}
