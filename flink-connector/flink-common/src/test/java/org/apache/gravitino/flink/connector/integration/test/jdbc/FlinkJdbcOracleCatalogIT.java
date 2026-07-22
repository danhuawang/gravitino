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

import static org.apache.gravitino.integration.test.util.TestDatabaseName.ORACLE_CATALOG_ORACLE_IT;
import static org.apache.gravitino.rel.expressions.transforms.Transforms.EMPTY_TRANSFORM;
import static org.junit.jupiter.api.Assertions.fail;

import com.google.common.base.Preconditions;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.Lists;
import java.io.IOException;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.function.Consumer;
import java.util.stream.Collectors;
import org.apache.flink.table.api.ResultKind;
import org.apache.flink.table.api.TableResult;
import org.apache.flink.table.catalog.CatalogBaseTable;
import org.apache.flink.table.catalog.CatalogTable;
import org.apache.flink.table.catalog.ObjectPath;
import org.apache.flink.table.catalog.exceptions.TableNotExistException;
import org.apache.flink.types.Row;
import org.apache.gravitino.Catalog;
import org.apache.gravitino.NameIdentifier;
import org.apache.gravitino.flink.connector.integration.test.FlinkCommonIT;
import org.apache.gravitino.flink.connector.integration.test.utils.TestUtils;
import org.apache.gravitino.flink.connector.jdbc.JdbcPropertiesConstants;
import org.apache.gravitino.flink.connector.utils.DefaultCatalogCompat;
import org.apache.gravitino.integration.test.container.ContainerSuite;
import org.apache.gravitino.integration.test.container.OracleContainer;
import org.apache.gravitino.integration.test.util.ITUtils;
import org.apache.gravitino.rel.Column;
import org.apache.gravitino.rel.Table;
import org.apache.gravitino.rel.indexes.Index;
import org.apache.gravitino.rel.types.Types;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIf;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/** Integration tests for {@code gravitino-jdbc-oracle} Flink catalog. */
@Tag("gravitino-docker-test")
public abstract class FlinkJdbcOracleCatalogIT extends FlinkCommonIT {

  private static final Logger LOG = LoggerFactory.getLogger(FlinkJdbcOracleCatalogIT.class);

  protected String oracleUrl;
  protected String oracleUsername;
  protected String oraclePassword;
  protected String oracleDriver;
  protected String oracleDefaultDatabase = OracleContainer.APP_USER;

  protected Catalog catalog;

  protected static final String CATALOG_NAME = "test_flink_oracle_jdbc_catalog";

  private static final String FLINK_BYPASS_DEFAULT_DATABASE = "flink.bypass.default-database";

  private static final String ORACLE_JDBC_DRIVER_URL =
      "https://repo1.maven.org/maven2/com/oracle/database/jdbc/ojdbc8/23.4.0.24.05/ojdbc8-23.4.0.24.05.jar";

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
  protected boolean supportTablePropertiesOperation() {
    return false;
  }

  @Override
  protected String defaultDatabaseName() {
    return OracleContainer.APP_USER;
  }

  @Override
  protected boolean supportSchemaOperationWithCommentAndOptions() {
    return false;
  }

  @Override
  protected boolean supportDropCascade() {
    return false;
  }

  /** Oracle schema lifecycle is not supported because Oracle schemas are database users. */
  @Override
  protected boolean supportsSchemaLifecycle() {
    return false;
  }

  @Override
  protected boolean defaultValueWithNullLiterals() {
    // Oracle's JDBC driver returns DEFAULT_VALUE_NOT_SET (not Literals.NULL) for columns without
    // an explicit DEFAULT clause, so null-literal promotion must be disabled.
    return false;
  }

  @Override
  protected Catalog currentCatalog() {
    return catalog;
  }

  @Override
  protected String getProvider() {
    return "jdbc-oracle";
  }

  @BeforeAll
  void jdbcStartup() {
    init();
  }

