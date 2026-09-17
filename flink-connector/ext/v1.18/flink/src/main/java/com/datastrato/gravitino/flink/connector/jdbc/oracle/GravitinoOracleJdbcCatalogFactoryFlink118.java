/*
 * Copyright 2026 Datastrato Inc.
 */
package com.datastrato.gravitino.flink.connector.jdbc.oracle;

import org.apache.flink.table.catalog.Catalog;
import org.apache.flink.table.factories.CatalogFactory;
import org.apache.gravitino.flink.connector.PartitionConverter;
import org.apache.gravitino.flink.connector.SchemaAndTablePropertiesConverter;

/** Flink 1.18 specific factory for {@link GravitinoOracleJdbcCatalogFactory}. */
public class GravitinoOracleJdbcCatalogFactoryFlink118 extends GravitinoOracleJdbcCatalogFactory {

  @Override
  protected Catalog newCatalog(
      CatalogFactory.Context context,
      String defaultDatabase,
      SchemaAndTablePropertiesConverter schemaAndTablePropertiesConverter,
      PartitionConverter partitionConverter) {
    return new GravitinoOracleJdbcCatalogFlink118(
        context, defaultDatabase, schemaAndTablePropertiesConverter, partitionConverter);
  }
}
