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

import static org.apache.gravitino.integration.test.util.TestDatabaseName.SQLSERVER_CATALOG_SQLSERVER_IT;

import com.google.common.collect.Maps;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;
import org.apache.gravitino.integration.test.container.ContainerSuite;
import org.apache.gravitino.integration.test.container.SqlServerContainer;
import org.apache.gravitino.spark.connector.integration.test.SparkCommonIT;
import org.apache.gravitino.spark.connector.integration.test.util.SparkTableInfoChecker;
import org.apache.gravitino.spark.connector.jdbc.JdbcPropertiesConstants;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.DisabledIfEnvironmentVariable;

@Tag("gravitino-docker-test")
// mcr.microsoft.com/mssql/server only supports linux/amd64
@DisabledIfEnvironmentVariable(named = "PLATFORM", matches = "linux/arm64")
public class SparkJdbcSqlServerCatalogIT extends SparkCommonIT {

  private String mssqlUrl;
  private String mssqlUsername;
  private String mssqlPassword;
  private String mssqlDriver;

  @Override
  protected boolean supportsSparkSQLClusteredBy() {
    return false;
  }

  @Override
  protected boolean supportsPartition() {
    return false;
  }

  @Override
  protected boolean supportsDelete() {
    return false;
  }

  @Override
  protected boolean supportsSchemaEvolution() {
    return false;
  }

  @Override
  protected boolean supportsReplaceColumns() {
    return false;
  }

  @Override
  protected boolean supportsSchemaAndTableProperties() {
    return false;
  }

  @Override
  protected boolean supportsComplexType() {
    return false;
  }

  @Override
  protected boolean supportsUpdateColumnPosition() {
    return false;
  }

  @Override
  protected boolean supportsCreateTableWithComment() {
    return false;
  }

  @Override
  protected boolean supportsFunction() {
    return false;
  }

  @Override
  protected Set<String> getDatabases() {
    // dbo is a built-in SQL Server schema that cannot be dropped; exclude it from cleanup
    return super.getDatabases().stream()
        .filter(db -> !db.equals("dbo"))
        .collect(Collectors.toSet());
  }

  @Override
  protected String getCatalogName() {
    return "jdbc_sqlserver";
  }

  @Override
  protected String getProvider() {
    return "jdbc-sqlserver";
  }

  @Override
  protected SparkTableInfoChecker getTableInfoChecker() {
    return SparkJdbcTableInfoChecker.create();
  }

  @Override
  protected void initCatalogEnv() throws Exception {
    ContainerSuite containerSuite = ContainerSuite.getInstance();
    containerSuite.startSqlServerContainer(SQLSERVER_CATALOG_SQLSERVER_IT);
    SqlServerContainer c = containerSuite.getSqlServerContainer();
    this.mssqlUrl = c.getJdbcUrl(SQLSERVER_CATALOG_SQLSERVER_IT);
    this.mssqlUsername = c.getUsername();
    this.mssqlPassword = c.getPassword();
    this.mssqlDriver = c.getDriverClassName(SQLSERVER_CATALOG_SQLSERVER_IT);
  }

  @Override
  protected Map<String, String> getCatalogConfigs() {
    Map<String, String> catalogProperties = Maps.newHashMap();
    catalogProperties.put(JdbcPropertiesConstants.GRAVITINO_JDBC_URL, this.mssqlUrl);
    catalogProperties.put(JdbcPropertiesConstants.GRAVITINO_JDBC_USER, this.mssqlUsername);
    catalogProperties.put(JdbcPropertiesConstants.GRAVITINO_JDBC_PASSWORD, this.mssqlPassword);
    catalogProperties.put(JdbcPropertiesConstants.GRAVITINO_JDBC_DRIVER, this.mssqlDriver);
    catalogProperties.put(
        JdbcPropertiesConstants.GRAVITINO_JDBC_DATABASE, SQLSERVER_CATALOG_SQLSERVER_IT.toString());
    return catalogProperties;
  }

  @Test
  void testCreateTableWithTimestampColumn() {
    // A `timestamp` column must be creatable via Spark SQL on a SQL Server catalog, since Spark's
    // TimestampType has to be mapped to a Gravitino type that SQL Server's converter accepts.
    String tableName = "timestamp_column_test";
    dropTableIfExists(tableName);
    sql(String.format("CREATE TABLE %s (id INT, created_at TIMESTAMP)", tableName));
    sql(String.format("INSERT INTO %s VALUES (1, TIMESTAMP '2026-01-01 08:00:00')", tableName));

    List<String> result = getQueryData(String.format("SELECT id FROM %s WHERE id = 1", tableName));
    Assertions.assertEquals(1, result.size());
    Assertions.assertEquals("1", result.get(0));
  }
}
