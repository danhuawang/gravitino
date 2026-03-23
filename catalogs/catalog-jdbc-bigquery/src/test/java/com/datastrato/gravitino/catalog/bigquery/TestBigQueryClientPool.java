/*
 * Copyright 2026 Datastrato Pvt Ltd.
 * This software is licensed under the Apache License version 2.
 */

package com.datastrato.gravitino.catalog.bigquery;

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
