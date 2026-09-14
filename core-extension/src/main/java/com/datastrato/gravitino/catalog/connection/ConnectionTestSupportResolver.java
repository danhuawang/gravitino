/*
 * Copyright 2026 Datastrato Inc.
 */
package com.datastrato.gravitino.catalog.connection;

import java.util.Locale;
import java.util.Map;
import java.util.Set;
import javax.annotation.Nullable;

/** Central provider capability resolver for the Enterprise Catalog connection test UI. */
public class ConnectionTestSupportResolver {
  private static final String DISABLE_FILESYSTEM_OPS = "disable-filesystem-ops";
  private static final Set<String> FILESET_PROVIDERS = Set.of("fileset", "hadoop");
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
  public boolean supports(@Nullable String provider) {
    if (provider == null) {
      return false;
    }
    String normalized = provider.toLowerCase(Locale.ROOT);
    return normalized.startsWith("jdbc-") || SUPPORTED_PROVIDERS.contains(normalized);
  }

  /**
   * Returns whether a configured Catalog supports a real read-only connection probe.
   *
   * <p>Fileset Catalogs cannot run a connection probe when server-side filesystem operations are
   * disabled.
   *
   * @param provider The Catalog provider.
   * @param properties The effective Catalog properties.
   * @return {@code true} when the configured Catalog supports connection testing.
   */
  public boolean supports(@Nullable String provider, @Nullable Map<String, String> properties) {
    if (!supports(provider)) {
      return false;
    }

    String normalized = provider.toLowerCase(Locale.ROOT);
    return !FILESET_PROVIDERS.contains(normalized)
        || properties == null
        || !Boolean.parseBoolean(properties.get(DISABLE_FILESYSTEM_OPS));
  }
}
