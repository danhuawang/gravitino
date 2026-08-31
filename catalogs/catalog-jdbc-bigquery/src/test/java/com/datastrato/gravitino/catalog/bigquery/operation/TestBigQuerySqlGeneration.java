/*
 * Copyright 2026 Datastrato Inc.
 */

package com.datastrato.gravitino.catalog.bigquery.operation;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.datastrato.gravitino.catalog.bigquery.BigQueryTablePropertiesMetadata;
import com.datastrato.gravitino.catalog.bigquery.converter.BigQueryTypeConverter;
import java.lang.reflect.Field;
import java.util.HashMap;
import java.util.Map;
import org.apache.gravitino.catalog.jdbc.JdbcColumn;
import org.apache.gravitino.rel.expressions.distributions.Distributions;
import org.apache.gravitino.rel.expressions.transforms.Transform;
import org.apache.gravitino.rel.expressions.transforms.Transforms;
import org.apache.gravitino.rel.indexes.Index;
import org.apache.gravitino.rel.types.Types;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/** Tests for BigQuery SQL generation functionality. */
public class TestBigQuerySqlGeneration {

  @BeforeEach
  void setUp() {
    // Test setup is done per test method
  }

  private BigQueryTableOperations createTableOperations() {
    BigQueryTableOperations tableOperations = new BigQueryTableOperations();
    BigQueryTypeConverter typeConverter = new BigQueryTypeConverter();
    // Use reflection to set the protected field for testing
    try {
      Field field = tableOperations.getClass().getSuperclass().getDeclaredField("typeConverter");
      field.setAccessible(true);
      field.set(tableOperations, typeConverter);
    } catch (Exception e) {
      throw new RuntimeException("Failed to set typeConverter for testing", e);
    }
    return tableOperations;
  }

  @Test
  void testCreateTableWithAllFeatures() {
    BigQueryTableOperations tableOperations = createTableOperations();

    String tableName = "my_project.my_dataset.my_table";
    JdbcColumn[] columns = {
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
          .build(),
      JdbcColumn.builder()
          .withName("created_date")
          .withType(Types.DateType.get())
          .withNullable(false)
          .build(),
      JdbcColumn.builder()
          .withName("category")
          .withType(Types.StringType.get())
          .withNullable(true)
          .build()
    };

    String comment = "Test table with all BigQuery features";
    Map<String, String> properties = new HashMap<>();
    properties.put(BigQueryTablePropertiesMetadata.CLUSTERING_FIELDS, "category,name");
    properties.put(BigQueryTablePropertiesMetadata.PARTITION_EXPIRATION_DAYS, "30");
    properties.put(BigQueryTablePropertiesMetadata.REQUIRE_PARTITION_FILTER, "true");
    properties.put(BigQueryTablePropertiesMetadata.FRIENDLY_NAME, "My Test Table");

    Transform[] partitioning = {Transforms.day("created_date")};
    Index[] indexes = new Index[0];

    String sql =
        tableOperations.generateCreateTableSql(
            tableName, columns, comment, properties, partitioning, Distributions.NONE, indexes);

    // Verify the generated SQL contains expected elements
    assertTrue(sql.contains("CREATE TABLE `my_project.my_dataset.my_table`"));
    assertTrue(sql.contains("`id` int64 NOT NULL OPTIONS(description=\"Primary key\")"));
    assertTrue(sql.contains("`name` string OPTIONS(description=\"User name\")"));
    assertTrue(sql.contains("`created_date` date NOT NULL"));
    assertTrue(sql.contains("`category` string"));
    assertTrue(sql.contains("PARTITION BY `created_date`"));
    assertTrue(sql.contains("CLUSTER BY `category`, `name`"));
    assertTrue(sql.contains("description=\"Test table with all BigQuery features\""));
    assertTrue(sql.contains("partition_expiration_days=30"));
    assertTrue(sql.contains("require_partition_filter=true"));
    assertTrue(sql.contains("friendly_name=\"My Test Table\""));
    assertTrue(sql.endsWith(";"));
  }

  @Test
  void testCreateTableWithDifferentPartitioning() {
    BigQueryTableOperations tableOperations = createTableOperations();

    String tableName = "test.table";
    JdbcColumn[] columns = {
      JdbcColumn.builder()
          .withName("timestamp_col")
          .withType(Types.TimestampType.withTimeZone())
          .withNullable(false)
          .build()
    };

    // Test hour partitioning
    Transform[] hourPartitioning = {Transforms.hour("timestamp_col")};
    String sql =
        tableOperations.generateCreateTableSql(
            tableName, columns, null, null, hourPartitioning, Distributions.NONE, new Index[0]);
    assertTrue(sql.contains("PARTITION BY TIMESTAMP_TRUNC(`timestamp_col`, HOUR)"));

    // Test month partitioning
    Transform[] monthPartitioning = {Transforms.month("timestamp_col")};
    sql =
        tableOperations.generateCreateTableSql(
            tableName, columns, null, null, monthPartitioning, Distributions.NONE, new Index[0]);
    assertTrue(sql.contains("PARTITION BY TIMESTAMP_TRUNC(`timestamp_col`, MONTH)"));

    // Test year partitioning
    Transform[] yearPartitioning = {Transforms.year("timestamp_col")};
    sql =
        tableOperations.generateCreateTableSql(
            tableName, columns, null, null, yearPartitioning, Distributions.NONE, new Index[0]);
    assertTrue(sql.contains("PARTITION BY TIMESTAMP_TRUNC(`timestamp_col`, YEAR)"));
  }

  @Test
  void testCreateTableWithComplexTypes() {
    BigQueryTableOperations tableOperations = createTableOperations();

    String tableName = "test.complex_table";
    JdbcColumn[] columns = {
      JdbcColumn.builder()
          .withName("decimal_col")
          .withType(Types.DecimalType.of(10, 2))
          .withNullable(false)
          .build(),
      JdbcColumn.builder()
          .withName("timestamp_col")
          .withType(Types.TimestampType.withTimeZone(6))
          .withNullable(true)
          .build(),
      JdbcColumn.builder()
          .withName("time_col")
          .withType(Types.TimeType.of(3))
          .withNullable(true)
          .build()
    };

    String sql =
        tableOperations.generateCreateTableSql(
            tableName, columns, null, null, new Transform[0], Distributions.NONE, new Index[0]);

    assertTrue(sql.contains("`decimal_col` NUMERIC(10, 2) NOT NULL"));
    assertTrue(sql.contains("`timestamp_col` timestamp"));
    assertTrue(sql.contains("`time_col` time"));
  }

  @Test
  void testDropTableSql() {
    BigQueryTableOperations tableOperations = createTableOperations();
    String sql = tableOperations.generatePurgeTableSql("my_project.my_dataset.my_table");
    assertEquals("DROP TABLE `my_project.my_dataset.my_table`", sql);
  }
}
