/*
 * Copyright 2026 Datastrato Inc.
 */
package org.apache.gravitino.spark.connector.integration.test.jdbc;

import com.datastrato.gravitino.spark.connector.jdbc.oracle.GravitinoOracleCatalogSpark35;
import org.apache.gravitino.spark.connector.integration.test.SparkJdbcOracleCatalogIT;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

public class SparkJdbcOracleCatalogIT35 extends SparkJdbcOracleCatalogIT {

  @Test
  void testCatalogClassName() {
    String catalogClass =
        getSparkSession()
            .sessionState()
            .conf()
            .getConfString("spark.sql.catalog." + getCatalogName());
    Assertions.assertEquals(GravitinoOracleCatalogSpark35.class.getName(), catalogClass);
  }
}
