/*
 * Copyright 2026 Datastrato Inc.
 */
package com.datastrato.gravitino.catalog.connection;

import java.util.Locale;
import java.util.Set;

/** Central provider capability resolver for the Enterprise Catalog connection test UI. */
public class ConnectionTestSupportResolver {
  private static final Set<String> SUPPORTED_PROVIDERS =
      Set.of(
          "hive",
          "glue",
          "kafka",
          "lakehouse-iceberg",
          "lakehouse-paimon",
          "lakehouse-hudi",
          "fileset",
          "hadoop");

  /**
   * Returns whether a Catalog provider implements a real read-only connection probe.
   *
   * @param provider The Catalog provider.
   * @return {@code true} when existing-Catalog connection testing is supported.
   */
  public boolean supports(String provider) {
    if (provider == null) {
      return false;
    }
    String normalized = provider.toLowerCase(Locale.ROOT);
    return normalized.startsWith("jdbc-") || SUPPORTED_PROVIDERS.contains(normalized);
  }
}
