/*
 * Copyright 2026 Datastrato Inc.
 */
package com.datastrato.gravitino.flink.connector.jdbc.sqlserver;

import com.datastrato.gravitino.flink.connector.jdbc.ExtJdbcCatalogFactoryOptions;
import org.apache.flink.table.catalog.Catalog;
import org.apache.gravitino.flink.connector.CatalogPropertiesConverter;
import org.apache.gravitino.flink.connector.PartitionConverter;
import org.apache.gravitino.flink.connector.SchemaAndTablePropertiesConverter;
import org.apache.gravitino.flink.connector.jdbc.GravitinoJdbcCatalogFactory;

/** Factory for creating SQL Server JDBC catalog instances. */
public class GravitinoSqlServerJdbcCatalogFactory extends GravitinoJdbcCatalogFactory {

  @Override
  public String gravitinoCatalogProvider() {
    return "jdbc-sqlserver";
  }

  @Override
  public CatalogPropertiesConverter catalogPropertiesConverter() {
    return SqlServerPropertiesConverter.INSTANCE;
  }

  @Override
  public SchemaAndTablePropertiesConverter schemaAndTablePropertiesConverter() {
    return SqlServerPropertiesConverter.INSTANCE;
  }

  @Override
  public String factoryIdentifier() {
    return ExtJdbcCatalogFactoryOptions.SQLSERVER_IDENTIFIER;
  }

  @Override
  protected Catalog newCatalog(
      Context context,
      String defaultDatabase,
      SchemaAndTablePropertiesConverter schemaAndTablePropertiesConverter,
      PartitionConverter partitionConverter) {
    return new GravitinoSqlServerJdbcCatalog(
        context, defaultDatabase, schemaAndTablePropertiesConverter, partitionConverter);
  }
}
