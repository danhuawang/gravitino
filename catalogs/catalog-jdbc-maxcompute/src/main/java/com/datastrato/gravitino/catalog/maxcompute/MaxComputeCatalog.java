/*
 * Copyright 2026 Datastrato Pvt Ltd.
 * This software is licensed under the Apache License version 2.
 */

package com.datastrato.gravitino.catalog.maxcompute;

import com.datastrato.gravitino.catalog.maxcompute.converter.MaxComputeColumnDefaultValueConverter;
import com.datastrato.gravitino.catalog.maxcompute.converter.MaxComputeExceptionConverter;
import com.datastrato.gravitino.catalog.maxcompute.converter.MaxComputeTypeConverter;
import com.datastrato.gravitino.catalog.maxcompute.operation.MaxComputeDatabaseOperations;
import com.datastrato.gravitino.catalog.maxcompute.operation.MaxComputeTableOperations;
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