  @AfterAll
  void jdbcStop() {
    Preconditions.checkArgument(metalake != null, "metalake is null");
    metalake.dropCatalog(CATALOG_NAME, true);
  }

  private void init() {
    Preconditions.checkArgument(metalake != null, "metalake is null");
    grantPrivilegesToCatalogUser();
    catalog =
        metalake.createCatalog(
            CATALOG_NAME,
            Catalog.Type.RELATIONAL,
            getProvider(),
            null,
            ImmutableMap.<String, String>builder()
                .put(JdbcPropertiesConstants.GRAVITINO_JDBC_USER, oracleUsername)
                .put(JdbcPropertiesConstants.GRAVITINO_JDBC_PASSWORD, oraclePassword)
                .put(JdbcPropertiesConstants.GRAVITINO_JDBC_URL, oracleUrl)
                .put(JdbcPropertiesConstants.GRAVITINO_JDBC_DRIVER, oracleDriver)
                .put(FLINK_BYPASS_DEFAULT_DATABASE, oracleDefaultDatabase)
                .build());
  }

  /**
   * Grants cross-schema privileges to the catalog user so that the Gravitino server can create and
   * manage tables in Oracle schemas created by the test via SYSTEM JDBC.
   */
  private void grantPrivilegesToCatalogUser() {
    try (Connection conn = DriverManager.getConnection(oracleUrl, "SYSTEM", oraclePassword);
        Statement stmt = conn.createStatement()) {
      // DDL privileges for creating and managing tables in other schemas.
      stmt.execute("GRANT CREATE ANY TABLE TO " + oracleUsername);
      stmt.execute("GRANT DROP ANY TABLE TO " + oracleUsername);
      stmt.execute("GRANT ALTER ANY TABLE TO " + oracleUsername);
      stmt.execute("GRANT CREATE ANY INDEX TO " + oracleUsername);
      stmt.execute("GRANT DROP ANY INDEX TO " + oracleUsername);
      stmt.execute("GRANT COMMENT ANY TABLE TO " + oracleUsername);
      // DML privileges for INSERT/SELECT/UPDATE/DELETE in other schemas.
      stmt.execute("GRANT INSERT ANY TABLE TO " + oracleUsername);
      stmt.execute("GRANT SELECT ANY TABLE TO " + oracleUsername);
      stmt.execute("GRANT UPDATE ANY TABLE TO " + oracleUsername);
      stmt.execute("GRANT DELETE ANY TABLE TO " + oracleUsername);
      // Dictionary access for column comments (ALL_COL_COMMENTS) in other schemas.
      stmt.execute("GRANT SELECT_CATALOG_ROLE TO " + oracleUsername);
    } catch (SQLException e) {
      LOG.warn("Failed to grant ANY privileges to {}", oracleUsername, e);
    }
  }

  @Override
  protected void initCatalogEnv() throws Exception {
    ContainerSuite containerSuite = ContainerSuite.getInstance();
    containerSuite.startOracleContainer(ORACLE_CATALOG_ORACLE_IT);
    OracleContainer oracle = containerSuite.getOracleContainer();
    oracleUrl = oracle.getJdbcUrl();
    oracleUsername = oracle.getUsername();
    oraclePassword = oracle.getPassword();
    oracleDriver = oracle.getDriverClassName(ORACLE_CATALOG_ORACLE_IT);
  }

  @Override
  protected void stopCatalogEnv() throws Exception {
    if (null != containerSuite) {
      containerSuite.close();
    }
  }

  /**
   * Oracle cannot create or drop schemas via the Gravitino API (schemas are database users
   * requiring DBA privileges). This override creates the test schema via a SYSTEM JDBC connection
   * and drops it with {@code DROP USER CASCADE} in the finally block, allowing all table-level
   * tests to run against a real Oracle schema.
   */
  @Override
  protected void doWithSchema(
      Catalog catalog,
      String schemaName,
      Consumer<Catalog> action,
      boolean dropSchema,
      boolean cascade) {
    String upperSchema = schemaName.toUpperCase(Locale.ROOT);
    createOracleUser(upperSchema);
    try {
      tableEnv.useCatalog(catalog.name());
      tableEnv.useDatabase(upperSchema);
      action.accept(catalog);
    } finally {
      if (dropSchema) {
        clearTableInSchema();
        dropOracleUser(upperSchema);
      }
    }
  }

