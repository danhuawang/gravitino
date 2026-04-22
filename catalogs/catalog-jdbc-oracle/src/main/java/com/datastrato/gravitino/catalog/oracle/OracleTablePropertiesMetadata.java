/*
 * Copyright 2026 Datastrato Pvt Ltd.
 * This software is licensed under the Apache License version 2.
 */
package com.datastrato.gravitino.catalog.oracle;

import static org.apache.gravitino.connector.PropertyEntry.stringOptionalPropertyEntry;

import java.util.Collections;
import java.util.HashMap;
import java.util.Map;
import org.apache.gravitino.catalog.jdbc.JdbcTablePropertiesMetadata;
import org.apache.gravitino.connector.PropertyEntry;

public class OracleTablePropertiesMetadata extends JdbcTablePropertiesMetadata {

  public static final String TABLESPACE = "tablespace";
  public static final String PARTITIONED = "partitioned";
  public static final String ROW_MOVEMENT = "row_movement";
  public static final String COMPRESSION = "compression";

  private static final Map<String, PropertyEntry<?>> PROPERTIES_METADATA =
      createPropertiesMetadata();

  private static Map<String, PropertyEntry<?>> createPropertiesMetadata() {
    Map<String, PropertyEntry<?>> map = new HashMap<>();
    map.put(
        TABLESPACE,
        stringOptionalPropertyEntry(
            TABLESPACE, "Oracle tablespace from ALL_TABLES.TABLESPACE_NAME", false, null, true));
    map.put(
        PARTITIONED,
        stringOptionalPropertyEntry(
            PARTITIONED, "Oracle partition flag from ALL_TABLES.PARTITIONED", false, null, true));
    map.put(
        ROW_MOVEMENT,
        stringOptionalPropertyEntry(
            ROW_MOVEMENT,
            "Oracle row movement status from ALL_TABLES.ROW_MOVEMENT",
            false,
            null,
            true));
    map.put(
        COMPRESSION,
        stringOptionalPropertyEntry(
            COMPRESSION,
            "Oracle compression status from ALL_TABLES.COMPRESSION",
            false,
            null,
            true));
    return Collections.unmodifiableMap(map);
  }

  @Override
  protected Map<String, PropertyEntry<?>> specificPropertyEntries() {
    return PROPERTIES_METADATA;
  }
}
