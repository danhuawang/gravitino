/*
 * Copyright 2026 Datastrato Inc.
 */
package org.apache.gravitino.connector;

/**
 * Implemented by {@link CatalogOperations} whose backing store can publish an Iceberg metadata
 * pointer and its integrity hash in one atomic commit.
 *
 * <p>This is the only core-side type that crosses the isolated-ClassLoader boundary for the
 * capability, so core code never needs an {@code instanceof} probe against a connector class. An
 * implementation must declare this interface <b>directly</b> on the operations class: {@code
 * OperationsProxy} copies only {@code getClass().getInterfaces()}, so an inherited declaration
 * would be dropped behind a proxy.
 */
public interface SupportsAtomicIntegrity {

  /**
   * Returns whether the initialized catalog commits the metadata pointer and the integrity hash
   * atomically, computed from the live connection and never inferred from configuration.
   *
   * @return {@code true} when atomic metadata integrity is available
   */
  boolean atomicIntegrityAvailable();
}
