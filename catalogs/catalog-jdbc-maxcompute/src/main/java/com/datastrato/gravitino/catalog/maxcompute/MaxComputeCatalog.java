/*
 * Copyright 2026 Datastrato Inc.
 */

package com.datastrato.gravitino.catalog.maxcompute;

import com.datastrato.gravitino.catalog.maxcompute.converter.MaxComputeColumnDefaultValueConverter;
import com.datastrato.gravitino.catalog.maxcompute.converter.MaxComputeExceptionConverter;
import com.datastrato.gravitino.catalog.maxcompute.converter.MaxComputeTypeConverter;
import com.datastrato.gravitino.catalog.maxcompute.operation.MaxComputeDatabaseOperations;
import com.datastrato.gravitino.catalog.maxcompute.operation.MaxComputeTableOperations;
import com.datastrato.gravitino.catalog.maxcompute.utils.MaxComputeTimeoutUtils;
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

/** MaxCompute catalog implementation. */
public class MaxComputeCatalog extends JdbcCatalog {

  public static final MaxComputeTablePropertiesMetadata MAXCOMPUTE_TABLE_PROPERTIES_META =
      new MaxComputeTablePropertiesMetadata();

  /**
   * {@inheritDoc}
   *
   * <p>Injects bounded default connection timeouts into the {@code jdbc-url} before the
   * configuration is used to open connections. The ODPS JDBC driver defaults its timeouts to "wait
   * forever", so without this an unreachable backend would block a connection attempt for the full
   * operating-system TCP timeout while the search metadata sync holds the metalake tree locks. See
   * {@link MaxComputeTimeoutUtils}.
   */
  @Override
  public JdbcCatalog withCatalogConf(Map<String, String> conf) {
    return super.withCatalogConf(MaxComputeTimeoutUtils.withConnectionTimeouts(conf));
  }

  @Override
  public String shortName() {
    return "jdbc-maxcompute";
  }

  @Override
  protected CatalogOperations newOps(Map<String, String> config) {
    JdbcTypeConverter jdbcTypeConverter = createJdbcTypeConverter();
    return new JdbcCatalogOperations(
        createExceptionConverter(),
        jdbcTypeConverter,
        createJdbcDatabaseOperations(),
        createJdbcTableOperations(),
        createJdbcColumnDefaultValueConverter());
  }

  @Override
  public Capability newCapability() {
    return new MaxComputeCatalogCapability();
  }

  @Override
  protected JdbcExceptionConverter createExceptionConverter() {
    return new MaxComputeExceptionConverter();
  }

  @Override
  protected JdbcTypeConverter createJdbcTypeConverter() {
    return new MaxComputeTypeConverter();
  }

  @Override
  protected JdbcDatabaseOperations createJdbcDatabaseOperations() {
    return new MaxComputeDatabaseOperations();
  }

  @Override
  protected JdbcTableOperations createJdbcTableOperations() {
    return new MaxComputeTableOperations();
  }

  @Override
  protected JdbcColumnDefaultValueConverter createJdbcColumnDefaultValueConverter() {
    return new MaxComputeColumnDefaultValueConverter();
  }

  @Override
  public PropertiesMetadata tablePropertiesMetadata() throws UnsupportedOperationException {
    return MAXCOMPUTE_TABLE_PROPERTIES_META;
  }
}
