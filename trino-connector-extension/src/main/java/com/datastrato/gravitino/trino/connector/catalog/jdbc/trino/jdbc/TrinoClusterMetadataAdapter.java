/*
 * Copyright 2024 Datastrato Pvt Ltd.
 * This software is licensed under the Apache License version 2.
 */
package com.datastrato.gravitino.trino.connector.catalog.jdbc.trino.jdbc;

import io.trino.spi.session.PropertyMetadata;
import java.util.List;
import org.apache.gravitino.trino.connector.catalog.CatalogConnectorMetadataAdapter;
import org.apache.gravitino.trino.connector.util.GeneralDataTypeTransformer;

/** Support Trino cluster connector for testing. */
public class TrinoClusterMetadataAdapter extends CatalogConnectorMetadataAdapter {

  public TrinoClusterMetadataAdapter(
      List<PropertyMetadata<?>> schemaProperties,
      List<PropertyMetadata<?>> tableProperties,
      List<PropertyMetadata<?>> columnProperties) {

    super(schemaProperties, tableProperties, columnProperties, new GeneralDataTypeTransformer());
  }
}
