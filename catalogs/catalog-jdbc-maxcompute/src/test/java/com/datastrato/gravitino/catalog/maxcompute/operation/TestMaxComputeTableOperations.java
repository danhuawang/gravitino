/*
 * Copyright 2026 Datastrato Pvt Ltd.
 * This software is licensed under the Apache License version 2.
 */

package com.datastrato.gravitino.catalog.maxcompute.operation;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.datastrato.gravitino.catalog.maxcompute.converter.MaxComputeColumnDefaultValueConverter;
import com.datastrato.gravitino.catalog.maxcompute.converter.MaxComputeExceptionConverter;
import com.datastrato.gravitino.catalog.maxcompute.converter.MaxComputeTypeConverter;
import com.google.common.collect.ImmutableMap;
import java.util.HashMap;
import java.util.Map;
import org.apache.gravitino.catalog.jdbc.JdbcColumn;
import org.apache.gravitino.rel.TableChange;
import org.apache.gravitino.rel.expressions.distributions.Distributions;
import org.apache.gravitino.rel.expressions.transforms.Transforms;
import org.apache.gravitino.rel.indexes.Index;
import org.apache.gravitino.rel.indexes.Indexes;
import org.apache.gravitino.rel.types.Types;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/** Tests for {@link MaxComputeTableOperations}. */
public class TestMaxComputeTableOperations {

  private static final String PROJECT_NAME = "test_project";
  private MaxComputeTableOperations tableOperations;

  @BeforeEach
  void setUp() {
    tableOperations = new MaxComputeTableOperations();
    MaxComputeTypeConverter typeConverter = new MaxComputeTypeConverter();
    MaxComputeColumnDefaultValueConverter defaultValueConverter =
        new MaxComputeColumnDefaultValueConverter();

    // Initialize the table operations with converters and jdbc-url containing project name
    Map<String, String> conf = new HashMap<>();
    conf.put(
        "jdbc-url",
        "jdbc:odps:https://service.cn-hangzhou.maxcompute.aliyun.com/api?project="
            + PROJECT_NAME
            + "&odpsNamespaceSchema=true");

    tableOperations.initialize(
        null, // DataSource not needed for SQL generation tests
        new MaxComputeExceptionConverter(),
        typeConverter,
        defaultValueConverter,
        conf);
  }

  // ==================== CREATE TABLE Tests ====================

  @Test
  void testGenerateCreateTableSqlBasic() {
    JdbcColumn[] columns =
        new JdbcColumn[] {
          JdbcColumn.builder()
              .withName("id")
              .withType(Types.LongType.get())
              .withNullable(false)
              .withComment("Primary key")
              .build(),
          JdbcColumn.builder()
              .withName("name")
              .withType(Types.StringType.get())
              .withNullable(true)
              .withComment("User name")
              .build()
        };

    String sql =
        tableOperations.generateCreateTableSql(
            "test_table",
            columns,
            "Test table comment",
            new HashMap<>(),
            Transforms.EMPTY_TRANSFORM,
            Distributions.NONE,
            new Index[0]);

    assertTrue(sql.contains("CREATE TABLE `test_table`"));
    assertTrue(sql.contains("`id` bigint NOT NULL"));
    assertTrue(sql.contains("`name` string"));
    assertTrue(sql.contains("COMMENT 'Test table comment'"));
  }

  @Test
  void testGenerateCreateTableSqlWithLifecycle() {
    JdbcColumn[] columns =
        new JdbcColumn[] {
          JdbcColumn.builder()
              .withName("id")
              .withType(Types.LongType.get())
              .withNullable(false)
              .build()
        };

    Map<String, String> properties = ImmutableMap.of("lifecycle", "30");

    String sql =
        tableOperations.generateCreateTableSql(
            "test_table",
            columns,
            null,
            properties,
            Transforms.EMPTY_TRANSFORM,
            Distributions.NONE,
            new Index[0]);

    assertTrue(sql.contains("LIFECYCLE 30"));
  }

