/*
 * Copyright 2026 Datastrato Inc.
 */
package com.datastrato.gravitino.trino.connector.catalog.jdbc.sqlserver;

import org.apache.gravitino.trino.connector.GravitinoConfig;
import org.apache.gravitino.trino.connector.catalog.CatalogConnectorAdapter;
import org.apache.gravitino.trino.connector.catalog.CatalogConnectorAdapterProvider;

/** Registers the SQL Server adapter with the Gravitino Trino connector. */
public class SqlServerConnectorAdapterProvider implements CatalogConnectorAdapterProvider {

  @Override
  public String provider() {
    return "jdbc-sqlserver";
  }

  @Override
  public CatalogConnectorAdapter createAdapter(GravitinoConfig config) {
    return new SqlServerConnectorAdapter();
  }
}
