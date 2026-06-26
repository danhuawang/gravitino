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
package org.apache.gravitino.spark.connector.integration.test;

import static org.apache.gravitino.integration.test.util.TestDatabaseName.ORACLE_CATALOG_ORACLE_IT;

import com.google.common.collect.Maps;
import java.io.IOException;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import org.apache.gravitino.integration.test.container.ContainerSuite;
import org.apache.gravitino.integration.test.container.OracleContainer;
import org.apache.gravitino.integration.test.util.ITUtils;
import org.apache.gravitino.spark.connector.integration.test.jdbc.SparkJdbcTableInfoChecker;
import org.apache.gravitino.spark.connector.integration.test.util.SparkTableInfo.SparkColumnInfo;
import org.apache.gravitino.spark.connector.integration.test.util.SparkTableInfoChecker;
import org.apache.gravitino.spark.connector.jdbc.JdbcPropertiesConstants;
import org.apache.spark.sql.types.DataTypes;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Disabled;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

@Tag("gravitino-docker-test")
public abstract class SparkJdbcOracleCatalogIT extends SparkCommonIT {

  private static final String ORACLE_JDBC_DRIVER_URL =
      "https://repo1.maven.org/maven2/com/oracle/database/jdbc/ojdbc8/23.4.0.24.05/ojdbc8-23.4.0.24.05.jar";

  protected String oracleUrl;
  protected String oracleUsername;
  protected String oraclePassword;
  protected String oracleDriver;

  @Override
  protected void setupJdbcDrivers() throws IOException {
    super.setupJdbcDrivers();
    if (!ITUtils.DEPLOY_TEST_MODE.equals(testMode)) {
      return;
    }

    String gravitinoHome = System.getenv("GRAVITINO_HOME");
    String[] oracleDriverDirs = {
      ITUtils.joinPath(gravitinoHome, "catalogs", "jdbc-oracle", "libs")
    };
    String[] oracleDriverUrls = {ORACLE_JDBC_DRIVER_URL};
    downloadJdbcDrivers(oracleDriverUrls, oracleDriverDirs);
    cleanJdbcDriverConflicts(oracleDriverUrls, oracleDriverDirs);
  }

  @Override
  protected boolean supportsFunction() {
    return false;
  }

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
  protected String getCatalogName() {
    return "jdbc_oracle";
  }

  @Override
  protected String getProvider() {
    return "jdbc-oracle";
  }

  // Oracle schema = user (APP_USER). The container pre-creates the GRAVITINO user; we reuse it.
  @Override
  protected String getDefaultDatabase() {
    return OracleContainer.APP_USER;
  }

  // Oracle does not support CREATE USER via Gravitino; the GRAVITINO schema already exists.
  @Override
  protected void createDatabaseIfNotExists(String database, String provider) {}

  // Oracle does not support DROP USER via Gravitino; leave the schema intact.
  @Override
  protected void dropDatabaseIfExists(String database) {}

  @Override
  protected SparkTableInfoChecker getTableInfoChecker() {
    return SparkJdbcTableInfoChecker.create();
  }

  @Override
  protected void initCatalogEnv() throws Exception {
    ContainerSuite containerSuite = ContainerSuite.getInstance();
    containerSuite.startOracleContainer(ORACLE_CATALOG_ORACLE_IT);
    OracleContainer c = containerSuite.getOracleContainer();
    this.oracleUrl = c.getJdbcUrl(ORACLE_CATALOG_ORACLE_IT);
    this.oracleUsername = c.getUsername();
    this.oraclePassword = c.getPassword();
    this.oracleDriver = c.getDriverClassName(ORACLE_CATALOG_ORACLE_IT);
  }

  @Override
  protected Map<String, String> getCatalogConfigs() {
    Map<String, String> p = Maps.newHashMap();
    p.put(JdbcPropertiesConstants.GRAVITINO_JDBC_URL, oracleUrl);
    p.put(JdbcPropertiesConstants.GRAVITINO_JDBC_USER, oracleUsername);
    p.put(JdbcPropertiesConstants.GRAVITINO_JDBC_PASSWORD, oraclePassword);
    p.put(JdbcPropertiesConstants.GRAVITINO_JDBC_DRIVER, oracleDriver);
    // Oracle schema = APP_USER; no GRAVITINO_JDBC_DATABASE needed
    return p;
  }

