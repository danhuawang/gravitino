/*
 * Copyright 2026 Datastrato Inc.
 */
package com.datastrato.gravitino.trino.connector.catalog.jdbc.sqlserver;

import io.trino.spi.session.PropertyMetadata;
import java.util.List;
import org.apache.gravitino.trino.connector.catalog.CatalogConnectorMetadataAdapter;

/** Transforming gravitino SQL Server metadata to Trino. */
public class SqlServerMetadataAdapter extends CatalogConnectorMetadataAdapter {

  /**
   * Constructs a new SqlServerMetadataAdapter.
   *
   * @param schemaProperties The list of schema property metadata
   * @param tableProperties The list of table property metadata
   * @param columnProperties The list of column property metadata
   */
  public SqlServerMetadataAdapter(
      List<PropertyMetadata<?>> schemaProperties,
      List<PropertyMetadata<?>> tableProperties,
      List<PropertyMetadata<?>> columnProperties) {

    super(schemaProperties, tableProperties, columnProperties, new SqlServerDataTypeTransformer());
  }
}
