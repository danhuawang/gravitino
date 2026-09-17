/*
 * Copyright 2026 Datastrato Inc.
 */
package com.datastrato.gravitino.flink.connector.jdbc.oracle;

import java.util.Optional;
import org.apache.flink.connector.jdbc.core.table.JdbcDynamicTableFactory;
import org.apache.flink.table.factories.CatalogFactory;
import org.apache.flink.table.factories.Factory;
import org.apache.gravitino.flink.connector.PartitionConverter;
import org.apache.gravitino.flink.connector.SchemaAndTablePropertiesConverter;
import org.apache.gravitino.flink.connector.utils.CatalogCompat;
import org.apache.gravitino.flink.connector.utils.DefaultCatalogCompat;

/** {@link GravitinoOracleJdbcCatalog} implementation for Flink 1.19. */
public class GravitinoOracleJdbcCatalogFlink119 extends GravitinoOracleJdbcCatalog {

  GravitinoOracleJdbcCatalogFlink119(
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

  @Override
  protected CatalogCompat catalogCompat() {
    return DefaultCatalogCompat.INSTANCE;
  }
}
