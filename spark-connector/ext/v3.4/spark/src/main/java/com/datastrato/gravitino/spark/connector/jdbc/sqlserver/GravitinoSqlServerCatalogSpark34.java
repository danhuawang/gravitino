/*
 * Copyright 2026 Datastrato Inc.
 */
package com.datastrato.gravitino.spark.connector.jdbc.sqlserver;

import org.apache.gravitino.spark.connector.PropertiesConverter;
import org.apache.gravitino.spark.connector.SparkTypeConverter;
import org.apache.gravitino.spark.connector.jdbc.GravitinoJdbcCatalog;
import org.apache.gravitino.spark.connector.jdbc.JdbcPropertiesConverter;

/** SQL Server catalog for Spark 3.4. */
public class GravitinoSqlServerCatalogSpark34 extends GravitinoJdbcCatalog {
  @Override
  protected PropertiesConverter getPropertiesConverter() {
    return JdbcPropertiesConverter.getNoTablePropertiesInstance();
  }

  @Override
  protected SparkTypeConverter getSparkTypeConverter() {
    return new SparkSqlServerTypeConverter34();
  }
}
