/*
 * Copyright 2026 Datastrato Pvt Ltd.
 * This software is licensed under the Apache License version 2.
 */
package com.datastrato.gravitino.catalog.oracle.integration.test.service;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.Locale;
import org.apache.gravitino.integration.test.container.OracleContainer;
import org.apache.gravitino.integration.test.util.TestDatabaseName;

/** Thin helper that lets ITs issue direct SQL against the Oracle container for verification. */
public class OracleService {

  private final Connection connection;
  private final String schemaOwner;

  public OracleService(OracleContainer oracleContainer, TestDatabaseName testDBName) {
    String jdbcUrl = oracleContainer.getJdbcUrl(testDBName);
    String username = oracleContainer.getUsername();
    try {
      connection = DriverManager.getConnection(jdbcUrl, username, oracleContainer.getPassword());
      schemaOwner = username.toUpperCase(Locale.ROOT);
    } catch (SQLException e) {
      throw new RuntimeException(
          String.format(
              "Failed to open Oracle JDBC connection. url=%s, user=%s", jdbcUrl, username),
          e);
    }
  }

  public void executeQuery(String sql) {
    try (Statement statement = connection.createStatement()) {
      statement.execute(sql);
    } catch (SQLException e) {
      throw new RuntimeException(e);
    }
  }

  /** Returns whether a table exists in the current user's schema. */
  public boolean tableExists(String tableName) {
    String sql = "SELECT 1 FROM ALL_TABLES WHERE OWNER = ? AND TABLE_NAME = ?";
    try (PreparedStatement stmt = connection.prepareStatement(sql)) {
      stmt.setString(1, schemaOwner);
      stmt.setString(2, tableName.toUpperCase(Locale.ROOT));
      try (ResultSet rs = stmt.executeQuery()) {
        return rs.next();
      }
    } catch (SQLException e) {
      throw new RuntimeException(e);
    }
  }

  public void close() {
    try {
      connection.close();
    } catch (SQLException e) {
      // ignore
    }
  }
}
