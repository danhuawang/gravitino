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

package org.apache.gravitino.flink.connector.integration.test.jdbc;

import static org.apache.gravitino.rel.expressions.transforms.Transforms.EMPTY_TRANSFORM;

import com.google.common.base.Preconditions;
import com.google.common.collect.ImmutableMap;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.Duration;
import java.util.Map;
import org.apache.flink.table.api.ResultKind;
import org.apache.flink.table.api.TableResult;
import org.apache.flink.table.catalog.ObjectPath;
import org.apache.flink.types.Row;
import org.apache.gravitino.Catalog;
import org.apache.gravitino.NameIdentifier;
import org.apache.gravitino.credential.CredentialConstants;
import org.apache.gravitino.credential.JdbcCredential;
import org.apache.gravitino.flink.connector.integration.test.FlinkCommonIT;
import org.apache.gravitino.flink.connector.integration.test.utils.TestUtils;
import org.apache.gravitino.flink.connector.jdbc.JdbcPropertiesConstants;
import org.apache.gravitino.rel.Column;
import org.apache.gravitino.rel.Table;
import org.apache.gravitino.rel.expressions.literals.Literals;
import org.apache.gravitino.rel.indexes.Index;
import org.apache.gravitino.rel.indexes.Indexes;
import org.apache.gravitino.rel.types.Types;
import org.awaitility.Awaitility;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIf;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.wait.strategy.Wait;
import org.testcontainers.utility.DockerImageName;

/** Integration tests for {@code gravitino-jdbc-sqlserver} Flink catalog. */
@Tag("gravitino-docker-test")
public abstract class FlinkJdbcSqlServerCatalogIT extends FlinkCommonIT {

  private static final String SQLSERVER_IMAGE = "mcr.microsoft.com/mssql/server:2022-latest";
  private static final int SQLSERVER_PORT = 1433;
  protected static final String USERNAME = "sa";
  private static final String PASSWORD = "YourStrong!Passw0rd";
  private static final String DRIVER = "com.microsoft.sqlserver.jdbc.SQLServerDriver";
  private static final String CATALOG_NAME = "test_flink_sqlserver_jdbc_catalog";
  private static final String SQL_CATALOG_NAME = "gravitino_sqlserver_jdbc_catalog";
  private static final String SQL_CATALOG_WITH_DRIVER_NAME =
      "gravitino_sqlserver_jdbc_catalog_with_driver";
  private static final String FLINK_BYPASS_DEFAULT_DATABASE = "flink.bypass.default-database";

  protected String sqlServerBaseUrl;
  protected String sqlServerJdbcUrl;
  protected String sqlServerDatabase = "flink_sqlserver_it";
  protected String sqlServerDefaultSchema = "dbo";
  protected Catalog catalog;

  @SuppressWarnings("resource")
  private GenericContainer<?> sqlServerContainer;

  @Override
  protected boolean supportTablePropertiesOperation() {
    return false;
  }

  @Override
  protected boolean supportSchemaOperationWithCommentAndOptions() {
    return false;
  }

  @Override
  protected String defaultDatabaseName() {
    return sqlServerDefaultSchema;
  }

  @Override
  protected Catalog currentCatalog() {
    return catalog;
  }

  @Override
  protected String getProvider() {
    return "jdbc-sqlserver";
  }

  @Override
  protected boolean supportDropCascade() {
    return false;
  }

  @Override
  protected boolean defaultValueWithNullLiterals() {
    // SQL Server distinguishes a missing DEFAULT constraint from an explicit DEFAULT NULL.
    return false;
  }

  @BeforeAll
  void jdbcStartup() {
    init();
  }

  @AfterAll
  void jdbcStop() {
    Preconditions.checkArgument(metalake != null, "metalake is null");
    metalake.dropCatalog(CATALOG_NAME, true);
    metalake.dropCatalog(SQL_CATALOG_NAME, true);
    metalake.dropCatalog(SQL_CATALOG_WITH_DRIVER_NAME, true);
  }

