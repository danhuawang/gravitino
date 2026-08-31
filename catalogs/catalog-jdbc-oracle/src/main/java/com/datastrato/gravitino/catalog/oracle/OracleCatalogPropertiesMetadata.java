/*
 * Copyright 2026 Datastrato Inc.
 */
package com.datastrato.gravitino.catalog.oracle;

import com.google.common.collect.ImmutableMap;
import java.util.Map;
import org.apache.gravitino.catalog.jdbc.JdbcCatalogPropertiesMetadata;
import org.apache.gravitino.connector.PropertyEntry;

public class OracleCatalogPropertiesMetadata extends JdbcCatalogPropertiesMetadata {

  private static final Map<String, PropertyEntry<?>> PROPERTIES_METADATA = ImmutableMap.of();

  @Override
  protected Map<String, PropertyEntry<?>> specificPropertyEntries() {
    Map<String, PropertyEntry<?>> superMap = super.specificPropertyEntries();
    return new ImmutableMap.Builder<String, PropertyEntry<?>>()
        .putAll(superMap)
        .putAll(PROPERTIES_METADATA)
        .build();
  }
}
