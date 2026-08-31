/*
 * Copyright 2026 Datastrato Inc.
 */
package com.datastrato.gravitino.catalog.sqlserver;

import java.sql.Driver;
import java.sql.DriverManager;
import java.sql.SQLException;
import org.apache.gravitino.catalog.jdbc.JdbcCatalogOperations;
import org.apache.gravitino.catalog.jdbc.converter.JdbcColumnDefaultValueConverter;
import org.apache.gravitino.catalog.jdbc.converter.JdbcExceptionConverter;
import org.apache.gravitino.catalog.jdbc.converter.JdbcTypeConverter;
import org.apache.gravitino.catalog.jdbc.operation.JdbcDatabaseOperations;
import org.apache.gravitino.catalog.jdbc.operation.JdbcTableOperations;

/** Catalog operations for SQL Server. */
public class SqlServerCatalogOperations extends JdbcCatalogOperations {

  public SqlServerCatalogOperations(
      JdbcExceptionConverter exceptionConverter,
      JdbcTypeConverter jdbcTypeConverter,
      JdbcDatabaseOperations databaseOperation,
      JdbcTableOperations tableOperation,
      JdbcColumnDefaultValueConverter columnDefaultValueConverter) {
    super(
        exceptionConverter,
        jdbcTypeConverter,
        databaseOperation,
        tableOperation,
        columnDefaultValueConverter);
  }

  @Override
  protected Driver getDriver() throws SQLException {
    return DriverManager.getDriver("jdbc:sqlserver://dummy_address:12345/");
  }
}