  private void init() {
    Preconditions.checkArgument(metalake != null, "metalake is null");
    catalog =
        metalake.createCatalog(
            CATALOG_NAME,
            org.apache.gravitino.Catalog.Type.RELATIONAL,
            getProvider(),
            null,
            ImmutableMap.<String, String>builder()
                .put(JdbcPropertiesConstants.GRAVITINO_JDBC_USER, USERNAME)
                .put(JdbcPropertiesConstants.GRAVITINO_JDBC_PASSWORD, PASSWORD)
                .put(JdbcPropertiesConstants.GRAVITINO_JDBC_URL, sqlServerJdbcUrl)
                .put(JdbcPropertiesConstants.GRAVITINO_JDBC_DRIVER, DRIVER)
                .put(JdbcPropertiesConstants.GRAVITINO_JDBC_DATABASE, sqlServerDatabase)
                .put(FLINK_BYPASS_DEFAULT_DATABASE, sqlServerDefaultSchema)
                .put(CredentialConstants.CREDENTIAL_PROVIDERS, JdbcCredential.JDBC_CREDENTIAL_TYPE)
                .build());
  }

  @Override
  protected void initCatalogEnv() throws Exception {
    sqlServerContainer =
        new GenericContainer<>(DockerImageName.parse(SQLSERVER_IMAGE))
            .withExposedPorts(SQLSERVER_PORT)
            .withEnv("ACCEPT_EULA", "Y")
            .withEnv("MSSQL_SA_PASSWORD", PASSWORD)
            .waitingFor(
                Wait.forLogMessage(".*SQL Server is now ready for client connections.*", 1)
                    .withStartupTimeout(Duration.ofSeconds(120)));
    sqlServerContainer.start();

    sqlServerBaseUrl =
        String.format(
            "jdbc:sqlserver://%s:%d;encrypt=true;trustServerCertificate=true",
            sqlServerContainer.getHost(), sqlServerContainer.getMappedPort(SQLSERVER_PORT));

    Awaitility.await()
        .atMost(Duration.ofSeconds(60))
        .pollInterval(Duration.ofSeconds(2))
        .pollDelay(Duration.ofSeconds(5))
        .until(
            () -> {
              try (Connection conn =
                  DriverManager.getConnection(sqlServerBaseUrl, USERNAME, PASSWORD)) {
                return true;
              } catch (SQLException e) {
                return false;
              }
            });

    try (Connection conn = DriverManager.getConnection(sqlServerBaseUrl, USERNAME, PASSWORD);
        Statement stmt = conn.createStatement()) {
      stmt.execute("CREATE DATABASE [" + sqlServerDatabase + "]");
    }
    sqlServerJdbcUrl =
        String.format(
            "jdbc:sqlserver://%s:%d;databaseName=%s;encrypt=true;trustServerCertificate=true",
            sqlServerContainer.getHost(),
            sqlServerContainer.getMappedPort(SQLSERVER_PORT),
            sqlServerDatabase);
  }

  @Override
  protected void stopCatalogEnv() throws Exception {
    if (sqlServerContainer != null) {
      sqlServerContainer.stop();
    }
  }

  @Override
  @Test
  @EnabledIf("supportTableOperation")
  public void testCreateSimpleTable() {
    String databaseName = "test_create_no_partition_table_db";
    String tableName = "test_create_no_partition_table";
    String comment = "test comment";

    doWithSchema(
        currentCatalog(),
        databaseName,
        catalog -> {
          TableResult result =
              sql(
                  "CREATE TABLE %s "
                      + "(string_type STRING COMMENT 'string_type', "
                      + " double_type DOUBLE COMMENT 'double_type')"
                      + " COMMENT '%s'",
                  tableName, comment);
          TestUtils.assertTableResult(result, ResultKind.SUCCESS);

          Table table =
              catalog.asTableCatalog().loadTable(NameIdentifier.of(databaseName, tableName));
          Assertions.assertNotNull(table);
          Assertions.assertEquals(comment, table.comment());
          Column[] columns =
              new Column[] {
                Column.of("string_type", Types.StringType.get(), "string_type", true, false, null),
                Column.of("double_type", Types.DoubleType.get(), "double_type")
              };
          assertColumns(columns, table.columns());
          Assertions.assertArrayEquals(EMPTY_TRANSFORM, table.partitioning());

          TestUtils.assertTableResult(
              sql("INSERT INTO %s VALUES ('A', 1.0), ('B', 2.0)", tableName),
              ResultKind.SUCCESS_WITH_CONTENT,
              Row.of(-1L));
          TestUtils.assertTableResult(
              sql("SELECT * FROM %s ORDER BY string_type", tableName),
              ResultKind.SUCCESS_WITH_CONTENT,
              Row.of("A", 1.0),
              Row.of("B", 2.0));
        },
        true,
        supportDropCascade());
  }