  @Test
  void testGenerateCreateTableSqlWithTransactional() {
    JdbcColumn[] columns =
        new JdbcColumn[] {
          JdbcColumn.builder()
              .withName("id")
              .withType(Types.LongType.get())
              .withNullable(false)
              .build(),
          JdbcColumn.builder()
              .withName("value")
              .withType(Types.StringType.get())
              .withNullable(true)
              .build()
        };

    Map<String, String> properties =
        ImmutableMap.of(
            "transactional", "true",
            "write.bucket.num", "32",
            "acid.data.retain.hours", "72");

    Index primaryKey = Indexes.primary("pk_id", new String[][] {{"id"}});

    String sql =
        tableOperations.generateCreateTableSql(
            "delta_table",
            columns,
            "Delta table",
            properties,
            Transforms.EMPTY_TRANSFORM,
            Distributions.NONE,
            new Index[] {primaryKey});

    assertTrue(sql.contains("TBLPROPERTIES"));
    assertTrue(sql.contains("\"transactional\"=\"true\""));
    assertTrue(sql.contains("\"write.bucket.num\"=\"32\""));
    assertTrue(sql.contains("\"acid.data.retain.hours\"=\"72\""));
    assertTrue(sql.contains("PRIMARY KEY (`id`)"));
  }

  @Test
  void testGenerateCreateTableSqlWithAllDataTypes() {
    JdbcColumn[] columns =
        new JdbcColumn[] {
          JdbcColumn.builder()
              .withName("col_boolean")
              .withType(Types.BooleanType.get())
              .withNullable(true)
              .build(),
          JdbcColumn.builder()
              .withName("col_tinyint")
              .withType(Types.ByteType.get())
              .withNullable(true)
              .build(),
          JdbcColumn.builder()
              .withName("col_smallint")
              .withType(Types.ShortType.get())
              .withNullable(true)
              .build(),
          JdbcColumn.builder()
              .withName("col_int")
              .withType(Types.IntegerType.get())
              .withNullable(true)
              .build(),
          JdbcColumn.builder()
              .withName("col_bigint")
              .withType(Types.LongType.get())
              .withNullable(true)
              .build(),
          JdbcColumn.builder()
              .withName("col_float")
              .withType(Types.FloatType.get())
              .withNullable(true)
              .build(),
          JdbcColumn.builder()
              .withName("col_double")
              .withType(Types.DoubleType.get())
              .withNullable(true)
              .build(),
          JdbcColumn.builder()
              .withName("col_decimal")
              .withType(Types.DecimalType.of(18, 6))
              .withNullable(true)
              .build(),
          JdbcColumn.builder()
              .withName("col_string")
              .withType(Types.StringType.get())
              .withNullable(true)
              .build(),
          JdbcColumn.builder()
              .withName("col_varchar")
              .withType(Types.VarCharType.of(100))
              .withNullable(true)
              .build(),
          JdbcColumn.builder()
              .withName("col_char")
              .withType(Types.FixedCharType.of(10))
              .withNullable(true)
              .build(),
          JdbcColumn.builder()
              .withName("col_binary")
              .withType(Types.BinaryType.get())
              .withNullable(true)
              .build(),
          JdbcColumn.builder()
              .withName("col_date")
              .withType(Types.DateType.get())
              .withNullable(true)
              .build(),
          JdbcColumn.builder()
              .withName("col_timestamp")
              .withType(Types.TimestampType.withoutTimeZone())
              .withNullable(true)
              .build()
        };

    String sql =
        tableOperations.generateCreateTableSql(
            "all_types_table",
            columns,
            "Table with all data types",
            new HashMap<>(),
            Transforms.EMPTY_TRANSFORM,
            Distributions.NONE,
            new Index[0]);

    assertTrue(sql.contains("`col_boolean` boolean"));
    assertTrue(sql.contains("`col_tinyint` tinyint"));
    assertTrue(sql.contains("`col_smallint` smallint"));
    assertTrue(sql.contains("`col_int` int"));
    assertTrue(sql.contains("`col_bigint` bigint"));
    assertTrue(sql.contains("`col_float` float"));
    assertTrue(sql.contains("`col_double` double"));
    assertTrue(sql.contains("`col_decimal` decimal(18,6)"));
    assertTrue(sql.contains("`col_string` string"));
    assertTrue(sql.contains("`col_varchar` varchar(100)"));
    assertTrue(sql.contains("`col_char` char(10)"));
    assertTrue(sql.contains("`col_binary` binary"));
    assertTrue(sql.contains("`col_date` date"));
    assertTrue(sql.contains("`col_timestamp` timestamp"));
  }

