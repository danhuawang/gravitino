/*
 * Copyright 2026 Datastrato Inc.
 */
package com.datastrato.gravitino.spark.connector.jdbc.oracle;

import java.util.Set;
import org.apache.gravitino.spark.connector.SparkTableChangeConverter;
import org.apache.gravitino.spark.connector.SparkTableChangeConverter34;
import org.apache.gravitino.spark.connector.SparkTypeConverter;
import org.apache.spark.sql.catalyst.analysis.NoSuchTableException;
import org.apache.spark.sql.connector.catalog.Identifier;
import org.apache.spark.sql.connector.catalog.Table;
import org.apache.spark.sql.connector.catalog.TableWritePrivilege;

/** Oracle catalog for Spark 3.5. */
public class GravitinoOracleCatalogSpark35 extends GravitinoOracleJdbcCatalog {

  @Override
  protected SparkTypeConverter getSparkTypeConverter() {
    return new SparkOracleTypeConverter34();
  }

  @Override
  protected SparkTableChangeConverter getSparkTableChangeConverter(
      SparkTypeConverter sparkTypeConverter) {
    return new SparkTableChangeConverter34(sparkTypeConverter);
  }

  @Override
  public Table loadTable(Identifier ident, Set<TableWritePrivilege> writePrivileges)
      throws NoSuchTableException {
    return loadTableForWriting(ident);
  }
}
