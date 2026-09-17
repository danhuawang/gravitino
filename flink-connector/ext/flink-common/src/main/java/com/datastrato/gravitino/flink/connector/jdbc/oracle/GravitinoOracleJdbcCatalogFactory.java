/*
 * Copyright 2026 Datastrato Inc.
 */
package com.datastrato.gravitino.flink.connector.jdbc.oracle;

import com.datastrato.gravitino.flink.connector.jdbc.ExtJdbcCatalogFactoryOptions;
import org.apache.flink.table.catalog.Catalog;
import org.apache.gravitino.flink.connector.CatalogPropertiesConverter;
import org.apache.gravitino.flink.connector.PartitionConverter;
import org.apache.gravitino.flink.connector.SchemaAndTablePropertiesConverter;
import org.apache.gravitino.flink.connector.jdbc.GravitinoJdbcCatalogFactory;

/** Factory for creating {@link GravitinoOracleJdbcCatalog} instances. */
public class GravitinoOracleJdbcCatalogFactory extends GravitinoJdbcCatalogFactory {

  @Override
  public String gravitinoCatalogProvider() {
    return "jdbc-oracle";
  }

  @Override
  public CatalogPropertiesConverter catalogPropertiesConverter() {
    return OraclePropertiesConverter.INSTANCE;
  }

  @Override
  public SchemaAndTablePropertiesConverter schemaAndTablePropertiesConverter() {
    return OraclePropertiesConverter.INSTANCE;
  }

  @Override
  public String factoryIdentifier() {
    return ExtJdbcCatalogFactoryOptions.ORACLE_IDENTIFIER;
  }

  @Override
  protected Catalog newCatalog(
      Context context,
      String defaultDatabase,
      SchemaAndTablePropertiesConverter schemaAndTablePropertiesConverter,
      PartitionConverter partitionConverter) {
    return new GravitinoOracleJdbcCatalog(
        context, defaultDatabase, schemaAndTablePropertiesConverter, partitionConverter);
  }
}