  @Override
  @Test
  @EnabledIf("supportsPrimaryKey")
  public void testCreateTableWithPrimaryKey() {
    String databaseName = "test_create_table_with_primary_key_db";
    String tableName = "test_create_primary_key_table";
    String comment = "test comment";

    doWithSchema(
        currentCatalog(),
        databaseName,
        catalog -> {
          sql(
              "CREATE TABLE %s "
                  + "(aa int, bb int, cc int, PRIMARY KEY (aa, bb) NOT ENFORCED)"
                  + " COMMENT '%s'",
              tableName, comment);
          Table table =
              catalog.asTableCatalog().loadTable(NameIdentifier.of(databaseName, tableName));
          Assertions.assertEquals(1, table.index().length);
          Index index = table.index()[0];
          Assertions.assertEquals("aa", index.fieldNames()[0][0]);
          Assertions.assertEquals("bb", index.fieldNames()[1][0]);

          TestUtils.assertTableResult(
              sql("INSERT INTO %s VALUES(1, 2, 3)", tableName),
              ResultKind.SUCCESS_WITH_CONTENT,
              Row.of(-1));
          TestUtils.assertTableResult(
              sql("SELECT * FROM %s", tableName), ResultKind.SUCCESS_WITH_CONTENT, Row.of(1, 2, 3));
        },
        true,
        supportDropCascade());
  }

  @Override
  @Test
  @EnabledIf("supportTableOperation")
  public void testGetSimpleTable() {
    String databaseName = "test_get_simple_table";
    Column[] columns =
        new Column[] {
          Column.of("string_type", Types.StringType.get(), "string_type", true, false, null),
          Column.of("double_type", Types.DoubleType.get(), "double_type")
        };

    doWithSchema(
        currentCatalog(),
        databaseName,
        catalog -> {
          String tableName = "test_desc_table";
          String comment = "comment1";
          catalog
              .asTableCatalog()
              .createTable(
                  NameIdentifier.of(databaseName, tableName), columns, comment, ImmutableMap.of());
          Assertions.assertTrue(tableEnv.getCatalog(catalog.name()).isPresent());
          Assertions.assertNotNull(
              Assertions.assertDoesNotThrow(
                  () ->
                      tableEnv
                          .getCatalog(catalog.name())
                          .get()
                          .getTable(new ObjectPath(databaseName, tableName))));
        },
        true,
        supportDropCascade());
  }

  @Override
  @Test
  @EnabledIf("supportColumnOperation")
  public void testAlterTableAddColumn() {
    String databaseName = "test_alter_table_add_column_db";
    String tableName = "test_alter_table_add_column";
    doWithSchema(
        currentCatalog(),
        databaseName,
        catalog -> {
          TestUtils.assertTableResult(
              sql(
                  "CREATE TABLE %s "
                      + "(user_id INT COMMENT 'USER_ID', order_amount INT COMMENT 'ORDER_AMOUNT')"
                      + " COMMENT 'test comment'",
                  tableName),
              ResultKind.SUCCESS);
          TestUtils.assertTableResult(
              sql("ALTER TABLE %s ADD new_column_2 INT", tableName), ResultKind.SUCCESS);
          Column[] actual =
              catalog
                  .asTableCatalog()
                  .loadTable(NameIdentifier.of(databaseName, tableName))
                  .columns();
          Column[] expected =
              new Column[] {
                Column.of("user_id", Types.IntegerType.get(), "USER_ID"),
                Column.of("order_amount", Types.IntegerType.get(), "ORDER_AMOUNT"),
                Column.of("new_column_2", Types.IntegerType.get(), null),
              };
          assertColumns(expected, actual);
        },
        true,
        supportDropCascade());
  }

