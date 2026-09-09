/*
 * Copyright 2026 Datastrato Inc.
 */
package com.datastrato.gravitino.catalog.sqlserver;

import com.datastrato.gravitino.catalog.sqlserver.converter.SqlServerColumnDefaultValueConverter;
import com.datastrato.gravitino.catalog.sqlserver.converter.SqlServerExceptionConverter;
import com.datastrato.gravitino.catalog.sqlserver.converter.SqlServerTypeConverter;
import com.datastrato.gravitino.catalog.sqlserver.operation.SqlServerSchemaOperations;
import com.datastrato.gravitino.catalog.sqlserver.operation.SqlServerTableOperations;
import com.microsoft.sqlserver.jdbc.SQLServerDriver;
import java.sql.DriverPropertyInfo;
import java.sql.SQLException;
import java.util.Map;
import java.util.Properties;
import javax.annotation.Nullable;
import org.apache.gravitino.catalog.jdbc.JdbcCatalog;
import org.apache.gravitino.catalog.jdbc.converter.JdbcColumnDefaultValueConverter;
import org.apache.gravitino.catalog.jdbc.converter.JdbcExceptionConverter;
import org.apache.gravitino.catalog.jdbc.converter.JdbcTypeConverter;
import org.apache.gravitino.catalog.jdbc.operation.JdbcDatabaseOperations;
import org.apache.gravitino.catalog.jdbc.operation.JdbcTableOperations;
import org.apache.gravitino.connector.CatalogOperations;
import org.apache.gravitino.connector.PropertiesMetadata;
import org.apache.gravitino.connector.capability.Capability;

/** Implementation of a SQL Server catalog in Gravitino. */
public class SqlServerCatalog extends JdbcCatalog {

  private static final SqlServerTablePropertiesMetadata TABLE_PROPERTIES_META =
      new SqlServerTablePropertiesMetadata();

  /** {@inheritDoc} */
  @Override
  public JdbcCatalog withCatalogConf(Map<String, String> conf) {
    return super.withCatalogConf(resolveJdbcDatabase(conf, SqlServerCatalog::databaseFromUrl));
  }

  @Override
  public String shortName() {
    return "jdbc-sqlserver";
  }

  @Override
  protected CatalogOperations newOps(Map<String, String> config) {
    JdbcTypeConverter jdbcTypeConverter = createJdbcTypeConverter();
    return new SqlServerCatalogOperations(
        createExceptionConverter(),
        jdbcTypeConverter,
        createJdbcDatabaseOperations(),
        createJdbcTableOperations(),
        createJdbcColumnDefaultValueConverter());
  }

  @Override
  public Capability newCapability() {
    return new SqlServerCatalogCapability();
  }

  @Override
  protected JdbcExceptionConverter createExceptionConverter() {
    return new SqlServerExceptionConverter();
  }

  @Override
  protected JdbcTypeConverter createJdbcTypeConverter() {
    return new SqlServerTypeConverter();
  }

  @Override
  protected JdbcDatabaseOperations createJdbcDatabaseOperations() {
    return new SqlServerSchemaOperations();
  }

  @Override
  protected JdbcTableOperations createJdbcTableOperations() {
    return new SqlServerTableOperations();
  }

  @Override
  protected JdbcColumnDefaultValueConverter createJdbcColumnDefaultValueConverter() {
    return new SqlServerColumnDefaultValueConverter();
  }

  @Override
  public PropertiesMetadata tablePropertiesMetadata() throws UnsupportedOperationException {
    return TABLE_PROPERTIES_META;
  }

  @Nullable
  private static String databaseFromUrl(String url) {
    try {
      for (DriverPropertyInfo property :
          new SQLServerDriver().getPropertyInfo(url, new Properties())) {
        if ("databaseName".equals(property.name)) {
          return property.value;
        }
      }
      return null;
    } catch (SQLException e) {
      throw new IllegalArgumentException("Cannot resolve jdbc-database from jdbc-url", e);
    }
  }
}
