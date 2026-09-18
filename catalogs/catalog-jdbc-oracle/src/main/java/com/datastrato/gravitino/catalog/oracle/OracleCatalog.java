/*
 * Copyright 2026 Datastrato Inc.
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

  // The table operations instance created for this catalog, retained so newCapability() can read
  // the Oracle server's identifier length limit it resolves at initialization time (#2095).
  private volatile OracleTableOperations oracleTableOperations;

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
    OracleTableOperations ops = new OracleTableOperations();
    this.oracleTableOperations = ops;
    return ops;
  }

  @Override
  protected JdbcColumnDefaultValueConverter createJdbcColumnDefaultValueConverter() {
    return new OracleColumnDefaultValueConverter();
  }

  @Override
  public Capability newCapability() {
    // Ensure the operations layer is initialized so the Oracle server's identifier length limit is
    // resolved before the capability that enforces it is built. When operations are not yet
    // available (e.g. before the catalog is initialized), the capability falls back to its default
    // limit.
    OracleTableOperations tableOps = this.oracleTableOperations;
    if (tableOps == null) {
      ops();
      tableOps = this.oracleTableOperations;
    }
    return tableOps != null
        ? new OracleCatalogCapability(tableOps.getMaxIdentifierLengthBytes())
        : new OracleCatalogCapability();
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