  // ==================== DROP TABLE Tests ====================

  @Test
  void testGenerateDropTableSql() {
    String sql = tableOperations.generateDropTableSql("test_table");
    assertEquals("DROP TABLE IF EXISTS `test_table`", sql);
  }

  // ==================== RENAME TABLE Tests ====================

  @Test
  void testGenerateRenameTableSql() {
    String sql = tableOperations.generateRenameTableSql("old_table", "new_table");
    assertEquals("ALTER TABLE `old_table` RENAME TO `new_table`", sql);
  }

  // ==================== PURGE TABLE Tests ====================

  @Test
  void testGeneratePurgeTableSql() {
    String sql = tableOperations.generatePurgeTableSql("test_table");
    // MaxCompute DROP TABLE is permanent
    assertEquals("DROP TABLE IF EXISTS `test_table`", sql);
  }

  // ==================== ALTER TABLE Tests ====================

  @Test
  void testGenerateAlterTableSqlUpdateComment() {
    TableChange updateComment = TableChange.updateComment("New table comment");

    String sql = tableOperations.generateAlterTableSql("test_schema", "test_table", updateComment);

    assertTrue(sql.contains("ALTER TABLE " + PROJECT_NAME + ".test_schema.`test_table`"));
    assertTrue(sql.contains("SET COMMENT 'New table comment'"));
  }

  @Test
  void testGenerateAlterTableSqlAddColumn() {
    TableChange addColumn =
        TableChange.addColumn(
            new String[] {"new_column"}, Types.StringType.get(), "New column comment");

    String sql = tableOperations.generateAlterTableSql("test_schema", "test_table", addColumn);

    assertTrue(sql.contains("ALTER TABLE " + PROJECT_NAME + ".test_schema.`test_table`"));
    assertTrue(sql.contains("ADD COLUMNS"));
    assertTrue(sql.contains("`new_column` string"));
    assertTrue(sql.contains("COMMENT 'New column comment'"));
  }

  @Test
  void testGenerateAlterTableSqlRenameColumn() {
    TableChange renameColumn = TableChange.renameColumn(new String[] {"old_column"}, "new_column");

    String sql = tableOperations.generateAlterTableSql("test_schema", "test_table", renameColumn);

    assertTrue(sql.contains("ALTER TABLE " + PROJECT_NAME + ".test_schema.`test_table`"));
    assertTrue(sql.contains("CHANGE COLUMN `old_column` RENAME TO `new_column`"));
  }

  @Test
  void testGenerateAlterTableSqlSetLifecycle() {
    TableChange setProperty = TableChange.setProperty("lifecycle", "60");

    String sql = tableOperations.generateAlterTableSql("test_schema", "test_table", setProperty);

    assertTrue(sql.contains("ALTER TABLE " + PROJECT_NAME + ".test_schema.`test_table`"));
    assertTrue(sql.contains("SET LIFECYCLE 60"));
  }

  @Test
  void testGenerateAlterTableSqlSetProperty() {
    TableChange setProperty = TableChange.setProperty("custom_property", "custom_value");

    String sql = tableOperations.generateAlterTableSql("test_schema", "test_table", setProperty);

    assertTrue(sql.contains("ALTER TABLE " + PROJECT_NAME + ".test_schema.`test_table`"));
    assertTrue(sql.contains("SET TBLPROPERTIES"));
    assertTrue(sql.contains("\"custom_property\"=\"custom_value\""));
  }

  @Test
  void testGenerateAlterTableSqlRenameTable() {
    TableChange renameTable = TableChange.rename("new_table_name");

    String sql = tableOperations.generateAlterTableSql("test_schema", "test_table", renameTable);

    assertEquals(
        "ALTER TABLE " + PROJECT_NAME + ".test_schema.`test_table` RENAME TO `new_table_name`",
        sql);
  }

