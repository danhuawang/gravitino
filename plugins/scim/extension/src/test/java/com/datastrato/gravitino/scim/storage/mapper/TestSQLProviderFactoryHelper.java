/*
 * Copyright 2026 Datastrato Pvt Ltd.
 * This software is licensed under the Apache License version 2.
 */

package com.datastrato.gravitino.scim.storage.mapper;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import com.google.common.collect.ImmutableMap;
import java.util.Map;
import org.apache.gravitino.storage.relational.JDBCBackend.JDBCBackendType;
import org.junit.jupiter.api.Test;

public class TestSQLProviderFactoryHelper {
  private static final Map<JDBCBackendType, String> PROVIDER_MAP =
      ImmutableMap.of(
          JDBCBackendType.MYSQL, "mysql",
          JDBCBackendType.H2, "h2",
          JDBCBackendType.POSTGRESQL, "postgresql");

  @Test
  void testGetProvider() {
    assertEquals("mysql", provider("mysql"));
    assertEquals("h2", provider("h2"));
    assertEquals("postgresql", provider("postgresql"));
  }

  @Test
  void testGetProviderWithNullDatabaseId() {
    IllegalStateException exception =
        assertThrows(IllegalStateException.class, () -> provider(null));
    assertEquals(
        "MyBatis databaseId is not configured for TestSQLProviderFactoryHelper.",
        exception.getMessage());
  }

  @Test
  void testGetProviderWithUnsupportedDatabaseId() {
    IllegalStateException exception =
        assertThrows(IllegalStateException.class, () -> provider("sqlite"));
    assertEquals(
        "Unsupported TestSQLProviderFactoryHelper databaseId: sqlite, supported backends: [MYSQL,"
            + " H2,"
            + " POSTGRESQL]",
        exception.getMessage());
  }

  private String provider(String databaseId) {
    return SQLProviderFactoryHelper.getProvider(
        databaseId, PROVIDER_MAP, TestSQLProviderFactoryHelper.class);
  }
}
