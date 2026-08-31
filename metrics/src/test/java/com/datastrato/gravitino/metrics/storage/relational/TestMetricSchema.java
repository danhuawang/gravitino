/*
 * Copyright 2024 Datastrato Pvt Ltd.
 */
package com.datastrato.gravitino.metrics.storage.relational;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.Statement;
import java.util.Arrays;
import java.util.List;
import org.junit.jupiter.api.Test;

class TestMetricSchema {

  @Test
  void testH2MetricSchema() throws Exception {
    String gravitinoHome = System.getenv("GRAVITINO_HOME");
    Path h2Scripts = Path.of(gravitinoHome, "scripts", "h2");
    Class.forName("org.h2.Driver");

    try (Connection connection =
            DriverManager.getConnection(
                "jdbc:h2:mem:metric_schema;DB_CLOSE_DELAY=-1;MODE=MySQL", "sa", "");
        Statement statement = connection.createStatement()) {
      executeScript(statement, h2Scripts.resolve("schema-1.3.0-h2.sql"));
      assertMetricSchema(statement);
    }
  }

  private static void assertMetricSchema(Statement statement) throws Exception {
    try (ResultSet result =
        statement.executeQuery(
            "SELECT COUNT(*) FROM INFORMATION_SCHEMA.TABLES"
                + " WHERE UPPER(TABLE_NAME) IN"
                + " ('DASHBOARD_METRIC_CURRENT', 'DASHBOARD_METRIC_DIRTY')")) {
      result.next();
      assertEquals(2, result.getInt(1));
    }

    for (String table : new String[] {"DASHBOARD_METRICS", "DASHBOARD_METRIC_CURRENT"}) {
      assertColumn(statement, table, "METRIC_NAME", "NO", 256L);
      assertColumn(statement, table, "METRIC_VALUE", "YES", null);
      assertColumn(statement, table, "METRIC_STATE", "NO", 16L);
      assertColumn(statement, table, "METRIC_MESSAGE", "YES", 1024L);
    }
  }

  private static void assertColumn(
      Statement statement,
      String table,
      String column,
      String nullable,
      Long characterMaximumLength)
      throws Exception {
    try (ResultSet result =
        statement.executeQuery(
            "SELECT IS_NULLABLE, CHARACTER_MAXIMUM_LENGTH FROM INFORMATION_SCHEMA.COLUMNS"
                + " WHERE UPPER(TABLE_NAME) = '"
                + table
                + "' AND UPPER(COLUMN_NAME) = '"
                + column
                + "'")) {
      result.next();
      assertEquals(nullable, result.getString(1));
      if (characterMaximumLength != null) {
        assertEquals(characterMaximumLength.longValue(), result.getLong(2));
      }
    }
  }

  private static void executeScript(Statement statement, Path script) throws Exception {
    String executableSql =
        Files.readAllLines(script).stream()
            .map(String::trim)
            .filter(line -> !line.isEmpty())
            .filter(line -> !line.startsWith("--"))
            .reduce((left, right) -> left + "\n" + right)
            .orElse("");
    List<String> statements =
        Arrays.stream(executableSql.split(";"))
            .map(String::trim)
            .filter(sql -> !sql.isEmpty())
            .toList();
    for (String sql : statements) {
      statement.execute(sql);
    }
  }
}
