/*
 * Copyright 2026 Datastrato Inc.
 */
package com.datastrato.gravitino.spark.connector.jdbc.oracle;

import org.apache.gravitino.spark.connector.SparkTableChangeConverter;
import org.apache.gravitino.spark.connector.SparkTableChangeConverter34;
import org.apache.gravitino.spark.connector.SparkTypeConverter;

/** Oracle catalog for Spark 3.4. */
public class GravitinoOracleCatalogSpark34 extends GravitinoOracleJdbcCatalog {

  @Override
  protected SparkTypeConverter getSparkTypeConverter() {
    return new SparkOracleTypeConverter34();
  }

  @Override
  protected SparkTableChangeConverter getSparkTableChangeConverter(
      SparkTypeConverter sparkTypeConverter) {
    return new SparkTableChangeConverter34(sparkTypeConverter);
  }
}
