/*
 * Copyright 2026 Datastrato Pvt Ltd.
 * This software is licensed under the Apache License version 2.
 */
package com.datastrato.gravitino.catalog.oracle;

import com.datastrato.gravitino.catalog.oracle.converter.OracleColumnDefaultValueConverter;
import com.datastrato.gravitino.catalog.oracle.converter.OracleExceptionConverter;
import com.datastrato.gravitino.catalog.oracle.converter.OracleTypeConverter;
import com.datastrato.gravitino.catalog.oracle.operations.OracleDatabaseOperations;
import com.datastrato.gravitino.catalog.oracle.operations.OracleTableOperations;
import java.util.Map;
import org.apache.gravitino.catalog.jdbc.JdbcCatalog;
import org.apache.gravitino.catalog.jdbc.JdbcCatalogOperations;
import org.apache.gravitino.catalog.jdbc.converter.JdbcColumnDefaultValueConverter;
import org.apache.gravitino.catalog.jdbc.converter.JdbcExceptionConverter;
import org.apache.gravitino.catalog.jdbc.converter.JdbcTypeConverter;
import org.apache.gravitino.catalog.jdbc.operation.JdbcDatabaseOperations;
import org.apache.gravitino.catalog.jdbc.operation.JdbcTableOperations;
import org.apache.gravitino.connector.CatalogOperations;
import org.apache.gravitino.connector.PropertiesMetadata;
import org.apache.gravitino.connector.capability.Capability;

public class OracleCatalog extends JdbcCatalog {

  private static final OracleTablePropertiesMetadata TABLE_PROPERTIES_METADATA =
      new OracleTablePropertiesMetadata();

  @Override
  public String shortName() {
    return "jdbc-oracle";
  }

  @Override
  protected CatalogOperations newOps(Map<String, String> config) {
    JdbcTypeConverter jdbcTypeConverter = createJdbcTypeConverter();
    JdbcCatalogOperations ops =
        new JdbcCatalogOperations(
            createExceptionConverter(),
            jdbcTypeConverter,
            createJdbcDatabaseOperations(),
            createJdbcTableOperations(),
            createJdbcColumnDefaultValueConverter());
    return ops;
  }

  @Override
  protected JdbcExceptionConverter createExceptionConverter() {
    return new OracleExceptionConverter();
  }

  @Override
  protected JdbcTypeConverter createJdbcTypeConverter() {
    return new OracleTypeConverter();
  }

  @Override
  protected JdbcDatabaseOperations createJdbcDatabaseOperations() {
    return new OracleDatabaseOperations();
  }

  @Override
  protected JdbcTableOperations createJdbcTableOperations() {
    return new OracleTableOperations();
  }

  @Override
  protected JdbcColumnDefaultValueConverter createJdbcColumnDefaultValueConverter() {
    return new OracleColumnDefaultValueConverter();
  }

  @Override
  public Capability newCapability() {
    return new OracleCatalogCapability();
  }

  @Override
  public PropertiesMetadata catalogPropertiesMetadata() throws UnsupportedOperationException {
    return new OracleCatalogPropertiesMetadata();
  }

  @Override
  public PropertiesMetadata tablePropertiesMetadata() throws UnsupportedOperationException {
    return TABLE_PROPERTIES_METADATA;
  }
}
