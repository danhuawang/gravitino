/*
 * Copyright 2026 Datastrato Inc.
 */
package com.datastrato.gravitino.catalog.oracle.integration.test.service;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.HashSet;
import java.util.Locale;
import java.util.Set;
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

  /** Returns whether a table exists in the current user's schema, matched case-insensitively. */
  public boolean tableExists(String tableName) {
    return tableExistsExact(tableName.toUpperCase(Locale.ROOT));
  }

  /**
   * Returns whether a table with this exact physical name (case preserved, not uppercased) exists
   * in the current user's schema. Used to verify quoted, case-sensitive Oracle tables.
   */
  public boolean tableExistsExact(String exactTableName) {
    String sql = "SELECT 1 FROM ALL_TABLES WHERE OWNER = ? AND TABLE_NAME = ?";
    try (PreparedStatement stmt = connection.prepareStatement(sql)) {
      stmt.setString(1, schemaOwner);
      stmt.setString(2, exactTableName);
      try (ResultSet rs = stmt.executeQuery()) {
        return rs.next();
      }
    } catch (SQLException e) {
      throw new RuntimeException(e);
    }
  }

  /** Returns Oracle-maintained usernames visible in {@code ALL_USERS}, upper-cased. */
  public Set<String> listOracleMaintainedUsers() {
    Set<String> users = new HashSet<>();
    String sql = "SELECT USERNAME FROM ALL_USERS WHERE ORACLE_MAINTAINED = 'Y'";
    try (Statement statement = connection.createStatement();
        ResultSet rs = statement.executeQuery(sql)) {
      while (rs.next()) {
        users.add(rs.getString("USERNAME").toUpperCase(Locale.ROOT));
      }
    } catch (SQLException e) {
      throw new RuntimeException(e);
    }
    return users;
  }

  /** Returns the comment stored for a column in {@code ALL_COL_COMMENTS}, or {@code null}. */
  public String getColumnComment(String tableName, String columnName) {
    String sql =
        "SELECT COMMENTS FROM ALL_COL_COMMENTS "
            + "WHERE OWNER = ? AND TABLE_NAME = ? AND COLUMN_NAME = ?";
    try (PreparedStatement stmt = connection.prepareStatement(sql)) {
      stmt.setString(1, schemaOwner);
      stmt.setString(2, tableName);
      stmt.setString(3, columnName);
      try (ResultSet rs = stmt.executeQuery()) {
        return rs.next() ? rs.getString("COMMENTS") : null;
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
