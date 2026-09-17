/*
 * Copyright 2026 Datastrato Inc.
 */
package com.datastrato.gravitino.spark.connector.jdbc.sqlserver;

import org.apache.gravitino.spark.connector.PropertiesConverter;
import org.apache.gravitino.spark.connector.SparkTypeConverter;
import org.apache.gravitino.spark.connector.jdbc.GravitinoJdbcCatalogSpark33;
import org.apache.gravitino.spark.connector.jdbc.JdbcPropertiesConverter;

/** SQL Server catalog for Spark 3.3. */
public class GravitinoSqlServerCatalogSpark33 extends GravitinoJdbcCatalogSpark33 {
  @Override
  protected PropertiesConverter getPropertiesConverter() {
    return JdbcPropertiesConverter.getNoTablePropertiesInstance();
  }

  @Override
  protected SparkTypeConverter getSparkTypeConverter() {
    return new SparkSqlServerTypeConverter();
  }
}
