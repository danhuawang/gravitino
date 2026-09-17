/*
 * Copyright 2026 Datastrato Inc.
 */
package com.datastrato.gravitino.flink.connector.jdbc.sqlserver;

import java.util.Optional;
import org.apache.flink.connector.jdbc.table.JdbcDynamicTableFactory;
import org.apache.flink.table.factories.CatalogFactory;
import org.apache.flink.table.factories.Factory;
import org.apache.gravitino.flink.connector.PartitionConverter;
import org.apache.gravitino.flink.connector.SchemaAndTablePropertiesConverter;

/** {@link GravitinoSqlServerJdbcCatalog} implementation for Flink 1.18. */
public class GravitinoSqlServerJdbcCatalogFlink118 extends GravitinoSqlServerJdbcCatalog {

  public GravitinoSqlServerJdbcCatalogFlink118(
      CatalogFactory.Context context,
      String defaultDatabase,
      SchemaAndTablePropertiesConverter schemaAndTablePropertiesConverter,
      PartitionConverter partitionConverter) {
    super(context, defaultDatabase, schemaAndTablePropertiesConverter, partitionConverter);
  }

  @Override
  public Optional<Factory> getFactory() {
    return Optional.of(new JdbcDynamicTableFactory());
  }
}