  @Test
  void testGenerateAlterTableSqlRemovePropertyNotSupported() {
    TableChange removeProperty = TableChange.removeProperty("some_property");

    assertThrows(
        UnsupportedOperationException.class,
        () -> tableOperations.generateAlterTableSql("test_schema", "test_table", removeProperty));
  }

  @Test
  void testGenerateAlterTableSqlNestedColumnNotSupported() {
    TableChange addColumn =
        TableChange.addColumn(
            new String[] {"parent", "child"}, // Nested column
            Types.StringType.get());

    assertThrows(
        IllegalArgumentException.class,
        () -> tableOperations.generateAlterTableSql("test_schema", "test_table", addColumn));
  }

  // ==================== SQL Escaping Tests ====================

  @Test
  void testCommentEscaping() {
    JdbcColumn[] columns =
        new JdbcColumn[] {
          JdbcColumn.builder()
              .withName("id")
              .withType(Types.LongType.get())
              .withNullable(false)
              .withComment("Column with 'quotes'")
              .build()
        };

    String sql =
        tableOperations.generateCreateTableSql(
            "test_table",
            columns,
            "Table with 'single quotes'",
            new HashMap<>(),
            Transforms.EMPTY_TRANSFORM,
            Distributions.NONE,
            new Index[0]);

    // MaxCompute uses SQL-standard escaping: single quotes are escaped by doubling them
    assertTrue(sql.contains("COMMENT 'Table with ''single quotes'''"));
    assertTrue(sql.contains("COMMENT 'Column with ''quotes'''"));
  }

  // ==================== Column Definition Tests ====================

  @Test
  void testColumnWithNotNull() {
    JdbcColumn[] columns =
        new JdbcColumn[] {
          JdbcColumn.builder()
              .withName("required_col")
              .withType(Types.StringType.get())
              .withNullable(false)
              .build()
        };

    String sql =
        tableOperations.generateCreateTableSql(
            "test_table",
            columns,
            null,
            new HashMap<>(),
            Transforms.EMPTY_TRANSFORM,
            Distributions.NONE,
            new Index[0]);

    assertTrue(sql.contains("`required_col` string NOT NULL"));
  }

  @Test
  void testColumnWithDefaultValue() {
    JdbcColumn[] columns =
        new JdbcColumn[] {
          JdbcColumn.builder()
              .withName("col_with_default")
              .withType(Types.IntegerType.get())
              .withNullable(true)
              .withDefaultValue(
                  org.apache.gravitino.rel.expressions.literals.Literals.integerLiteral(100))
              .build()
        };

    String sql =
        tableOperations.generateCreateTableSql(
            "test_table",
            columns,
            null,
            new HashMap<>(),
            Transforms.EMPTY_TRANSFORM,
            Distributions.NONE,
            new Index[0]);

    assertTrue(sql.contains("`col_with_default` int"));
    assertTrue(sql.contains("DEFAULT"));
  }

  // ==================== Partitioned Table Tests ====================

  @Test
  void testGenerateCreateTableSqlWithSinglePartition() {
    JdbcColumn[] columns =
        new JdbcColumn[] {
          JdbcColumn.builder()
              .withName("id")
              .withType(Types.LongType.get())
              .withNullable(false)
              .build(),
          JdbcColumn.builder()
              .withName("name")
              .withType(Types.StringType.get())
              .withNullable(true)
              .build(),
          JdbcColumn.builder()
              .withName("dt")
              .withType(Types.StringType.get())
              .withNullable(true)
              .withComment("Partition column")
              .build()
        };

    // Create partition transform for 'dt' column
    org.apache.gravitino.rel.expressions.transforms.Transform[] partitioning =
        new org.apache.gravitino.rel.expressions.transforms.Transform[] {Transforms.identity("dt")};

    String sql =
        tableOperations.generateCreateTableSql(
            "partitioned_table",
            columns,
            "Table with single partition",
            new HashMap<>(),
            partitioning,
            Distributions.NONE,
            new Index[0]);

    assertTrue(sql.contains("CREATE TABLE `partitioned_table`"));
    assertTrue(sql.contains("`id` bigint NOT NULL"));
    assertTrue(sql.contains("`name` string"));
    assertTrue(sql.contains("PARTITIONED BY"));
    assertTrue(sql.contains("`dt` string"));
    assertTrue(sql.contains("COMMENT 'Partition column'"));
    // Partition column should not be in regular columns
    assertTrue(sql.indexOf("`dt`") > sql.indexOf("PARTITIONED BY"));
  }

