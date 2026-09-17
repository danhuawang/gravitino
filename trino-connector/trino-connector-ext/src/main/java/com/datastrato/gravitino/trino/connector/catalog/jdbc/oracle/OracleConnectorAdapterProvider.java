/*
 * Copyright 2026 Datastrato Inc.
 */
package com.datastrato.gravitino.trino.connector.catalog.jdbc.oracle;

import org.apache.gravitino.trino.connector.GravitinoConfig;
import org.apache.gravitino.trino.connector.catalog.CatalogConnectorAdapter;
import org.apache.gravitino.trino.connector.catalog.CatalogConnectorAdapterProvider;

/** Registers the Oracle adapter with the Gravitino Trino connector. */
public class OracleConnectorAdapterProvider implements CatalogConnectorAdapterProvider {

  @Override
  public String provider() {
    return "jdbc-oracle";
  }

  @Override
  public CatalogConnectorAdapter createAdapter(GravitinoConfig config) {
    return new OracleConnectorAdapter();
  }
}
