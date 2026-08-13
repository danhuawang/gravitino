/*
 * Copyright 2026 Datastrato Pvt Ltd.
 * This software is licensed under the Apache License version 2.
 */
package com.datastrato.gravitino.transit.kms;

import org.apache.gravitino.encryption.kms.KmsKeyProperties;
import org.apache.gravitino.encryption.kms.KmsReference;

/** Normalized key properties returned by Transit-compatible providers. */
final class TransitKmsKeyProperties implements KmsKeyProperties {

  private final KmsReference reference;
  private final boolean supportsWrapping;
  private final boolean supportsUnwrapping;

  TransitKmsKeyProperties(
      KmsReference reference, boolean supportsWrapping, boolean supportsUnwrapping) {
    this.reference = reference;
    this.supportsWrapping = supportsWrapping;
    this.supportsUnwrapping = supportsUnwrapping;
  }

  @Override
  public KmsReference reference() {
    return reference;
  }

  @Override
  public boolean enabled() {
    return true;
  }

  @Override
  public boolean supportsWrapping() {
    return supportsWrapping;
  }

  @Override
  public boolean supportsUnwrapping() {
    return supportsUnwrapping;
  }
}
