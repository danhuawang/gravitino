/*
 * Copyright 2026 Datastrato Inc.
 */
package com.datastrato.gravitino.trino.connector.catalog.jdbc.oracle;

import io.trino.spi.session.PropertyMetadata;
import java.util.List;
import org.apache.gravitino.trino.connector.catalog.CatalogConnectorMetadataAdapter;

/** Transforming Gravitino Oracle metadata to Trino. */
public class OracleMetadataAdapter extends CatalogConnectorMetadataAdapter {

  /**
   * Constructs a new OracleMetadataAdapter.
   *
   * @param schemaProperties The list of schema property metadata
   * @param tableProperties The list of table property metadata
   * @param columnProperties The list of column property metadata
   */
  public OracleMetadataAdapter(
      List<PropertyMetadata<?>> schemaProperties,
      List<PropertyMetadata<?>> tableProperties,
      List<PropertyMetadata<?>> columnProperties) {
    super(schemaProperties, tableProperties, columnProperties, new OracleDataTypeTransformer());
  }
}
