/*
 * Copyright 2026 Datastrato Inc.
 */
package org.apache.iceberg.jdbc;

import static org.apache.iceberg.jdbc.JdbcTrustedMetadataStore.TABLE_NAME;

import com.google.common.base.Preconditions;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;

/**
 * Inspects the storage engines behind the JDBC catalog tables.
 *
 * <p>The schema itself is defined by the versioned DDL scripts and provisioned/probed by {@link
 * JdbcTrustedMetadataStore}; this class only answers whether MySQL backs both {@code
 * iceberg_tables} and {@code iceberg_metadata_integrity} with InnoDB, the engine the two-statement
 * commit transactions and the foreign-key cascade rely on. Other products are transactional by
 * default and pass without a query.
 */
final class JdbcMetadataIntegritySchema {

  private static final String MYSQL_DATABASE_PRODUCT = "MySQL";
  private static final String MYSQL_TABLE_ENGINES_SQL =
      "SELECT TABLE_NAME, ENGINE FROM information_schema.TABLES"
          + " WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME IN (?, ?)";

  private JdbcMetadataIntegritySchema() {}

  /**
   * Fails when the connected MySQL database backs either catalog table with a non-InnoDB engine.
   *
   * @param connection open connection to the catalog database
   * @throws SQLException when a MySQL table is missing or not InnoDB, or the inspection fails
   */
  static void validateAtomicStorage(Connection connection) throws SQLException {
    Preconditions.checkNotNull(connection, "connection cannot be null");
    if (isDatabaseProduct(connection, MYSQL_DATABASE_PRODUCT)) {
      validateMySqlStorageEngines(connection);
    }
  }

  private static void validateMySqlStorageEngines(Connection connection) throws SQLException {
    Map<String, String> engines = new LinkedHashMap<>();
    try (PreparedStatement statement = connection.prepareStatement(MYSQL_TABLE_ENGINES_SQL)) {
      statement.setString(1, JdbcUtil.CATALOG_TABLE_VIEW_NAME);
      statement.setString(2, TABLE_NAME);
      try (ResultSet resultSet = statement.executeQuery()) {
        while (resultSet.next()) {
          String table = resultSet.getString("TABLE_NAME");
          String normalizedTable = table == null ? null : table.toLowerCase(Locale.ROOT);
          if (normalizedTable == null || engines.containsKey(normalizedTable)) {
            throw new SQLException(
                "Could not uniquely resolve MySQL tables required for atomic metadata integrity");
          }
          engines.put(normalizedTable, resultSet.getString("ENGINE"));
        }
      }
    }

    validateMySqlStorageEngine(engines, JdbcUtil.CATALOG_TABLE_VIEW_NAME);
    validateMySqlStorageEngine(engines, TABLE_NAME);
  }

  private static void validateMySqlStorageEngine(Map<String, String> engines, String table)
      throws SQLException {
    String engine = engines.get(table.toLowerCase(Locale.ROOT));
    if (!"InnoDB".equalsIgnoreCase(engine)) {
      throw new SQLException(
          String.format(
              "MySQL table %s must use InnoDB for atomic metadata integrity; found %s",
              table, engine == null ? "<missing>" : engine));
    }
  }

  private static boolean isDatabaseProduct(Connection connection, String expected)
      throws SQLException {
    return expected.equalsIgnoreCase(connection.getMetaData().getDatabaseProductName());
  }
}
