/*
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *
 *  http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing,
 * software distributed under the License is distributed on an
 * "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
 * KIND, either express or implied.  See the License for the
 * specific language governing permissions and limitations
 * under the License.
 */
package org.apache.gravitino.spark.connector.jdbc.oracle;

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
    GravitinoCatalogManager.create(() -> mock(GravitinoClient.class));
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
