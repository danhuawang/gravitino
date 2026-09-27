/*
 * Copyright 2026 Datastrato Inc.
 */
package com.datastrato.gravitino;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.function.Function;
import org.apache.gravitino.NameIdentifier;
import org.apache.gravitino.catalog.CatalogManager;
import org.apache.gravitino.connector.BaseCatalog;
import org.apache.gravitino.connector.CatalogOperations;
import org.apache.gravitino.connector.SupportsAtomicIntegrity;
import org.apache.gravitino.encryption.AtomicIntegrityCapability;
import org.apache.gravitino.utils.ThrowableFunction;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

/**
 * Pins the core-side capability reader: the answer comes from the loaded catalog's own operations,
 * through the leased entry point, and a connector that cannot answer is unsupported rather than an
 * error.
 */
public class TestDatastratoGravitinoEnvAtomicIntegrityCapability {

  private static final NameIdentifier TABLE =
      NameIdentifier.of("metalake", "catalog", "schema", "orders");
  private static final NameIdentifier CATALOG = NameIdentifier.of("metalake", "catalog");

  @Test
  void testCapabilityIsTheLoadedCatalogsAnswerReadThroughTheLease() {
    for (boolean available : new boolean[] {true, false}) {
      AtomicIntegrityOperations ops = mock(AtomicIntegrityOperations.class);
      when(ops.atomicIntegrityAvailable()).thenReturn(available);
      CatalogManager catalogManager = catalogManagerReturning(ops);

      AtomicIntegrityCapability capability =
          DatastratoGravitinoEnv.atomicIntegrityCapability(catalogManager).apply(TABLE);

      Assertions.assertEquals(AtomicIntegrityCapability.of(available), capability);
      // The lease is what keeps the operations object alive while it is asked.
      verify(catalogManager).doWithCatalog(eq(CATALOG), any());
      verify(ops).atomicIntegrityAvailable();
    }
  }

  @Test
  void testConnectorWithoutTheCapabilityInterfaceIsUnsupported() {
    CatalogOperations ops = mock(CatalogOperations.class);
    CatalogManager catalogManager = catalogManagerReturning(ops);

    AtomicIntegrityCapability capability =
        DatastratoGravitinoEnv.atomicIntegrityCapability(catalogManager).apply(TABLE);

    Assertions.assertEquals(AtomicIntegrityCapability.UNSUPPORTED, capability);
    verify(catalogManager).doWithCatalog(eq(CATALOG), any());
  }

  @Test
  void testCapabilityIsResolvedPerRequestAndNeverCached() {
    AtomicIntegrityOperations ops = mock(AtomicIntegrityOperations.class);
    when(ops.atomicIntegrityAvailable()).thenReturn(true, false);
    CatalogManager catalogManager = catalogManagerReturning(ops);
    Function<NameIdentifier, AtomicIntegrityCapability> capability =
        DatastratoGravitinoEnv.atomicIntegrityCapability(catalogManager);

    Assertions.assertEquals(AtomicIntegrityCapability.SUPPORTED, capability.apply(TABLE));
    Assertions.assertEquals(AtomicIntegrityCapability.UNSUPPORTED, capability.apply(TABLE));
  }

  @SuppressWarnings("unchecked")
  private static CatalogManager catalogManagerReturning(CatalogOperations ops) {
    BaseCatalog<?> catalog = mock(BaseCatalog.class);
    when(catalog.ops()).thenReturn(ops);
    CatalogManager catalogManager = mock(CatalogManager.class);
    try {
      when(catalogManager.doWithCatalog(any(NameIdentifier.class), any()))
          .thenAnswer(
              invocation ->
                  ((ThrowableFunction<BaseCatalog, Object>) invocation.getArgument(1))
                      .apply(catalog));
    } catch (Exception e) {
      throw new IllegalStateException(e);
    }
    return catalogManager;
  }

  /** A connector whose operations answer the capability, as the Iceberg catalog's do. */
  private interface AtomicIntegrityOperations extends CatalogOperations, SupportsAtomicIntegrity {}
}
