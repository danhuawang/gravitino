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
package org.apache.gravitino.catalog.bigquery;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.util.HashMap;
import java.util.Map;
import org.junit.jupiter.api.Test;

/** Unit tests for BigQueryClientPool. */
public class TestBigQueryClientPool {

  @Test
  void testConstructorWithValidConfig() {
    Map<String, String> config = new HashMap<>();
    config.put("project-id", "test-project");
    config.put("jdbc-password", "/path/to/key.json");

    BigQueryClientPool pool = new BigQueryClientPool(config);
    assertNotNull(pool);
    assertEquals("test-project", pool.getProjectId());
  }

  @Test
  void testConstructorWithMissingProjectId() {
    Map<String, String> config = new HashMap<>();
    config.put("jdbc-password", "/path/to/key.json");

    assertThrows(IllegalArgumentException.class, () -> new BigQueryClientPool(config));
  }

  @Test
  void testConstructorWithMissingKeyFile() {
    Map<String, String> config = new HashMap<>();
    config.put("project-id", "test-project");

    assertThrows(IllegalArgumentException.class, () -> new BigQueryClientPool(config));
  }

  @Test
  void testConstructorWithEmptyProjectId() {
    Map<String, String> config = new HashMap<>();
    config.put("project-id", "");
    config.put("jdbc-password", "/path/to/key.json");

    assertThrows(IllegalArgumentException.class, () -> new BigQueryClientPool(config));
  }

  @Test
  void testConstructorWithEmptyKeyFile() {
    Map<String, String> config = new HashMap<>();
    config.put("project-id", "test-project");
    config.put("jdbc-password", "");

    assertThrows(IllegalArgumentException.class, () -> new BigQueryClientPool(config));
  }

  @Test
  void testClose() {
    Map<String, String> config = new HashMap<>();
    config.put("project-id", "test-project");
    config.put("jdbc-password", "/path/to/key.json");

    BigQueryClientPool pool = new BigQueryClientPool(config);
    // Should not throw exception
    pool.close();
  }
}