  @Override
  @Test
  @EnabledIf("supportColumnOperation")
  public void testAlterColumnTypeAndChangeOrder() {
    String databaseName = "test_alter_table_alter_column_db";
    String tableName = "test_alter_table_rename_column";
    doWithSchema(
        currentCatalog(),
        databaseName,
        catalog -> {
          TestUtils.assertTableResult(
              sql(
                  "CREATE TABLE %s "
                      + "(user_id BIGINT COMMENT 'USER_ID', order_amount INT COMMENT 'ORDER_AMOUNT')"
                      + " COMMENT 'test comment'",
                  tableName),
              ResultKind.SUCCESS);
          TestUtils.assertTableResult(
              sql("ALTER TABLE %s MODIFY order_amount BIGINT COMMENT 'new comment2'", tableName),
              ResultKind.SUCCESS);
          TestUtils.assertTableResult(
              sql("ALTER TABLE %s MODIFY user_id BIGINT COMMENT 'new comment'", tableName),
              ResultKind.SUCCESS);
          Column[] actual =
              catalog
                  .asTableCatalog()
                  .loadTable(NameIdentifier.of(databaseName, tableName))
                  .columns();
          Column[] expected =
              new Column[] {
                Column.of("user_id", Types.LongType.get(), "new comment"),
                Column.of("order_amount", Types.LongType.get(), "new comment2")
              };
          assertColumns(expected, actual);
        },
        true,
        supportDropCascade());
  }

  @Test
  public void testCreateGravitinoJdbcCatalogUsingSQL() {
    tableEnv.useCatalog(DEFAULT_CATALOG);
    int numCatalogs = tableEnv.listCatalogs().length;
    String catalogName = SQL_CATALOG_NAME;
    tableEnv.executeSql(
        String.format(
            "create catalog %s with ("
                + "'type'='gravitino-jdbc-sqlserver', "
                + "'base-url'='%s',"
                + "'jdbc-database'='%s',"
                + "'username'='%s',"
                + "'password'='%s',"
                + "'default-database'='%s'"
                + ")",
            catalogName,
            sqlServerBaseUrl,
            sqlServerDatabase,
            USERNAME,
            PASSWORD,
            sqlServerDefaultSchema));
    String[] catalogs = tableEnv.listCatalogs();
    Assertions.assertEquals(numCatalogs + 1, catalogs.length, "Should create a new catalog");
    Assertions.assertTrue(metalake.catalogExists(catalogName));
    org.apache.gravitino.Catalog gravitinoCatalog = metalake.loadCatalog(catalogName);
    Map<String, String> properties = gravitinoCatalog.properties();
    Assertions.assertEquals(
        sqlServerJdbcUrl, properties.get(JdbcPropertiesConstants.GRAVITINO_JDBC_URL));
    Assertions.assertEquals(
        sqlServerDatabase, properties.get(JdbcPropertiesConstants.GRAVITINO_JDBC_DATABASE));
    // jdbc-user and jdbc-password are hidden and returned as masked placeholders.
    Assertions.assertEquals(
        org.apache.gravitino.connector.HiddenPropertyMaskUtils.MASKED_VALUE,
        properties.get(JdbcPropertiesConstants.GRAVITINO_JDBC_USER));
    Assertions.assertEquals(
        org.apache.gravitino.connector.HiddenPropertyMaskUtils.MASKED_VALUE,
        properties.get(JdbcPropertiesConstants.GRAVITINO_JDBC_PASSWORD));
    Assertions.assertEquals(DRIVER, properties.get(JdbcPropertiesConstants.GRAVITINO_JDBC_DRIVER));
  }

  @Test
  public void testCreateGravitinoJdbcCatalogUsingSQLWithDriver() {
    tableEnv.useCatalog(DEFAULT_CATALOG);
    String catalogName = SQL_CATALOG_WITH_DRIVER_NAME;
    tableEnv.executeSql(
        String.format(
            "create catalog %s with ("
                + "'type'='gravitino-jdbc-sqlserver', "
                + "'base-url'='%s',"
                + "'jdbc-database'='%s',"
                + "'username'='%s',"
                + "'password'='%s',"
                + "'driver'='%s',"
                + "'default-database'='%s'"
                + ")",
            catalogName,
            sqlServerBaseUrl,
            sqlServerDatabase,
            USERNAME,
            PASSWORD,
            DRIVER,
            sqlServerDefaultSchema));
    org.apache.gravitino.Catalog gravitinoCatalog = metalake.loadCatalog(catalogName);
    Assertions.assertEquals(
        DRIVER, gravitinoCatalog.properties().get(JdbcPropertiesConstants.GRAVITINO_JDBC_DRIVER));
  }

