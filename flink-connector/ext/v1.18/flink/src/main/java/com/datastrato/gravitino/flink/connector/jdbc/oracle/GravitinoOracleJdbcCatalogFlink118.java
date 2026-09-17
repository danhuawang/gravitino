/*
 * Copyright 2026 Datastrato Inc.
 */
package com.datastrato.gravitino.flink.connector.jdbc.oracle;

import org.apache.flink.table.factories.CatalogFactory;
import org.apache.gravitino.flink.connector.PartitionConverter;
import org.apache.gravitino.flink.connector.SchemaAndTablePropertiesConverter;
import org.apache.gravitino.flink.connector.utils.CatalogCompat;
import org.apache.gravitino.flink.connector.utils.CatalogCompatFlink118;

/** {@link GravitinoOracleJdbcCatalog} implementation for Flink 1.18. */
public class GravitinoOracleJdbcCatalogFlink118 extends GravitinoOracleJdbcCatalog {

  GravitinoOracleJdbcCatalogFlink118(
      CatalogFactory.Context context,
      String defaultDatabase,
      SchemaAndTablePropertiesConverter schemaAndTablePropertiesConverter,
      PartitionConverter partitionConverter) {
    super(context, defaultDatabase, schemaAndTablePropertiesConverter, partitionConverter);
  }

  @Override
  protected CatalogCompat catalogCompat() {
    return CatalogCompatFlink118.INSTANCE;
  }
}
