/*
 * Copyright 2026 Datastrato Inc.
 */
package org.apache.gravitino.spark.connector.integration.test.jdbc;

import com.datastrato.gravitino.spark.connector.jdbc.oracle.GravitinoOracleCatalogSpark33;
import org.apache.gravitino.spark.connector.integration.test.SparkJdbcOracleCatalogIT;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

public class SparkJdbcOracleCatalogIT33 extends SparkJdbcOracleCatalogIT {

  @Test
  void testCatalogClassName() {
    String catalogClass =
        getSparkSession()
            .sessionState()
            .conf()
            .getConfString("spark.sql.catalog." + getCatalogName());
    Assertions.assertEquals(GravitinoOracleCatalogSpark33.class.getName(), catalogClass);
  }
}
