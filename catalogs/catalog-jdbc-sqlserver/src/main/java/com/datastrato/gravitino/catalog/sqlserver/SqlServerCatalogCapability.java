/*
 * Copyright 2026 Datastrato Pvt Ltd.
 * This software is licensed under the Apache License version 2.
 */
package com.datastrato.gravitino.catalog.sqlserver;

import org.apache.gravitino.connector.capability.Capability;
import org.apache.gravitino.connector.capability.CapabilityResult;

/** Capability definition for SQL Server catalog. */
public class SqlServerCatalogCapability implements Capability {

  @Override
  public CapabilityResult specificationOnName(Scope scope, String name) {
    // TODO: Implement name validation in PR2
    throw new UnsupportedOperationException("Not implemented yet");
  }

  @Override
  public CapabilityResult caseSensitiveOnName(Scope scope) {
    // TODO: Implement case sensitivity check in PR2
    throw new UnsupportedOperationException("Not implemented yet");
  }
}
