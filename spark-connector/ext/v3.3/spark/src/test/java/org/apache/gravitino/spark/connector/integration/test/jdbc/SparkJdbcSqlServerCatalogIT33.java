/*
 * Copyright 2026 Datastrato Inc.
 */
package org.apache.gravitino.spark.connector.integration.test.jdbc;

import com.datastrato.gravitino.spark.connector.jdbc.sqlserver.GravitinoSqlServerCatalogSpark33;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.DisabledIfEnvironmentVariable;

@DisabledIfEnvironmentVariable(named = "PLATFORM", matches = "linux/arm64")
public class SparkJdbcSqlServerCatalogIT33 extends SparkJdbcSqlServerCatalogIT {

  @Test
  void testCatalogClassName() {
    String catalogClass =
        getSparkSession()
            .sessionState()
            .conf()
            .getConfString("spark.sql.catalog." + getCatalogName());
    Assertions.assertEquals(GravitinoSqlServerCatalogSpark33.class.getName(), catalogClass);
  }
}
