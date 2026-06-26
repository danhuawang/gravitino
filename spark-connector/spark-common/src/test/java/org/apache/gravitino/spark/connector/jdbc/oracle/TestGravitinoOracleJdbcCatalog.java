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
  void testUpperCaseConvertsLowercase() {
    String[] result = GravitinoOracleJdbcCatalog.upperCase(new String[] {"gravitino"});
    Assertions.assertArrayEquals(new String[] {"GRAVITINO"}, result);
  }

  @Test
  void testUpperCasePreservesAlreadyUppercase() {
    String[] result = GravitinoOracleJdbcCatalog.upperCase(new String[] {"GRAVITINO"});
    Assertions.assertArrayEquals(new String[] {"GRAVITINO"}, result);
  }

  @Test
  void testUpperCaseHandlesMixedCase() {
    String[] result = GravitinoOracleJdbcCatalog.upperCase(new String[] {"MySchema"});
    Assertions.assertArrayEquals(new String[] {"MYSCHEMA"}, result);
  }

  @Test
  void testUpperCaseHandlesEmptyNamespace() {
    String[] result = GravitinoOracleJdbcCatalog.upperCase(new String[] {});
    Assertions.assertArrayEquals(new String[] {}, result);
  }

  @Test
  void testGetPropertiesConverterIsNoTablePropertiesInstance() {
    GravitinoOracleJdbcCatalog catalog = new GravitinoOracleJdbcCatalog() {};
    PropertiesConverter converter = catalog.getPropertiesConverter();
    Assertions.assertSame(JdbcPropertiesConverter.getNoTablePropertiesInstance(), converter);
  }
}
