/*
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *
 *  http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing,
 * software distributed under the License is distributed on an
 * "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
 * KIND, either express or implied.  See the License for the
 * specific language governing permissions and limitations
 * under the License.
 */
package org.apache.gravitino.spark.connector.integration.test.jdbc;

import java.util.List;
import org.apache.gravitino.spark.connector.jdbc.sqlserver.GravitinoSqlServerCatalogSpark34;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.DisabledIfEnvironmentVariable;

@DisabledIfEnvironmentVariable(named = "PLATFORM", matches = "linux/arm64")
public class SparkJdbcSqlServerCatalogIT34 extends SparkJdbcSqlServerCatalogIT {

  @Test
  void testCatalogClassName() {
    String catalogClass =
        getSparkSession()
            .sessionState()
            .conf()
            .getConfString("spark.sql.catalog." + getCatalogName());
    Assertions.assertEquals(GravitinoSqlServerCatalogSpark34.class.getName(), catalogClass);
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
