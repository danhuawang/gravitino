/*
 * Copyright 2026 Datastrato Inc.
 */

package com.datastrato.gravitino.scim.storage.relational.converters;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.sql.SQLException;
import org.junit.jupiter.api.Test;

class TestScimSQLExceptionConverter {

  @Test
  void testH2IoExceptionPreservesSqlMessage() {
    ScimSQLExceptionConverter converter =
        new ScimSQLExceptionConverter(ScimSQLExceptionConverter.JdbcType.H2);
    SQLException sqlException =
        new SQLException("Table \"SCIM_TOKEN_META\" not found", "42S02", 42102);

    IOException thrown =
        assertThrows(
            IOException.class, () -> converter.toScimException(sqlException, "token", "entra"));

    assertTrue(thrown.getMessage().contains("42102"));
    assertTrue(thrown.getMessage().contains("Table \"SCIM_TOKEN_META\" not found"));
    assertEquals(sqlException, thrown.getCause());
  }

  @Test
  void testValueTooLongMapsToIllegalArgument() {
    ScimSQLExceptionConverter converter =
        new ScimSQLExceptionConverter(ScimSQLExceptionConverter.JdbcType.POSTGRESQL);
    SQLException sqlException = new SQLException("value too long", "22001");

    IllegalArgumentException thrown =
        assertThrows(
            IllegalArgumentException.class,
            () -> converter.toScimException(sqlException, "user", "alice"));

    assertEquals(
        "The SCIM user has a value that exceeds the maximum length of its column.",
        thrown.getMessage());
    assertNull(thrown.getCause());
  }

  @Test
  void testMysqlAndH2ValueTooLongUseTheSameMessage() {
    assertValueTooLong(
        new ScimSQLExceptionConverter(ScimSQLExceptionConverter.JdbcType.MYSQL),
        new SQLException("Data too long for column", "22001", 1406),
        "group");
    assertValueTooLong(
        new ScimSQLExceptionConverter(ScimSQLExceptionConverter.JdbcType.H2),
        new SQLException("Value too long for column", "22001", 22001),
        "user");
  }

  private static void assertValueTooLong(
      ScimSQLExceptionConverter converter, SQLException sqlException, String resourceType) {
    IllegalArgumentException thrown =
        assertThrows(
            IllegalArgumentException.class,
            () -> converter.toScimException(sqlException, resourceType, "name"));
    assertEquals(
        "The SCIM " + resourceType + " has a value that exceeds the maximum length of its column.",
        thrown.getMessage());
    assertNull(thrown.getCause());
  }
}
