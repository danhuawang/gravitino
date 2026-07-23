/*
 * Copyright 2026 Datastrato Pvt Ltd.
 * This software is licensed under the Apache License version 2.
 */
package com.datastrato.gravitino.catalog.oracle;

import static org.apache.gravitino.connector.PropertyEntry.stringOptionalPropertyEntry;
import static org.apache.gravitino.connector.PropertyEntry.stringReservedPropertyEntry;

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
    map.put(COMMENT_KEY, stringReservedPropertyEntry(COMMENT_KEY, "The table comment", true));
    // These properties are metadata read from Oracle's ALL_TABLES view. They are not sensitive, so
    // they must stay visible (hidden=false) to be returned by loadTable. TABLESPACE can be selected
    // when a table is created but cannot be altered, while the other three values are derived by
    // Oracle and must not be supplied by users.
    map.put(
        TABLESPACE,
        stringOptionalPropertyEntry(
            TABLESPACE, "Oracle tablespace from ALL_TABLES.TABLESPACE_NAME", true, null, false));
    map.put(
        PARTITIONED,
        stringReservedPropertyEntry(
            PARTITIONED, "Oracle partition flag from ALL_TABLES.PARTITIONED", false));
    map.put(
        ROW_MOVEMENT,
        stringReservedPropertyEntry(
            ROW_MOVEMENT, "Oracle row movement status from ALL_TABLES.ROW_MOVEMENT", false));
    map.put(
        COMPRESSION,
        stringReservedPropertyEntry(
            COMPRESSION, "Oracle compression status from ALL_TABLES.COMPRESSION", false));
    return Collections.unmodifiableMap(map);
  }

  @Override
  protected Map<String, PropertyEntry<?>> specificPropertyEntries() {
    return PROPERTIES_METADATA;
  }
}
