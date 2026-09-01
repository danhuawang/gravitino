/*
 * Copyright 2026 Datastrato Pvt Ltd.
 */

package com.datastrato.gravitino.scim.v2.storage.relational.converters;

import static org.junit.jupiter.api.Assertions.assertEquals;
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
}