  @Test
  public void testCreateGravitinoJdbcCatalogUsingSQLMissingOptions() {
    tableEnv.useCatalog(DEFAULT_CATALOG);
    String catalogName = "gravitino_sqlserver_jdbc_catalog_missing_options";
    Assertions.assertThrows(
        IllegalArgumentException.class,
        () ->
            tableEnv.executeSql(
                String.format(
                    "create catalog %s with ("
                        + "'type'='gravitino-jdbc-sqlserver', "
                        + "'base-url'='%s',"
                        + "'jdbc-database'='%s',"
                        + "'username'='%s',"
                        + "'default-database'='%s'"
                        + ")",
                    catalogName,
                    sqlServerBaseUrl,
                    sqlServerDatabase,
                    USERNAME,
                    sqlServerDefaultSchema)));
  }

  @Test
  public void testDefaultValues() {
    String databaseName = "test_sqlserver_defaults";
    String tableName = "test_default_values";
    doWithSchema(
        currentCatalog(),
        databaseName,
        catalog -> {
          Column[] columns =
              new Column[] {
                Column.of(
                    "col_int",
                    Types.IntegerType.get(),
                    null,
                    true,
                    false,
                    Literals.integerLiteral(42)),
                Column.of(
                    "col_str",
                    Types.VarCharType.of(100),
                    null,
                    true,
                    false,
                    Literals.varcharLiteral(100, "hello")),
                Column.of("col_null", Types.StringType.get(), null, true, false, Literals.NULL)
              };
          catalog
              .asTableCatalog()
              .createTable(
                  NameIdentifier.of(databaseName, tableName), columns, null, ImmutableMap.of());
          Table loaded =
              catalog.asTableCatalog().loadTable(NameIdentifier.of(databaseName, tableName));
          Assertions.assertEquals(Literals.integerLiteral(42), loaded.columns()[0].defaultValue());
          Assertions.assertEquals(
              Literals.varcharLiteral(100, "hello"), loaded.columns()[1].defaultValue());
          Assertions.assertEquals(Literals.NULL, loaded.columns()[2].defaultValue());
        },
        true,
        supportDropCascade());
  }

  @Test
  public void testIdentityColumnAndUniqueIndex() {
    String databaseName = "test_sqlserver_identity";
    String tableName = "test_identity_unique";
    doWithSchema(
        currentCatalog(),
        databaseName,
        catalog -> {
          Column id = Column.of("id", Types.IntegerType.get(), "id", false, true, null);
          Column email = Column.of("email", Types.VarCharType.of(100), "email", false, false, null);
          Index[] indexes =
              new Index[] {
                Indexes.primary("pk_identity", new String[][] {{"id"}}),
                Indexes.unique("uq_email", new String[][] {{"email"}})
              };
          catalog
              .asTableCatalog()
              .createTable(
                  NameIdentifier.of(databaseName, tableName),
                  new Column[] {id, email},
                  "identity unique",
                  ImmutableMap.of(),
                  EMPTY_TRANSFORM,
                  org.apache.gravitino.rel.expressions.distributions.Distributions.NONE,
                  new org.apache.gravitino.rel.expressions.sorts.SortOrder[0],
                  indexes);
          Table loaded =
              catalog.asTableCatalog().loadTable(NameIdentifier.of(databaseName, tableName));
          Assertions.assertTrue(loaded.columns()[0].autoIncrement());
          Assertions.assertEquals(2, loaded.index().length);
        },
        true,
        supportDropCascade());
  }

  @Test
  public void testSchemaCommentRejected() {
    doWithCatalog(
        currentCatalog(),
        catalog ->
            Assertions.assertThrows(
                Exception.class,
                () -> sql("CREATE DATABASE test_schema_comment COMMENT 'comment'")));
  }

  @Test
  public void testReservedSchemaRejected() {
    doWithCatalog(
        currentCatalog(),
        catalog -> Assertions.assertThrows(Exception.class, () -> sql("CREATE DATABASE sys")));
  }
}
