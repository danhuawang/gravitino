/*
 * Copyright 2026 Datastrato Inc.
 */
package com.datastrato.gravitino.trino.connector.catalog.jdbc.sqlserver;

import static java.util.Collections.emptyList;

import java.util.HashMap;
import java.util.Map;
import org.apache.gravitino.catalog.property.PropertyConverter;
import org.apache.gravitino.credential.Credential;
import org.apache.gravitino.trino.connector.catalog.CatalogConnectorAdapter;
import org.apache.gravitino.trino.connector.catalog.CatalogConnectorMetadataAdapter;
import org.apache.gravitino.trino.connector.catalog.jdbc.JDBCCatalogPropertyConverter;
import org.apache.gravitino.trino.connector.metadata.GravitinoCatalog;

/**
 * Transforming SQL Server connector configuration and components into Apache Gravitino connector.
 */
public class SqlServerConnectorAdapter implements CatalogConnectorAdapter {
  private static final String CONNECTOR_SQLSERVER = "sqlserver";
  private final PropertyConverter catalogConverter;

  /**
   * Constructs a new SqlServerConnectorAdapter. Initializes the catalog property converter for SQL
   * Server-specific configurations.
   */
  public SqlServerConnectorAdapter() {
    this.catalogConverter = new JDBCCatalogPropertyConverter();
  }

  @Override
  public Map<String, String> buildInternalConnectorConfig(
      GravitinoCatalog catalog, Credential[] credentials) throws Exception {
    Map<String, String> gravitinoProps = new HashMap<>(catalog.getProperties());
    JDBCCatalogPropertyConverter.applyJdbcCredential(credentials, gravitinoProps);
    return catalogConverter.gravitinoToEngineProperties(gravitinoProps);
  }

  @Override
  public String internalConnectorName() {
    return CONNECTOR_SQLSERVER;
  }

  @Override
  public CatalogConnectorMetadataAdapter getMetadataAdapter() {
    return new SqlServerMetadataAdapter(getSchemaProperties(), getTableProperties(), emptyList());
  }
}
