/*
 * Copyright 2026 Datastrato Inc.
 */
package com.datastrato.gravitino.catalog.oracle;

import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotNull;

import org.apache.gravitino.connector.capability.Capability;
import org.junit.jupiter.api.Test;

public class TestOracleCatalog {

  /**
   * newCapability() must not initialize the operations layer or open a JDBC connection. {@code
   * CatalogManager} preloads {@code capability()} when a catalog is created or loaded, so if
   * newCapability() reached into {@code ops()} it would build the connection pool eagerly and break
   * BaseCatalog's lazy-init contract (#2195). A freshly constructed catalog has no entity or conf
   * set, so {@code ops()} would throw; newCapability() must still succeed by returning the default
   * capability without touching ops.
   */
  @Test
  void testNewCapabilityDoesNotInitializeOpsOrOpenConnection() {
    OracleCatalog catalog = new OracleCatalog();

    Capability capability = catalog.newCapability();

    assertNotNull(capability);
    assertInstanceOf(OracleCatalogCapability.class, capability);
  }
}
