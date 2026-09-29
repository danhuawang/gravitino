/*
 * Copyright 2026 Datastrato Inc.
 */

package com.datastrato.gravitino.catalog.bigquery;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

import com.google.api.client.http.HttpTransport;
import com.google.api.client.http.javanet.NetHttpTransport;
import com.google.api.client.json.gson.GsonFactory;
import com.google.api.services.bigquery.Bigquery;
import java.lang.reflect.Field;
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

  @Test
  void testNetHttpTransportInheritsShutdown() throws Exception {
    assertEquals(
        HttpTransport.class, NetHttpTransport.class.getMethod("shutdown").getDeclaringClass());
  }

  @Test
  void testCloseClearsRealNetHttpClient() throws Exception {
    NetHttpTransport transport = new NetHttpTransport();
    Bigquery client =
        new Bigquery.Builder(transport, GsonFactory.getDefaultInstance(), null).build();
    BigQueryClientPool pool = poolWithClient(client);
    assertEquals(client, pool.getClient());
    pool.close();
    pool.close();
    Field clientField = BigQueryClientPool.class.getDeclaredField("bigQueryClient");
    clientField.setAccessible(true);
    assertNull(clientField.get(pool));
    assertThrows(IllegalStateException.class, pool::getClient);
  }

  @Test
  void testCloseWithoutClientPreventsCreation() {
    BigQueryClientPool pool =
        new BigQueryClientPool(
            Map.of("project-id", "test-project", "jdbc-password", "/path/to/key.json"));
    pool.close();
    pool.close();
    assertThrows(IllegalStateException.class, pool::getClient);
  }

  private BigQueryClientPool poolWithClient(Bigquery client) throws Exception {
    BigQueryClientPool pool =
        new BigQueryClientPool(
            Map.of("project-id", "test-project", "jdbc-password", "/path/to/key.json"));
    Field clientField = BigQueryClientPool.class.getDeclaredField("bigQueryClient");
    clientField.setAccessible(true);
    clientField.set(pool, client);
    return pool;
  }
}
