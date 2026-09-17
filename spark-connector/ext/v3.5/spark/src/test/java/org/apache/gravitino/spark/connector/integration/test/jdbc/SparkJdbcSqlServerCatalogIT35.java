/*
 * Copyright 2026 Datastrato Inc.
 */
package org.apache.gravitino.spark.connector.integration.test.jdbc;

import com.datastrato.gravitino.spark.connector.jdbc.sqlserver.GravitinoSqlServerCatalogSpark35;
import java.util.List;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.DisabledIfEnvironmentVariable;

@DisabledIfEnvironmentVariable(named = "PLATFORM", matches = "linux/arm64")
public class SparkJdbcSqlServerCatalogIT35 extends SparkJdbcSqlServerCatalogIT {

  @Test
  void testCatalogClassName() {
    String catalogClass =
        getSparkSession()
            .sessionState()
            .conf()
            .getConfString("spark.sql.catalog." + getCatalogName());
    Assertions.assertEquals(GravitinoSqlServerCatalogSpark35.class.getName(), catalogClass);
  }

  @Test
  void testCreateTableWithTimestampNtzColumn() {
    // Spark's TimestampNTZType must also be creatable on a SQL Server catalog, alongside the
    // classic TIMESTAMP keyword covered by testCreateTableWithTimestampColumn.
    String tableName = "timestamp_ntz_column_test";
    dropTableIfExists(tableName);
    sql(String.format("CREATE TABLE %s (id INT, created_at TIMESTAMP_NTZ)", tableName));
    sql(String.format("INSERT INTO %s VALUES (1, TIMESTAMP_NTZ '2026-01-01 08:00:00')", tableName));

    List<String> result = getQueryData(String.format("SELECT id FROM %s WHERE id = 1", tableName));
    Assertions.assertEquals(1, result.size());
    Assertions.assertEquals("1", result.get(0));
  }
}
