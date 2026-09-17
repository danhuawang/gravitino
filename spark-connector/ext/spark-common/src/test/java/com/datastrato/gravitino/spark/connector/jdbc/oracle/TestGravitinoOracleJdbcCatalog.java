/*
 * Copyright 2026 Datastrato Inc.
 */
package com.datastrato.gravitino.spark.connector.jdbc.oracle;

import static org.mockito.Mockito.mock;

import org.apache.gravitino.client.GravitinoClient;
import org.apache.gravitino.spark.connector.PropertiesConverter;
import org.apache.gravitino.spark.connector.catalog.GravitinoCatalogManager;
import org.apache.gravitino.spark.connector.jdbc.JdbcPropertiesConverter;
import org.apache.spark.sql.connector.catalog.Identifier;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

public class TestGravitinoOracleJdbcCatalog {

  @BeforeAll
  static void initCatalogManager() {
    GravitinoCatalogManager.create("test_metalake", () -> mock(GravitinoClient.class));
  }

  @AfterAll
  static void cleanupCatalogManager() {
    GravitinoCatalogManager.get().close();
  }

  @Test
  void testToPhysicalIdentifierUppercasesNamespaceAndName() {
    Identifier ident = Identifier.of(new String[] {"myschema"}, "mytable");
    Identifier physical = GravitinoOracleJdbcCatalog.toPhysicalIdentifier(ident);
    Assertions.assertArrayEquals(new String[] {"MYSCHEMA"}, physical.namespace());
    Assertions.assertEquals("MYTABLE", physical.name());
  }

  @Test
  void testToPhysicalIdentifierPreservesAlreadyUppercase() {
    Identifier ident = Identifier.of(new String[] {"MYSCHEMA"}, "MYTABLE");
    Identifier physical = GravitinoOracleJdbcCatalog.toPhysicalIdentifier(ident);
    Assertions.assertArrayEquals(new String[] {"MYSCHEMA"}, physical.namespace());
    Assertions.assertEquals("MYTABLE", physical.name());
  }

  @Test
  void testToPhysicalIdentifierHandlesMixedCase() {
    Identifier ident = Identifier.of(new String[] {"MySchema"}, "MyTable");
    Identifier physical = GravitinoOracleJdbcCatalog.toPhysicalIdentifier(ident);
    Assertions.assertArrayEquals(new String[] {"MYSCHEMA"}, physical.namespace());
    Assertions.assertEquals("MYTABLE", physical.name());
  }

  @Test
  void testToPhysicalIdentifierHandlesEmptyNamespace() {
    Identifier ident = Identifier.of(new String[] {}, "mytable");
    Identifier physical = GravitinoOracleJdbcCatalog.toPhysicalIdentifier(ident);
    Assertions.assertArrayEquals(new String[] {}, physical.namespace());
    Assertions.assertEquals("MYTABLE", physical.name());
  }

  @Test
  void testToPhysicalIdentifierPreservesCaseForQuotedName() {
    Identifier ident = Identifier.of(new String[] {"\"MySchema\""}, "\"MyTable\"");
    Identifier physical = GravitinoOracleJdbcCatalog.toPhysicalIdentifier(ident);
    Assertions.assertArrayEquals(new String[] {"MySchema"}, physical.namespace());
    Assertions.assertEquals("MyTable", physical.name());
  }

  @Test
  void testGetPropertiesConverterIsNoTablePropertiesInstance() {
    GravitinoOracleJdbcCatalog catalog = new GravitinoOracleJdbcCatalog() {};
    PropertiesConverter converter = catalog.getPropertiesConverter();
    Assertions.assertSame(JdbcPropertiesConverter.getNoTablePropertiesInstance(), converter);
  }
}
