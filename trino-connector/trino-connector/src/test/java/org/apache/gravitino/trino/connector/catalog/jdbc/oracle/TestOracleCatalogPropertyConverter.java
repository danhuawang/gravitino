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
package org.apache.gravitino.trino.connector.catalog.jdbc.oracle;

import com.google.common.collect.ImmutableMap;
import java.util.Map;
import org.apache.gravitino.Catalog;
import org.apache.gravitino.credential.Credential;
import org.apache.gravitino.trino.connector.metadata.GravitinoCatalog;
import org.apache.gravitino.trino.connector.metadata.TestGravitinoCatalog;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

public class TestOracleCatalogPropertyConverter {

  @Test
  public void testBuildOracleConnectorProperties() throws Exception {
    String name = "test_catalog";
    Map<String, String> properties =
        ImmutableMap.<String, String>builder()
            .put("jdbc-url", "jdbc:oracle:thin:@localhost:1521/FREEPDB1")
            .put("jdbc-user", "test")
            .put("jdbc-password", "test")
            .put("trino.bypass.join-pushdown.strategy", "EAGER")
            .put("unknown-key", "1")
            .put("trino.bypass.oracle.unknown-key", "1")
            .build();
    Catalog mockCatalog =
        TestGravitinoCatalog.mockCatalog(
            name, "jdbc-oracle", "test catalog", Catalog.Type.RELATIONAL, properties);
    OracleConnectorAdapter adapter = new OracleConnectorAdapter();

    Map<String, String> config =
        adapter.buildInternalConnectorConfig(
            new GravitinoCatalog("test", mockCatalog), new Credential[0]);

    // test converted properties
    Assertions.assertEquals(
        "jdbc:oracle:thin:@localhost:1521/FREEPDB1", config.get("connection-url"));
    Assertions.assertEquals("test", config.get("connection-user"));
    Assertions.assertEquals("test", config.get("connection-password"));

    // test trino.bypass passthrough properties
    Assertions.assertEquals("EAGER", config.get("join-pushdown.strategy"));

    // test unknown properties are filtered
    Assertions.assertNull(config.get("unknown-key"));
    Assertions.assertEquals("1", config.get("oracle.unknown-key"));
  }
}