  @Test
  void testGenerateCreateTableSqlWithMultiplePartitions() {
    JdbcColumn[] columns =
        new JdbcColumn[] {
          JdbcColumn.builder()
              .withName("id")
              .withType(Types.LongType.get())
              .withNullable(false)
              .build(),
          JdbcColumn.builder()
              .withName("value")
              .withType(Types.StringType.get())
              .withNullable(true)
              .build(),
          JdbcColumn.builder()
              .withName("year")
              .withType(Types.StringType.get())
              .withNullable(true)
              .build(),
          JdbcColumn.builder()
              .withName("month")
              .withType(Types.StringType.get())
              .withNullable(true)
              .build(),
          JdbcColumn.builder()
              .withName("day")
              .withType(Types.StringType.get())
              .withNullable(true)
              .build()
        };

    // Create partition transforms for year, month, day columns
    org.apache.gravitino.rel.expressions.transforms.Transform[] partitioning =
        new org.apache.gravitino.rel.expressions.transforms.Transform[] {
          Transforms.identity("year"), Transforms.identity("month"), Transforms.identity("day")
        };

    String sql =
        tableOperations.generateCreateTableSql(
            "multi_partition_table",
            columns,
            "Table with multiple partitions",
            new HashMap<>(),
            partitioning,
            Distributions.NONE,
            new Index[0]);

    assertTrue(sql.contains("CREATE TABLE `multi_partition_table`"));
    assertTrue(sql.contains("PARTITIONED BY"));
    assertTrue(sql.contains("`year` string"));
    assertTrue(sql.contains("`month` string"));
    assertTrue(sql.contains("`day` string"));
  }

  @Test
  void testGenerateCreateTableSqlWithPartitionAndLifecycle() {
    JdbcColumn[] columns =
        new JdbcColumn[] {
          JdbcColumn.builder()
              .withName("id")
              .withType(Types.LongType.get())
              .withNullable(false)
              .build(),
          JdbcColumn.builder()
              .withName("dt")
              .withType(Types.StringType.get())
              .withNullable(true)
              .build()
        };

    org.apache.gravitino.rel.expressions.transforms.Transform[] partitioning =
        new org.apache.gravitino.rel.expressions.transforms.Transform[] {Transforms.identity("dt")};

    Map<String, String> properties = ImmutableMap.of("lifecycle", "90");

    String sql =
        tableOperations.generateCreateTableSql(
            "partition_lifecycle_table",
            columns,
            null,
            properties,
            partitioning,
            Distributions.NONE,
            new Index[0]);

    assertTrue(sql.contains("PARTITIONED BY"));
    assertTrue(sql.contains("LIFECYCLE 90"));
  }

  @Test
  void testGenerateCreateTableSqlWithPartitionDifferentTypes() {
    JdbcColumn[] columns =
        new JdbcColumn[] {
          JdbcColumn.builder()
              .withName("id")
              .withType(Types.LongType.get())
              .withNullable(false)
              .build(),
          JdbcColumn.builder()
              .withName("pt_string")
              .withType(Types.StringType.get())
              .withNullable(true)
              .build(),
          JdbcColumn.builder()
              .withName("pt_int")
              .withType(Types.IntegerType.get())
              .withNullable(true)
              .build()
        };

    org.apache.gravitino.rel.expressions.transforms.Transform[] partitioning =
        new org.apache.gravitino.rel.expressions.transforms.Transform[] {
          Transforms.identity("pt_string"), Transforms.identity("pt_int")
        };

    String sql =
        tableOperations.generateCreateTableSql(
            "typed_partition_table",
            columns,
            null,
            new HashMap<>(),
            partitioning,
            Distributions.NONE,
            new Index[0]);

    assertTrue(sql.contains("PARTITIONED BY"));
    assertTrue(sql.contains("`pt_string` string"));
    assertTrue(sql.contains("`pt_int` int"));
  }
}
