/*
 * Copyright 2026 Datastrato Inc.
 */
package com.datastrato.gravitino.trino.connector.catalog.jdbc.oracle;

import static java.util.Collections.emptyList;

import java.util.HashMap;
import java.util.Map;
import org.apache.gravitino.catalog.property.PropertyConverter;
import org.apache.gravitino.credential.Credential;
import org.apache.gravitino.trino.connector.catalog.CatalogConnectorAdapter;
import org.apache.gravitino.trino.connector.catalog.CatalogConnectorMetadataAdapter;
import org.apache.gravitino.trino.connector.catalog.jdbc.JDBCCatalogPropertyConverter;
import org.apache.gravitino.trino.connector.metadata.GravitinoCatalog;

/** Transforming Oracle connector configuration and components into Apache Gravitino connector. */
public class OracleConnectorAdapter implements CatalogConnectorAdapter {

  private static final String CONNECTOR_ORACLE = "oracle";
  private final PropertyConverter catalogConverter;

  /**
   * Constructs a new OracleConnectorAdapter. Initializes the catalog property converter for
   * Oracle-specific configurations.
   */
  public OracleConnectorAdapter() {
    this.catalogConverter = new JDBCCatalogPropertyConverter();
  }

  @Override
  public Map<String, String> buildInternalConnectorConfig(
      GravitinoCatalog catalog, Credential[] credentials) throws Exception {
    Map<String, String> gravitinoProps = new HashMap<>(catalog.getProperties());
    JDBCCatalogPropertyConverter.applyJdbcCredential(credentials, gravitinoProps);
    return new HashMap<>(catalogConverter.gravitinoToEngineProperties(gravitinoProps));
  }

  @Override
  public String internalConnectorName() {
    return CONNECTOR_ORACLE;
  }

  @Override
  public CatalogConnectorMetadataAdapter getMetadataAdapter() {
    // Column properties are not yet supported for the Oracle catalog; only schema and table
    // properties are wired up here.
    return new OracleMetadataAdapter(getSchemaProperties(), getTableProperties(), emptyList());
  }
}
