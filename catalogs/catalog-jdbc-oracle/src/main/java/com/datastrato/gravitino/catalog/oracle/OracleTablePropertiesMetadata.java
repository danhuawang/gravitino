/*
 * Copyright 2026 Datastrato Pvt Ltd.
 * This software is licensed under the Apache License version 2.
 */
package com.datastrato.gravitino.catalog.oracle;

import java.util.Collections;
import java.util.HashMap;
import java.util.Map;
import org.apache.gravitino.catalog.jdbc.JdbcTablePropertiesMetadata;
import org.apache.gravitino.connector.PropertyEntry;

public class OracleTablePropertiesMetadata extends JdbcTablePropertiesMetadata {

  private static final Map<String, PropertyEntry<?>> PROPERTIES_METADATA =
      createPropertiesMetadata();

  private static Map<String, PropertyEntry<?>> createPropertiesMetadata() {
    Map<String, PropertyEntry<?>> map = new HashMap<>();
    return Collections.unmodifiableMap(map);
  }

  @Override
  protected Map<String, PropertyEntry<?>> specificPropertyEntries() {
    return PROPERTIES_METADATA;
  }
}
