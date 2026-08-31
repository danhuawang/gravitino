/*
 * Copyright 2024 Datastrato Inc.
 */
package com.datastrato.gravitino.catalog;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import org.apache.gravitino.NameIdentifier;
import org.apache.gravitino.Namespace;
import org.apache.gravitino.catalog.CatalogManager;
import org.apache.gravitino.connector.capability.Capability;
import org.apache.gravitino.connector.capability.CapabilityResult;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

/** Tests {@link DatastratoSchemaNormalizeDispatcher}. */
public class TestDatastratoSchemaNormalizeDispatcher {

  /** Verifies that hierarchical schema support is read from the catalog capability. */
  @Test
  public void testSupportsHierarchicalSchema() throws Exception {
    DatastratoSchemaDispatcher dispatcher = mock(DatastratoSchemaDispatcher.class);
    CatalogManager catalogManager = mock(CatalogManager.class);
    CatalogManager.CatalogWrapper catalogWrapper = mock(CatalogManager.CatalogWrapper.class);
    Capability capability = mock(Capability.class);
    Namespace schemaNamespace = Namespace.of("metalake", "catalog", "schema");

    when(catalogManager.loadCatalogAndWrap(NameIdentifier.of("metalake", "catalog")))
        .thenReturn(catalogWrapper);
    when(catalogWrapper.capabilities()).thenReturn(capability);

    DatastratoSchemaNormalizeDispatcher normalizeDispatcher =
        new DatastratoSchemaNormalizeDispatcher(dispatcher, catalogManager);

    when(capability.supportsHierarchicalSchema()).thenReturn(CapabilityResult.SUPPORTED);
    Assertions.assertTrue(normalizeDispatcher.supportsHierarchicalSchema(schemaNamespace));

    when(capability.supportsHierarchicalSchema())
        .thenReturn(CapabilityResult.unsupported("Hierarchical schemas are not supported"));
    Assertions.assertFalse(normalizeDispatcher.supportsHierarchicalSchema(schemaNamespace));
  }
}
