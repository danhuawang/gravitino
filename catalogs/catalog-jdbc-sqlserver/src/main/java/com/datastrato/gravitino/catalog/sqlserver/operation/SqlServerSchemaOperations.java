/*
 * Copyright 2026 Datastrato Pvt Ltd.
 * This software is licensed under the Apache License version 2.
 */
package com.datastrato.gravitino.catalog.sqlserver.operation;

import com.google.common.collect.ImmutableSet;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import javax.sql.DataSource;
import org.apache.commons.collections4.MapUtils;
import org.apache.gravitino.catalog.jdbc.JdbcSchema;
import org.apache.gravitino.catalog.jdbc.config.JdbcConfig;
import org.apache.gravitino.catalog.jdbc.converter.JdbcExceptionConverter;
import org.apache.gravitino.catalog.jdbc.operation.JdbcDatabaseOperations;
import org.apache.gravitino.exceptions.NoSuchSchemaException;
import org.apache.gravitino.exceptions.SchemaAlreadyExistsException;
import org.apache.gravitino.exceptions.TableAlreadyExistsException;
import org.apache.gravitino.meta.AuditInfo;

/**
 * Schema operations for SQL Server catalog. Maps Gravitino Schema to SQL Server Schema (e.g., dbo).
 * Extends JdbcDatabaseOperations following the same pattern as PostgreSqlSchemaOperations.
 */
public class SqlServerSchemaOperations extends JdbcDatabaseOperations {

  private static final Set<String> SYSTEM_SCHEMAS =
      ImmutableSet.of(
          "guest",
          "information_schema",
          "sys",
          "db_owner",
          "db_accessadmin",
          "db_securityadmin",
          "db_ddladmin",
          "db_backupoperator",
          "db_datareader",
          "db_datawriter",
          "db_denydatareader",
          "db_denydatawriter");

  private String database;

  @Override
  public void initialize(
      DataSource dataSource, JdbcExceptionConverter exceptionMapper, Map<String, String> conf) {
    super.initialize(dataSource, exceptionMapper, conf);
    database = new JdbcConfig(conf).getJdbcDatabase();
  }

  @Override
  public void create(String databaseName, String comment, Map<String, String> properties)
      throws SchemaAlreadyExistsException {
    try {
      super.create(databaseName, comment, properties);
    } catch (TableAlreadyExistsException e) {
      // SQL Server returns error code 2714 for both duplicate tables and duplicate schemas.
      // The converter maps 2714 to TableAlreadyExistsException, but in schema context
      // we need SchemaAlreadyExistsException.
      throw new SchemaAlreadyExistsException(e, "Schema already exists: %s", databaseName);
    }
  }

  @Override
  public List<String> listDatabases() {
    List<String> result = new ArrayList<>();
    try (Connection connection = getConnection();
        PreparedStatement statement =
            connection.prepareStatement("SELECT s.name FROM sys.schemas s ORDER BY s.name");
        ResultSet resultSet = statement.executeQuery()) {
      while (resultSet.next()) {
        String schemaName = resultSet.getString(1);
        if (!isSystemDatabase(schemaName)) {
          result.add(schemaName);
        }
      }
    } catch (SQLException se) {
      throw exceptionMapper.toGravitinoException(se);
    }
    return result;
  }

  @Override
  public JdbcSchema load(String schema) throws NoSuchSchemaException {
    try (Connection connection = getConnection();
        PreparedStatement statement =
            connection.prepareStatement("SELECT s.name FROM sys.schemas s WHERE s.name = ?")) {
      statement.setString(1, schema);
      try (ResultSet resultSet = statement.executeQuery()) {
        if (!resultSet.next()) {
          throw new NoSuchSchemaException("No such schema: %s", schema);
        }
        return JdbcSchema.builder()
            .withName(schema)
            .withComment(null)
            .withAuditInfo(AuditInfo.EMPTY)
            .withProperties(Collections.emptyMap())
            .build();
      }
    } catch (NoSuchSchemaException e) {
      throw e;
    } catch (SQLException se) {
      throw exceptionMapper.toGravitinoException(se);
    }
  }

  @Override
  protected String generateCreateDatabaseSql(
      String schema, String comment, Map<String, String> properties) {
    if (MapUtils.isNotEmpty(properties)) {
      throw new UnsupportedOperationException(
          "SQL Server does not support properties on schema create.");
    }
    return "CREATE SCHEMA [" + schema + "] AUTHORIZATION [dbo]";
  }

  @Override
  protected String generateDropDatabaseSql(String schema, boolean cascade) {
    // SQL Server does not support DROP SCHEMA ... CASCADE.
    // If the schema contains objects, the native error is surfaced directly.
    return "DROP SCHEMA [" + schema + "]";
  }

  @Override
  protected Connection getConnection() throws SQLException {
    Connection connection = dataSource.getConnection();
    connection.setCatalog(database);
    return connection;
  }

  /**
   * Checks if a schema exists in the current database.
   *
   * @param connection The JDBC connection
   * @param schema The schema name to check
   * @return true if the schema exists, false otherwise
   * @throws SQLException if a database access error occurs
   */
  public boolean schemaExists(Connection connection, String schema) throws SQLException {
    try (PreparedStatement statement =
        connection.prepareStatement("SELECT s.name FROM sys.schemas s WHERE s.name = ?")) {
      statement.setString(1, schema);
      try (ResultSet resultSet = statement.executeQuery()) {
        while (resultSet.next()) {
          if (Objects.equals(resultSet.getString(1), schema)) {
            return true;
          }
        }
      }
    }
    return false;
  }

  @Override
  protected boolean supportSchemaComment() {
    return false;
  }

  @Override
  protected Set<String> createSysDatabaseNameSet() {
    return SYSTEM_SCHEMAS;
  }
}