  // Oracle JDBC metadata does not return column comments, so strip them from expectations.
  @Override
  protected List<SparkColumnInfo> getSimpleTableColumn() {
    return Arrays.asList(
        SparkColumnInfo.of("id", DataTypes.IntegerType, null),
        SparkColumnInfo.of("name", DataTypes.StringType, null),
        SparkColumnInfo.of("age", DataTypes.IntegerType, null));
  }

  @Test
  @Override
  protected void testCreateAndLoadSchema() {
    String schemaName = "TEST_CREATE_SCHEMA";
    createOracleUser(schemaName);
    try {
      Assertions.assertTrue(getDatabases().contains(schemaName));
      Assertions.assertDoesNotThrow(() -> getDatabaseMetadata(schemaName));
    } finally {
      dropOracleUser(schemaName);
    }
  }

  @Test
  @Override
  protected void testAlterSchema() {
    Exception exception =
        Assertions.assertThrows(
            Exception.class,
            () ->
                sql(
                    String.format(
                        "ALTER DATABASE %s SET DBPROPERTIES ('ID'='002')", getDefaultDatabase())));
    Assertions.assertTrue(
        containsMessage(exception, "jdbc-catalog does not support alter the schema"),
        () -> "Expected alter-schema unsupported error, but got: " + exception);
  }

  @Test
  @Override
  protected void testDropSchema() {
    Exception exception =
        Assertions.assertThrows(
            Exception.class, () -> sql(String.format("DROP DATABASE %s", getDefaultDatabase())));
    Assertions.assertTrue(
        containsMessage(exception, "Oracle catalog does not support"),
        () -> "Expected drop-schema unsupported error, but got: " + exception);
  }

  // Oracle cannot create extra schemas, so cross-schema table reference tests are skipped.
  @Test
  @Disabled("Oracle does not support creating schemas dynamically")
  @Override
  void testCreateTableWithDatabase() {}

  // testListTable (singular) creates a secondary schema db_list via createDatabaseIfNotExists,
  // which Oracle does not support; the test is skipped.
  @Test
  @Disabled("Oracle does not support creating schemas dynamically")
  @Override
  protected void testListTable() {}

  // Oracle JDBC metadata does not return column comments, so comment-update assertions fail.
  @Test
  @Disabled("Oracle JDBC metadata does not return column comments")
  @Override
  void testAlterTableUpdateColumnComment() {}

  private void createOracleUser(String schemaName) {
    dropOracleUser(schemaName);
    try (Connection conn = DriverManager.getConnection(oracleUrl, "SYSTEM", oraclePassword);
        Statement stmt = conn.createStatement()) {
      stmt.execute("CREATE USER " + schemaName + " IDENTIFIED BY " + oraclePassword);
      stmt.execute("GRANT CREATE SESSION, RESOURCE, UNLIMITED TABLESPACE TO " + schemaName);
    } catch (SQLException e) {
      throw new RuntimeException("Failed to create Oracle user: " + schemaName, e);
    }
  }

  private void dropOracleUser(String schemaName) {
    try (Connection conn = DriverManager.getConnection(oracleUrl, "SYSTEM", oraclePassword);
        Statement stmt = conn.createStatement()) {
      stmt.execute("DROP USER " + schemaName + " CASCADE");
    } catch (SQLException e) {
      // ORA-01918: user does not exist — expected during pre-test cleanup
      if (e.getErrorCode() != 1918) {
        throw new RuntimeException("Failed to drop Oracle user: " + schemaName, e);
      }
    }
  }

  private boolean containsMessage(Throwable throwable, String message) {
    Throwable current = throwable;
    while (current != null) {
      if (current.getMessage() != null && current.getMessage().contains(message)) {
        return true;
      }
      current = current.getCause();
    }
    return false;
  }
}
