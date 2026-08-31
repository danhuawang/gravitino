/*
 * Copyright 2026 Datastrato Inc.
 */
package com.datastrato.gravitino.catalog.oracle;

import static com.datastrato.gravitino.catalog.oracle.OracleTablePropertiesMetadata.COMPRESSION;
import static com.datastrato.gravitino.catalog.oracle.OracleTablePropertiesMetadata.PARTITIONED;
import static com.datastrato.gravitino.catalog.oracle.OracleTablePropertiesMetadata.ROW_MOVEMENT;
import static com.datastrato.gravitino.catalog.oracle.OracleTablePropertiesMetadata.TABLESPACE;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Arrays;
import java.util.List;
import org.apache.gravitino.connector.PropertyEntry;
import org.junit.jupiter.api.Test;

public class TestOracleTablePropertiesMetadata {

  private final OracleTablePropertiesMetadata metadata = new OracleTablePropertiesMetadata();

  @Test
  public void testOracleSpecificPropertiesAreVisible() {
    // These four properties are metadata from Oracle's ALL_TABLES view. They must not be hidden,
    // otherwise EntityCombinedTable.properties() strips them from the loadTable response (see
    // issue #854).
    List<String> oracleProperties =
        Arrays.asList(TABLESPACE, PARTITIONED, ROW_MOVEMENT, COMPRESSION);
    for (String property : oracleProperties) {
      PropertyEntry<?> entry = metadata.propertyEntries().get(property);
      assertTrue(entry != null, "Property must be registered: " + property);
      assertFalse(entry.isHidden(), "Property must not be hidden: " + property);
      assertFalse(
          metadata.isHiddenProperty(property), "isHiddenProperty must be false for: " + property);
    }

    PropertyEntry<?> tablespace = metadata.propertyEntries().get(TABLESPACE);
    assertTrue(tablespace.isImmutable(), "tablespace cannot be changed after table creation");
    assertFalse(tablespace.isReserved(), "tablespace can be selected when creating a table");

    for (String property : Arrays.asList(PARTITIONED, ROW_MOVEMENT, COMPRESSION)) {
      PropertyEntry<?> entry = metadata.propertyEntries().get(property);
      assertTrue(entry.isReserved(), "Derived property must not be supplied by users: " + property);
      assertTrue(entry.isImmutable(), "Reserved property must be immutable: " + property);
    }
  }
}
