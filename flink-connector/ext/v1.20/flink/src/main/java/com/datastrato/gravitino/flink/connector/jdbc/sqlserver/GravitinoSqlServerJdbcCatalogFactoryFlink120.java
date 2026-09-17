/*
 * Copyright 2026 Datastrato Inc.
 */
package com.datastrato.gravitino.flink.connector.jdbc.sqlserver;

import org.apache.flink.table.catalog.Catalog;
import org.apache.flink.table.factories.CatalogFactory;
import org.apache.gravitino.flink.connector.PartitionConverter;
import org.apache.gravitino.flink.connector.SchemaAndTablePropertiesConverter;

/** {@link GravitinoSqlServerJdbcCatalogFactory} implementation for Flink 1.20. */
public class GravitinoSqlServerJdbcCatalogFactoryFlink120
    extends GravitinoSqlServerJdbcCatalogFactory {

  @Override
  protected Catalog newCatalog(
      CatalogFactory.Context context,
      String defaultDatabase,
      SchemaAndTablePropertiesConverter schemaAndTablePropertiesConverter,
      PartitionConverter partitionConverter) {
    return new GravitinoSqlServerJdbcCatalogFlink120(
        context, defaultDatabase, schemaAndTablePropertiesConverter, partitionConverter);
  }
}
