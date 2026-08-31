/*
 * Copyright 2024 Datastrato Inc.
 */
package com.datastrato.gravitino;

import org.apache.gravitino.connector.capability.Capability;
import org.apache.gravitino.connector.capability.CapabilityResult;

public class TestCatalogCapabilities implements Capability {
  @Override
  public CapabilityResult caseSensitiveOnName(Scope scope) {
    return CapabilityResult.unsupported("The case sensitive on name is not supported.");
  }
}
