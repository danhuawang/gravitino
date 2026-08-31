/*
 * Copyright 2026 Datastrato Inc.
 */
package com.datastrato.gravitino.catalog.connection;

import com.google.common.collect.ImmutableMap;
import java.util.Map;

/** Immutable relational snapshot used to bind a test result to one Catalog configuration. */
public final class CatalogConnectionSnapshot {
  private final long catalogId;
  private final long catalogVersion;
  private final String catalogName;
  private final String provider;
  private final Map<String, String> properties;

  /**
   * Creates a Catalog connection snapshot.
   *
   * @param catalogId The Catalog ID.
   * @param catalogVersion The current Catalog version.
   * @param catalogName The current Catalog name.
   * @param provider The Catalog provider.
   * @param properties The persisted Catalog properties.
   */
  public CatalogConnectionSnapshot(
      long catalogId,
      long catalogVersion,
      String catalogName,
      String provider,
      Map<String, String> properties) {
    this.catalogId = catalogId;
    this.catalogVersion = catalogVersion;
    this.catalogName = catalogName;
    this.provider = provider;
    this.properties = properties == null ? ImmutableMap.of() : ImmutableMap.copyOf(properties);
  }

  /**
   * @return The Catalog ID.
   */
  public long catalogId() {
    return catalogId;
  }

  /**
   * @return The current Catalog version.
   */
  public long catalogVersion() {
    return catalogVersion;
  }

  /**
   * @return The current Catalog name.
   */
  public String catalogName() {
    return catalogName;
  }

  /**
   * @return The Catalog provider.
   */
  public String provider() {
    return provider;
  }

  /**
   * @return An immutable copy of the persisted Catalog properties.
   */
  public Map<String, String> properties() {
    return properties;
  }
}