  private void createOracleUser(String schemaName) {
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
      LOG.warn("Failed to drop Oracle user {}: {}", schemaName, e.getMessage());
    }
  }

  /**
   * Oracle normalizes unquoted identifiers to uppercase, so table names returned by {@code SHOW
   * TABLES} are uppercase. This override asserts against the normalized names.
   */
  @Override
  @Test
  @EnabledIf("supportTableOperation")
  public void testListTables() {
    String newSchema = "test_list_table_catalog";
    Column[] columns = new Column[] {Column.of("USER_ID", Types.IntegerType.get(), "USER_ID")};
    doWithSchema(
        currentCatalog(),
        newSchema,
        catalog -> {
          catalog
              .asTableCatalog()
              .createTable(
                  NameIdentifier.of(newSchema, "TEST_TABLE1"),
                  columns,
                  "comment1",
                  ImmutableMap.of());
          catalog
              .asTableCatalog()
              .createTable(
                  NameIdentifier.of(newSchema, "TEST_TABLE2"),
                  columns,
                  "comment2",
                  ImmutableMap.of());
          TableResult result = sql("SHOW TABLES");
          TestUtils.assertTableResult(
              result,
              ResultKind.SUCCESS_WITH_CONTENT,
              Row.of("TEST_TABLE1"),
              Row.of("TEST_TABLE2"));
        },
        true,
        supportDropCascade());
  }

  /**
   * Oracle requires uppercase column names for DML compatibility: the Flink JDBC Oracle dialect
   * sends unquoted identifiers, which Oracle normalizes to uppercase. This override creates the
   * table with uppercase column names so that INSERT and SELECT work correctly.
   */
  @Override
  @Test
  @EnabledIf("supportTableOperation")
  public void testCreateSimpleTable() {
    String databaseName = "CREATE_NO_PART_DB";
    String tableName = "TEST_CREATE_NO_PARTITION_TABLE";
    String comment = "test comment";

    doWithSchema(
        currentCatalog(),
        databaseName,
        catalog -> {
          TestUtils.assertTableResult(
              sql(
                  "CREATE TABLE %s "
                      + "(STRING_TYPE STRING COMMENT 'string_type',"
                      + " DOUBLE_TYPE DOUBLE COMMENT 'double_type')"
                      + " COMMENT '%s'",
                  tableName, comment),
              ResultKind.SUCCESS);

          Table table =
              catalog.asTableCatalog().loadTable(NameIdentifier.of(databaseName, tableName));
          Assertions.assertNotNull(table);
          Assertions.assertEquals(comment, table.comment());
          Column[] columns =
              new Column[] {
                Column.of("STRING_TYPE", Types.StringType.get(), "string_type"),
                Column.of("DOUBLE_TYPE", Types.DoubleType.get(), "double_type")
              };
          assertColumns(columns, table.columns());
          Assertions.assertArrayEquals(EMPTY_TRANSFORM, table.partitioning());

          TestUtils.assertTableResult(
              sql("INSERT INTO %s VALUES ('A', 1.0), ('B', 2.0)", tableName),
              ResultKind.SUCCESS_WITH_CONTENT,
              Row.of(-1L));
          TestUtils.assertTableResult(
              sql("SELECT * FROM %s", tableName),
              ResultKind.SUCCESS_WITH_CONTENT,
              Row.of("A", 1.0),
              Row.of("B", 2.0));
        },
        true,
        supportDropCascade());
  }

  /**
   * Oracle requires uppercase column names for DML. This override uses uppercase column names and
   * updates the primary-key field-name assertions accordingly.
   */
  @Override
  @Test
  @EnabledIf("supportsPrimaryKey")
  public void testCreateTableWithPrimaryKey() {
    String databaseName = "CREATE_PK_DB";
    String tableName = "TEST_CREATE_PRIMARY_KEY_TABLE";
    String comment = "test comment";

    doWithSchema(
        currentCatalog(),
        databaseName,
        catalog -> {
          sql(
              "CREATE TABLE %s "
                  + "(AA INT, BB INT, CC INT, PRIMARY KEY (AA, BB) NOT ENFORCED)"
                  + " COMMENT '%s'",
              tableName, comment);

          Table table =
              catalog.asTableCatalog().loadTable(NameIdentifier.of(databaseName, tableName));
          Assertions.assertEquals(1, table.index().length);
          Index index = table.index()[0];
          Assertions.assertEquals("AA", index.fieldNames()[0][0]);
          Assertions.assertEquals("BB", index.fieldNames()[1][0]);

          TestUtils.assertTableResult(
              sql("INSERT INTO %s VALUES(1, 2, 3)", tableName),
              ResultKind.SUCCESS_WITH_CONTENT,
              Row.of(-1));
          TestUtils.assertTableResult(
              sql("SELECT count(*) num FROM %s", tableName),
              ResultKind.SUCCESS_WITH_CONTENT,
              Row.of(1));
          TestUtils.assertTableResult(
              sql("SELECT * FROM %s", tableName), ResultKind.SUCCESS_WITH_CONTENT, Row.of(1, 2, 3));

          TestUtils.assertTableResult(
              sql("INSERT INTO %s VALUES(1, 2, 4)", tableName),
              ResultKind.SUCCESS_WITH_CONTENT,
              Row.of(-1));
          TestUtils.assertTableResult(
              sql("SELECT count(*) num FROM %s", tableName),
              ResultKind.SUCCESS_WITH_CONTENT,
              Row.of(1));
          TestUtils.assertTableResult(
              sql("SELECT * FROM %s", tableName), ResultKind.SUCCESS_WITH_CONTENT, Row.of(1, 2, 4));

          TestUtils.assertTableResult(
              sql("INSERT INTO %s VALUES(1, 3, 4)", tableName),
              ResultKind.SUCCESS_WITH_CONTENT,
              Row.of(-1));
          TestUtils.assertTableResult(
              sql("SELECT count(*) num FROM %s", tableName),
              ResultKind.SUCCESS_WITH_CONTENT,
              Row.of(2));
          // ORDER BY to get deterministic row order; Oracle does not guarantee scan order.
          TestUtils.assertTableResult(
              sql("SELECT * FROM %s ORDER BY BB", tableName),
              ResultKind.SUCCESS_WITH_CONTENT,
              Row.of(1, 2, 4),
              Row.of(1, 3, 4));
        },
        true,
        supportDropCascade());
  }

  /** Verifies that Oracle preserves column comments after a {@code RENAME COLUMN} operation. */
  @Override
  @Test
  @EnabledIf("supportColumnOperation")
  public void testRenameColumn() {
    String databaseName = "test_rename_column_db";
    String tableName = "test_rename_column";
    doWithSchema(
        currentCatalog(),
        databaseName,
        catalog -> {
          TestUtils.assertTableResult(
              sql(
                  "CREATE TABLE %s "
                      + "(user_id INT COMMENT 'USER_ID',"
                      + " order_amount DOUBLE COMMENT 'ORDER_AMOUNT')"
                      + " COMMENT 'test comment'",
                  tableName),
              ResultKind.SUCCESS);
          TestUtils.assertTableResult(
              sql("ALTER TABLE %s RENAME user_id TO user_id_new", tableName), ResultKind.SUCCESS);
          Column[] actual =
              catalog
                  .asTableCatalog()
                  .loadTable(NameIdentifier.of(databaseName, tableName.toUpperCase(Locale.ROOT)))
                  .columns();
          Column[] expected =
              new Column[] {
                Column.of("user_id_new", Types.IntegerType.get(), "USER_ID"),
                Column.of("order_amount", Types.DoubleType.get(), "ORDER_AMOUNT"),
              };
          assertColumns(expected, actual);
        },
        true,
        supportDropCascade());
  }

  /** Uses null column comments to focus this override on Oracle table metadata loading. */
  @Override
  @Test
  @EnabledIf("supportTableOperation")
  public void testGetSimpleTable() {
    String databaseName = "test_get_simple_table";
    Column[] columns =
        new Column[] {
          Column.of("string_type", Types.StringType.get(), null),
          Column.of("double_type", Types.DoubleType.get(), null)
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
                  NameIdentifier.of(databaseName, tableName.toUpperCase(Locale.ROOT)),
                  columns,
                  comment,
                  ImmutableMap.of());
          Optional<org.apache.flink.table.catalog.Catalog> flinkCatalogOpt =
              tableEnv.getCatalog(catalog.name());
          Assertions.assertTrue(flinkCatalogOpt.isPresent());
          try {
            CatalogBaseTable table =
                flinkCatalogOpt.get().getTable(new ObjectPath(databaseName, tableName));
            Assertions.assertNotNull(table);
            Assertions.assertEquals(CatalogBaseTable.TableKind.TABLE, table.getTableKind());
            Assertions.assertEquals(comment, table.getComment());
            Assertions.assertFalse(((CatalogTable) table).isPartitioned());
            // Verify columns via the Gravitino API.
            Table gravitinoTable =
                catalog
                    .asTableCatalog()
                    .loadTable(NameIdentifier.of(databaseName, tableName.toUpperCase(Locale.ROOT)));
            assertColumns(columns, gravitinoTable.columns());
          } catch (TableNotExistException e) {
            fail(e);
          }
        },
        true,
        supportDropCascade());
  }

  /**
   * Oracle schemas are database users created outside Gravitino, so the base-class test (which uses
   * {@code CREATE DATABASE} via Flink SQL) is replaced here with a JDBC-based version that creates
   * the user directly, then verifies Gravitino can read the schema metadata.
   */
  @Override
  @Test
  public void testGetSchemaWithoutCommentAndOption() {
    String schemaName = "TEST_GET_SCHEMA";
    createOracleUser(schemaName);
    try {
      doWithCatalog(
          currentCatalog(),
          catalog -> {
            Assertions.assertTrue(catalog.asSchemas().schemaExists(schemaName));
            // Oracle stores user names in uppercase; comment and properties are always empty.
            Assertions.assertEquals(schemaName, catalog.asSchemas().loadSchema(schemaName).name());
          });
    } finally {
      dropOracleUser(schemaName);
    }
  }

  /**
   * Oracle schema lifecycle is not available via Gravitino API, so the base-class test (which
   * creates schemas via Flink SQL {@code CREATE DATABASE}) is replaced with a JDBC-based version.
   * Only "contains" assertions are used because other Oracle users (e.g. APP_USER) always appear in
   * {@code ALL_USERS}.
   */
  @Override
  @Test
  public void testListSchema() {
    String[] schemaNames = {"TEST_LIST_SCH_1", "TEST_LIST_SCH_2", "TEST_LIST_SCH_3"};
    for (String s : schemaNames) createOracleUser(s);
    try {
      doWithCatalog(
          currentCatalog(),
          catalog -> {
            List<String> shown =
                Lists.newArrayList(sql("SHOW DATABASES").collect()).stream()
                    .map(row -> row.getField(0).toString())
                    .collect(Collectors.toList());
            for (String s : schemaNames) {
              Assertions.assertTrue(
                  shown.contains(s),
                  "Expected schema " + s + " in SHOW DATABASES but got: " + shown);
            }
            List<String> listed = Lists.newArrayList(catalog.asSchemas().listSchemas());
            for (String s : schemaNames) {
              Assertions.assertTrue(
                  listed.contains(s),
                  "Expected schema " + s + " in listSchemas() but got: " + listed);
            }
          });
    } finally {
      for (String s : schemaNames) dropOracleUser(s);
    }
  }

  /** Verifies that SHOW DATABASES lists the Oracle APP_USER schema. */
  @Test
  public void testShowSchemasContainsGravitinoUser() {
    doWithCatalog(
        currentCatalog(),
        catalog -> {
          List<String> schemas =
              Lists.newArrayList(sql("SHOW DATABASES").collect()).stream()
                  .map(row -> row.getField(0).toString().toUpperCase(Locale.ROOT))
                  .collect(Collectors.toList());
          Assertions.assertTrue(
              schemas.contains(oracleDefaultDatabase),
              "Expected schema " + oracleDefaultDatabase + " but got: " + schemas);
        });
  }

  /** Verifies table creation and metadata loading via the Gravitino API on the default schema. */
  @Test
  public void testCreateAndDropTableInDefaultSchema() {
    String tableName = "TEST_ORACLE_CREATE_TABLE";
    doWithCatalog(
        currentCatalog(),
        catalog -> {
          tableEnv.useDatabase(oracleDefaultDatabase);
          try {
            TestUtils.assertTableResult(
                sql(
                    "CREATE TABLE %s "
                        + "(ID INT COMMENT 'id', NAME VARCHAR(100) COMMENT 'name')"
                        + " COMMENT 'oracle table'",
                    tableName),
                ResultKind.SUCCESS);

            NameIdentifier identifier = NameIdentifier.of(oracleDefaultDatabase, tableName);
            Table table = catalog.asTableCatalog().loadTable(identifier);
            Assertions.assertNotNull(table);
            Assertions.assertEquals("oracle table", table.comment());
            Assertions.assertEquals(2, table.columns().length);
          } finally {
            sql("DROP TABLE IF EXISTS %s", tableName);
          }
        });
  }

  /** Verifies INSERT and SELECT through the Flink JDBC Oracle connector. */
  @Test
  public void testInsertAndSelect() {
    String tableName = "TEST_ORACLE_INSERT_SELECT";
    doWithCatalog(
        currentCatalog(),
        catalog -> {
          tableEnv.useDatabase(oracleDefaultDatabase);
          try {
            sql("CREATE TABLE %s (ID INT, VAL INT, PRIMARY KEY (ID) NOT ENFORCED)", tableName);
            TestUtils.assertTableResult(
                sql("INSERT INTO %s VALUES(1, 100)", tableName),
                ResultKind.SUCCESS_WITH_CONTENT,
                Row.of(-1));
            TestUtils.assertTableResult(
                sql("SELECT * FROM %s", tableName),
                ResultKind.SUCCESS_WITH_CONTENT,
                Row.of(1, 100));
          } finally {
            sql("DROP TABLE IF EXISTS %s", tableName);
          }
        });
  }

  /**
   * Oracle: {@code test_alter_table_comment_database} (33 chars) exceeds Gravitino's 30-char
   * identifier limit for Oracle. This override uses a shorter schema name.
   */
  @Override
  @Test
  @EnabledIf("supportColumnOperation")
  public void testAlterTableComment() {
    String databaseName = "ALTER_TBL_COMMENT_DB";
    String tableName = "test_alter_table_comment";
    String newComment = "new_table_comment";
    doWithSchema(
        currentCatalog(),
        databaseName,
        catalog -> {
          Optional<org.apache.flink.table.catalog.Catalog> flinkCatalogOpt =
              tableEnv.getCatalog(currentCatalog().name());
          if (flinkCatalogOpt.isPresent()) {
            org.apache.flink.table.catalog.Catalog flinkCat = flinkCatalogOpt.get();
            ObjectPath tablePath = new ObjectPath(databaseName, tableName);
            try {
              TestUtils.assertTableResult(
                  sql("CREATE TABLE %s (test INT) COMMENT 'test comment'", tableName),
                  ResultKind.SUCCESS);
              CatalogTable table = (CatalogTable) flinkCat.getTable(tablePath);
              flinkCat.alterTable(
                  tablePath,
                  DefaultCatalogCompat.INSTANCE.createCatalogTable(
                      table.getUnresolvedSchema(),
                      newComment,
                      table.getPartitionKeys(),
                      table.getOptions()),
                  false);
              CatalogTable updated = (CatalogTable) flinkCat.getTable(tablePath);
              Assertions.assertEquals(newComment, updated.getComment());
              Table gravitinoTable =
                  currentCatalog()
                      .asTableCatalog()
                      .loadTable(
                          NameIdentifier.of(databaseName, tableName.toUpperCase(Locale.ROOT)));
              Assertions.assertEquals(newComment, gravitinoTable.comment());
            } catch (TableNotExistException e) {
              fail(e);
            }
          } else {
            fail("Catalog doesn't exist");
          }
        },
        true,
        supportDropCascade());
  }

  /**
   * Oracle: {@code test_alter_table_drop_column_db} (31 chars) exceeds Gravitino's 30-char limit.
   * This override uses a shorter schema name.
   */
  @Override
  @Test
  @EnabledIf("supportColumnOperation")
  public void testAlterTableDropColumn() {
    String databaseName = "ALTER_DROP_COL_DB";
    String tableName = "test_alter_table_drop_column";
    doWithSchema(
        currentCatalog(),
        databaseName,
        catalog -> {
          TestUtils.assertTableResult(
              sql(
                  "CREATE TABLE %s "
                      + "(user_id INT COMMENT 'USER_ID',"
                      + " order_amount INT COMMENT 'ORDER_AMOUNT')"
                      + " COMMENT 'test comment'",
                  tableName),
              ResultKind.SUCCESS);
          TestUtils.assertTableResult(
              sql("ALTER TABLE %s DROP user_id", tableName), ResultKind.SUCCESS);
          Column[] actual =
              catalog
                  .asTableCatalog()
                  .loadTable(NameIdentifier.of(databaseName, tableName.toUpperCase(Locale.ROOT)))
                  .columns();
          Column[] expected =
              new Column[] {Column.of("order_amount", Types.IntegerType.get(), "ORDER_AMOUNT")};
          assertColumns(expected, actual);
        },
        true,
        supportDropCascade());
  }

  /**
   * Oracle: (1) {@code test_alter_table_alter_column_db} (32 chars) exceeds Gravitino's 30-char
   * limit so a shorter schema name is used; (2) Oracle does not support reordering columns, so the
   * {@code MODIFY … AFTER} step from the base test is omitted and expected column order is
   * preserved as-created.
   */
  @Override
  @Test
  @EnabledIf("supportColumnOperation")
  public void testAlterColumnTypeAndChangeOrder() {
    String databaseName = "ALTER_COL_TYPE_DB";
    String tableName = "test_alter_table_rename_column";
    doWithSchema(
        currentCatalog(),
        databaseName,
        catalog -> {
          TestUtils.assertTableResult(
              sql(
                  "CREATE TABLE %s "
                      + "(user_id BIGINT COMMENT 'USER_ID',"
                      + " order_amount INT COMMENT 'ORDER_AMOUNT')"
                      + " COMMENT 'test comment'",
                  tableName),
              ResultKind.SUCCESS);
          TestUtils.assertTableResult(
              sql("ALTER TABLE %s MODIFY order_amount INT COMMENT 'new comment2'", tableName),
              ResultKind.SUCCESS);
          TestUtils.assertTableResult(
              sql("ALTER TABLE %s MODIFY order_amount BIGINT COMMENT 'new comment2'", tableName),
              ResultKind.SUCCESS);
          TestUtils.assertTableResult(
              sql("ALTER TABLE %s MODIFY user_id BIGINT COMMENT 'new comment'", tableName),
              ResultKind.SUCCESS);
          // Oracle does not support column reordering (MODIFY … AFTER) — skip that step.
          Column[] actual =
              catalog
                  .asTableCatalog()
                  .loadTable(NameIdentifier.of(databaseName, tableName.toUpperCase(Locale.ROOT)))
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

  /**
   * Oracle does not support {@code ADD COLUMN … AFTER other_col}. This override adds the new column
   * without specifying a position (Oracle always appends to the end).
   */
  @Override
  @Test
  @EnabledIf("supportColumnOperation")
  public void testAlterTableAddColumn() {
    String databaseName = "ALTER_ADD_COL_DB";
    String tableName = "test_alter_table_add_column";
    doWithSchema(
        currentCatalog(),
        databaseName,
        catalog -> {
          TestUtils.assertTableResult(
              sql(
                  "CREATE TABLE %s "
                      + "(user_id INT COMMENT 'USER_ID',"
                      + " order_amount INT COMMENT 'ORDER_AMOUNT')"
                      + " COMMENT 'test comment'",
                  tableName),
              ResultKind.SUCCESS);
          // Oracle does not support ADD COLUMN AFTER — omit the position clause.
          TestUtils.assertTableResult(
              sql("ALTER TABLE %s ADD new_column_2 INT", tableName), ResultKind.SUCCESS);
          Column[] actual =
              catalog
                  .asTableCatalog()
                  .loadTable(NameIdentifier.of(databaseName, tableName.toUpperCase(Locale.ROOT)))
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

  /**
   * Oracle stores unquoted identifiers in uppercase. The base-class test uses lowercase names in
   * direct Gravitino API calls, which would miss the uppercase table. Override to use uppercase
   * identifiers so that direct API calls and Flink SQL agree on the same physical object.
   */
  @Override
  @Test
  @EnabledIf("supportTableOperation")
  public void testDropTable() {
    String databaseName = "test_drop_table_db";
    doWithSchema(
        currentCatalog(),
        databaseName,
        catalog -> {
          String tableName = "test_drop_table";
          Column[] columns =
              new Column[] {Column.of("user_id", Types.IntegerType.get(), "USER_ID")};
          NameIdentifier identifier =
              NameIdentifier.of(databaseName, tableName.toUpperCase(Locale.ROOT));
          catalog.asTableCatalog().createTable(identifier, columns, "comment1", ImmutableMap.of());
          Assertions.assertTrue(catalog.asTableCatalog().tableExists(identifier));
          sql("DROP TABLE IF EXISTS %s", tableName);
          Assertions.assertFalse(catalog.asTableCatalog().tableExists(identifier));
        },
        true,
        supportDropCascade());
  }

  /**
   * Oracle stores unquoted identifiers in uppercase. The base-class test checks {@code tableExists}
   * with lowercase names after a Flink SQL rename, which misses the uppercase physical table.
   * Override to check with uppercase identifiers.
   */
  @Override
  @Test
  @EnabledIf("supportTableOperation")
  public void testRenameTable() {
    String databaseName = "test_rename_table_db";
    String tableName = "test_rename_table";
    doWithSchema(
        currentCatalog(),
        databaseName,
        catalog -> {
          TestUtils.assertTableResult(
              sql(
                  "CREATE TABLE %s "
                      + "(user_id INT COMMENT 'USER_ID', "
                      + " order_amount INT COMMENT 'ORDER_AMOUNT')"
                      + " COMMENT 'test comment'",
                  tableName),
              ResultKind.SUCCESS);
          String newTableName = "new_rename_table_name";
          TestUtils.assertTableResult(
              sql("ALTER TABLE %s RENAME TO %s", tableName, newTableName), ResultKind.SUCCESS);
          Assertions.assertFalse(
              catalog
                  .asTableCatalog()
                  .tableExists(
                      NameIdentifier.of(databaseName, tableName.toUpperCase(Locale.ROOT))));
          Assertions.assertTrue(
              catalog
                  .asTableCatalog()
                  .tableExists(
                      NameIdentifier.of(databaseName, newTableName.toUpperCase(Locale.ROOT))));
        },
        true,
        supportDropCascade());
  }
}
