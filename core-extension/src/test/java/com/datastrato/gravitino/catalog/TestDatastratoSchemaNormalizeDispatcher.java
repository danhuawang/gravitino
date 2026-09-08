/*
 * Copyright 2024 Datastrato Inc.
 */
package com.datastrato.gravitino.catalog;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import org.apache.gravitino.NameIdentifier;
import org.apache.gravitino.Namespace;
import org.apache.gravitino.catalog.CatalogManager;
import org.apache.gravitino.connector.BaseCatalog;
import org.apache.gravitino.connector.capability.Capability;
import org.apache.gravitino.connector.capability.CapabilityResult;
import org.apache.gravitino.utils.ThrowableFunction;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

/** Tests {@link DatastratoSchemaNormalizeDispatcher}. */
public class TestDatastratoSchemaNormalizeDispatcher {

  /** Verifies that hierarchical schema support is read from the catalog capability. */
  @Test
  public void testSupportsHierarchicalSchema() throws Exception {
    DatastratoSchemaDispatcher dispatcher = mock(DatastratoSchemaDispatcher.class);
    CatalogManager catalogManager = mock(CatalogManager.class);
    BaseCatalog<?> mockCatalog = mock(BaseCatalog.class);
    Capability capability = mock(Capability.class);
    Namespace schemaNamespace = Namespace.of("metalake", "catalog", "schema");

    when(mockCatalog.capability()).thenReturn(capability);
    doAnswer(
            invocation -> {
              ThrowableFunction<BaseCatalog, Object> operation = invocation.getArgument(1);
              return operation.apply(mockCatalog);
            })
        .when(catalogManager)
        .doWithCatalog(eq(NameIdentifier.of("metalake", "catalog")), any());

    DatastratoSchemaNormalizeDispatcher normalizeDispatcher =
        new DatastratoSchemaNormalizeDispatcher(dispatcher, catalogManager);

    when(capability.supportsHierarchicalSchema()).thenReturn(CapabilityResult.SUPPORTED);
    Assertions.assertTrue(normalizeDispatcher.supportsHierarchicalSchema(schemaNamespace));

    when(capability.supportsHierarchicalSchema())
        .thenReturn(CapabilityResult.unsupported("Hierarchical schemas are not supported"));
    Assertions.assertFalse(normalizeDispatcher.supportsHierarchicalSchema(schemaNamespace));
  }
}
