package org.apache.gravitino.catalog.bigquery.operation;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.HashMap;
import java.util.Map;
import org.apache.gravitino.catalog.bigquery.BigQueryTablePropertiesMetadata;
import org.apache.gravitino.catalog.bigquery.converter.BigQueryTypeConverter;
import org.apache.gravitino.catalog.jdbc.JdbcColumn;
import org.apache.gravitino.rel.expressions.distributions.Distributions;
import org.apache.gravitino.rel.expressions.transforms.Transform;
import org.apache.gravitino.rel.expressions.transforms.Transforms;
import org.apache.gravitino.rel.indexes.Index;
import org.apache.gravitino.rel.indexes.Indexes;
import org.apache.gravitino.rel.types.Types;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/** Unit tests for {@link BigQueryTableOperations}. */
public class TestBigQueryTableOperations {

  @BeforeEach
  void setUp() {
    // Test setup is done per test method
  }

  private BigQueryTableOperations createTableOperations() {
    BigQueryTableOperations tableOperations = new BigQueryTableOperations();
    BigQueryTypeConverter typeConverter = new BigQueryTypeConverter();
    // Use reflection to set the protected field for testing
    try {
      java.lang.reflect.Field field =
          tableOperations.getClass().getSuperclass().getDeclaredField("typeConverter");
      field.setAccessible(true);
      field.set(tableOperations, typeConverter);
    } catch (Exception e) {
      throw new RuntimeException("Failed to set typeConverter for testing", e);
    }
    return tableOperations;
  }

  @Test
  void testGenerateCreateTableSqlBasic() {
    BigQueryTableOperations tableOperations = new BigQueryTableOperations();
    BigQueryTypeConverter typeConverter = new BigQueryTypeConverter();
    // Use reflection to set the protected field for testing
    try {
      java.lang.reflect.Field field =
          tableOperations.getClass().getSuperclass().getDeclaredField("typeConverter");
      field.setAccessible(true);
      field.set(tableOperations, typeConverter);
    } catch (Exception e) {
      throw new RuntimeException("Failed to set typeConverter for testing", e);
    }

    String tableName = "test_dataset.test_table";
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
          .withName("created_at")
          .withType(Types.TimestampType.withTimeZone())
          .withNullable(false)
          .build()
    };

    String comment = "Test table";
    Map<String, String> properties = new HashMap<>();
    Transform[] partitioning = new Transform[0];
    Index[] indexes = new Index[0];

    String sql =
        tableOperations.generateCreateTableSql(
            tableName, columns, comment, properties, partitioning, Distributions.NONE, indexes);

    assertTrue(sql.contains("CREATE TABLE `test_dataset.test_table`"));
    assertTrue(sql.contains("`id` int64 NOT NULL OPTIONS(description=\"Primary key\")"));
    assertTrue(sql.contains("`name` string"));
    assertTrue(sql.contains("`created_at` timestamp NOT NULL"));
    assertTrue(sql.contains("OPTIONS(\n  description=\"Test table\"\n)"));
    assertTrue(sql.endsWith(";"));
  }

  @Test
  void testGenerateCreateTableSqlWithPartitioning() {
    BigQueryTableOperations tableOperations = createTableOperations();
    String tableName = "test_dataset.test_table";
    JdbcColumn[] columns = {
      JdbcColumn.builder()
          .withName("id")
          .withType(Types.LongType.get())
          .withNullable(false)
          .build(),
      JdbcColumn.builder()
          .withName("created_date")
          .withType(Types.DateType.get())
          .withNullable(false)
          .build()
    };

    Transform[] partitioning = {Transforms.day("created_date")};

    String sql =
        tableOperations.generateCreateTableSql(
            tableName, columns, null, null, partitioning, Distributions.NONE, new Index[0]);

    assertTrue(sql.contains("PARTITION BY `created_date`"));
  }

  @Test
  void testGenerateCreateTableSqlWithClustering() {
    BigQueryTableOperations tableOperations = createTableOperations();
    String tableName = "test_dataset.test_table";
    JdbcColumn[] columns = {
      JdbcColumn.builder()
          .withName("id")
          .withType(Types.LongType.get())
          .withNullable(false)
          .build(),
      JdbcColumn.builder()
          .withName("category")
          .withType(Types.StringType.get())
          .withNullable(true)
          .build()
    };

    Map<String, String> properties = new HashMap<>();
    properties.put(BigQueryTablePropertiesMetadata.CLUSTERING_FIELDS, "category,id");

    String sql =
        tableOperations.generateCreateTableSql(
            tableName,
            columns,
            null,
            properties,
            new Transform[0],
            Distributions.NONE,
            new Index[0]);

    assertTrue(sql.contains("CLUSTER BY `category`, `id`"));
  }

  @Test
  void testGenerateCreateTableSqlWithPartitionProperties() {
    BigQueryTableOperations tableOperations = createTableOperations();
    String tableName = "test_dataset.test_table";
    JdbcColumn[] columns = {
      JdbcColumn.builder()
          .withName("id")
          .withType(Types.LongType.get())
          .withNullable(false)
          .build(),
      JdbcColumn.builder()
          .withName("created_date")
          .withType(Types.DateType.get())
          .withNullable(false)
          .build()
    };

    Map<String, String> properties = new HashMap<>();
    properties.put(BigQueryTablePropertiesMetadata.PARTITION_EXPIRATION_DAYS, "30");
    properties.put(BigQueryTablePropertiesMetadata.REQUIRE_PARTITION_FILTER, "true");

    Transform[] partitioning = {Transforms.day("created_date")};

    String sql =
        tableOperations.generateCreateTableSql(
            tableName,
            columns,
            "Test table",
            properties,
            partitioning,
            Distributions.NONE,
            new Index[0]);

    assertTrue(sql.contains("PARTITION BY `created_date`"));
    assertTrue(sql.contains("partition_expiration_days=30"));
    assertTrue(sql.contains("require_partition_filter=true"));
  }

  @Test
  void testGenerateCreateTableSqlWithPartitionPropertiesButNoPartition() {
    BigQueryTableOperations tableOperations = createTableOperations();
    String tableName = "test_dataset.test_table";
    JdbcColumn[] columns = {
      JdbcColumn.builder().withName("id").withType(Types.LongType.get()).withNullable(false).build()
    };

    Map<String, String> properties = new HashMap<>();
    properties.put(BigQueryTablePropertiesMetadata.PARTITION_EXPIRATION_DAYS, "30");
    properties.put(BigQueryTablePropertiesMetadata.REQUIRE_PARTITION_FILTER, "true");

    // No partitioning - these properties should be ignored
    Transform[] partitioning = new Transform[0];

    String sql =
        tableOperations.generateCreateTableSql(
            tableName,
            columns,
            "Test table",
            properties,
            partitioning,
            Distributions.NONE,
            new Index[0]);

    // Partition-related properties should NOT be in the SQL
    assertFalse(sql.contains("partition_expiration_days"));
    assertFalse(sql.contains("require_partition_filter"));
    // But description should still be there
    assertTrue(sql.contains("description=\"Test table\""));
  }

  @Test
  void testGenerateCreateTableSqlWithIndexes() {
    BigQueryTableOperations tableOperations = createTableOperations();
    String tableName = "test_dataset.test_table";
    JdbcColumn[] columns = {
      JdbcColumn.builder().withName("id").withType(Types.LongType.get()).withNullable(false).build()
    };

    Index[] indexes = {Indexes.of(Index.IndexType.PRIMARY_KEY, "pk_id", new String[][] {{"id"}})};

    assertThrows(
        UnsupportedOperationException.class,
        () ->
            tableOperations.generateCreateTableSql(
                tableName, columns, null, null, new Transform[0], Distributions.NONE, indexes));
  }

  @Test
  void testGenerateCreateTableSqlWithUnsupportedDistribution() {
    BigQueryTableOperations tableOperations = createTableOperations();
    String tableName = "test_dataset.test_table";
    JdbcColumn[] columns = {
      JdbcColumn.builder().withName("id").withType(Types.LongType.get()).withNullable(false).build()
    };

    assertThrows(
        UnsupportedOperationException.class,
        () ->
            tableOperations.generateCreateTableSql(
                tableName,
                columns,
                null,
                null,
                new Transform[0],
                Distributions.hash(1, Transforms.identity("id")),
                new Index[0]));
  }

  @Test
  void testGeneratePartitionClause() {
    BigQueryTableOperations tableOperations = createTableOperations();
    // Test day partitioning with DATE column
    JdbcColumn[] dateColumns = {
      JdbcColumn.builder()
          .withName("created_date")
          .withType(Types.DateType.get())
          .withNullable(false)
          .build()
    };
    Transform[] dayPartitioning = {Transforms.day("created_date")};
    String sql =
        tableOperations.generateCreateTableSql(
            "test.table",
            dateColumns,
            null,
            null,
            dayPartitioning,
            Distributions.NONE,
            new Index[0]);
    assertTrue(sql.contains("PARTITION BY `created_date`"));

    // Test day partitioning with TIMESTAMP column
    JdbcColumn[] timestampColumns = {
      JdbcColumn.builder()
          .withName("created_timestamp")
          .withType(Types.TimestampType.withTimeZone())
          .withNullable(false)
          .build()
    };
    Transform[] dayPartitioningTimestamp = {Transforms.day("created_timestamp")};
    sql =
        tableOperations.generateCreateTableSql(
            "test.table",
            timestampColumns,
            null,
            null,
            dayPartitioningTimestamp,
            Distributions.NONE,
            new Index[0]);
    assertTrue(sql.contains("PARTITION BY DATE(`created_timestamp`)"));

    // Test month partitioning with DATE column
    Transform[] monthPartitioning = {Transforms.month("created_date")};
    sql =
        tableOperations.generateCreateTableSql(
            "test.table",
            dateColumns,
            null,
            null,
            monthPartitioning,
            Distributions.NONE,
            new Index[0]);
    assertTrue(sql.contains("PARTITION BY DATE_TRUNC(`created_date`, MONTH)"));

    // Test year partitioning with DATE column
    Transform[] yearPartitioning = {Transforms.year("created_date")};
    sql =
        tableOperations.generateCreateTableSql(
            "test.table",
            dateColumns,
            null,
            null,
            yearPartitioning,
            Distributions.NONE,
            new Index[0]);
    assertTrue(sql.contains("PARTITION BY DATE_TRUNC(`created_date`, YEAR)"));

    // Test hour partitioning with TIMESTAMP column
    Transform[] hourPartitioning = {Transforms.hour("created_timestamp")};
    sql =
        tableOperations.generateCreateTableSql(
            "test.table",
            timestampColumns,
            null,
            null,
            hourPartitioning,
            Distributions.NONE,
            new Index[0]);
    assertTrue(sql.contains("PARTITION BY TIMESTAMP_TRUNC(`created_timestamp`, HOUR)"));

    // Test identity partitioning with DATE column
    Transform[] identityPartitioning = {Transforms.identity("created_date")};
    sql =
        tableOperations.generateCreateTableSql(
            "test.table",
            dateColumns,
            null,
            null,
            identityPartitioning,
            Distributions.NONE,
            new Index[0]);
    assertTrue(sql.contains("PARTITION BY `created_date`"));

    // Test identity partitioning with TIMESTAMP column
    Transform[] identityPartitioningTimestamp = {Transforms.identity("created_timestamp")};
    sql =
        tableOperations.generateCreateTableSql(
            "test.table",
            timestampColumns,
            null,
            null,
            identityPartitioningTimestamp,
            Distributions.NONE,
            new Index[0]);
    assertTrue(sql.contains("PARTITION BY DATE(`created_timestamp`)"));
  }

  @Test
  void testGenerateCreateTableSqlWithAllProperties() {
    BigQueryTableOperations tableOperations = createTableOperations();
    String tableName = "test_dataset.test_table";
    JdbcColumn[] columns = {
      JdbcColumn.builder()
          .withName("id")
          .withType(Types.LongType.get())
          .withNullable(false)
          .build(),
      JdbcColumn.builder()
          .withName("created_date")
          .withType(Types.DateType.get())
          .withNullable(false)
          .build()
    };

    Map<String, String> properties = new HashMap<>();
    properties.put(BigQueryTablePropertiesMetadata.PARTITION_EXPIRATION_DAYS, "30.5");
    properties.put(BigQueryTablePropertiesMetadata.REQUIRE_PARTITION_FILTER, "true");
    properties.put(BigQueryTablePropertiesMetadata.CLUSTERING_FIELDS, "id");
    properties.put(BigQueryTablePropertiesMetadata.EXPIRATION_TIMESTAMP, "2025-12-31T23:59:59Z");
    properties.put(BigQueryTablePropertiesMetadata.FRIENDLY_NAME, "Test Table");
    properties.put(
        BigQueryTablePropertiesMetadata.LABELS, "[{\"env\":\"test\"},{\"team\":\"data\"}]");
    properties.put(
        BigQueryTablePropertiesMetadata.KMS_KEY_NAME,
        "projects/test/locations/us/keyRings/ring/cryptoKeys/key");
    properties.put(BigQueryTablePropertiesMetadata.DEFAULT_ROUNDING_MODE, "ROUND_HALF_EVEN");
    properties.put(BigQueryTablePropertiesMetadata.ENABLE_CHANGE_HISTORY, "true");
    properties.put(
        BigQueryTablePropertiesMetadata.MAX_STALENESS, "INTERVAL \"1:0:0\" HOUR TO SECOND");

    Transform[] partitioning = {Transforms.day("created_date")};

    String sql =
        tableOperations.generateCreateTableSql(
            tableName,
            columns,
            "Comprehensive test table",
            properties,
            partitioning,
            Distributions.NONE,
            new Index[0]);

    // Verify basic structure
    assertTrue(sql.contains("CREATE TABLE `test_dataset.test_table`"));
    assertTrue(sql.contains("PARTITION BY `created_date`"));
    assertTrue(sql.contains("CLUSTER BY `id`"));

    // Verify all properties are included
    assertTrue(sql.contains("description=\"Comprehensive test table\""));
    assertTrue(sql.contains("partition_expiration_days=30.5"));
    assertTrue(sql.contains("require_partition_filter=true"));
    assertTrue(sql.contains("expiration_timestamp=TIMESTAMP \"2025-12-31T23:59:59Z\""));
    assertTrue(sql.contains("friendly_name=\"Test Table\""));
    assertTrue(sql.contains("labels="));
    assertTrue(sql.contains("kms_key_name="));
    assertTrue(sql.contains("default_rounding_mode=\"ROUND_HALF_EVEN\""));
    assertTrue(sql.contains("enable_change_history=true"));
    assertTrue(sql.contains("max_staleness=INTERVAL \"1:0:0\" HOUR TO SECOND"));
  }

  @Test
  void testGeneratePurgeTableSql() {
    BigQueryTableOperations tableOperations = new BigQueryTableOperations();
    String sql = tableOperations.generatePurgeTableSql("test_dataset.test_table");
    assertEquals("DROP TABLE `test_dataset.test_table`", sql);
  }

  @Test
  void testGenerateRenameTableSql() {
    BigQueryTableOperations tableOperations = new BigQueryTableOperations();
    String sql = tableOperations.generateRenameTableSql("dataset.old_table", "new_table");
    assertEquals("ALTER TABLE `dataset.old_table` RENAME TO `new_table`", sql);
  }

  @Test
  void testGetAutoIncrementInfo() throws SQLException {
    BigQueryTableOperations tableOperations = new BigQueryTableOperations();
    ResultSet mockResultSet = mock(ResultSet.class);
    when(mockResultSet.getString("IS_AUTOINCREMENT")).thenReturn("YES");

    // BigQuery doesn't support auto increment, should always return false
    assertFalse(tableOperations.getAutoIncrementInfo(mockResultSet));
  }

  @Test
  void testEscapeString() {
    BigQueryTableOperations tableOperations = createTableOperations();
    // Test the private escapeString method through SQL generation
    JdbcColumn[] columns = {
      JdbcColumn.builder()
          .withName("test")
          .withType(Types.StringType.get())
          .withNullable(true)
          .withComment("Test \"quoted\" comment\nwith newline")
          .build()
    };

    String sql =
        tableOperations.generateCreateTableSql(
            "test.table",
            columns,
            "Table with \"quotes\" and\nnewlines",
            null,
            new Transform[0],
            Distributions.NONE,
            new Index[0]);

    assertTrue(sql.contains("\\\"quoted\\\""));
    assertTrue(sql.contains("\\n"));
  }

  @Test
  void testManagedTableProperties() {
    BigQueryTableOperations tableOperations = createTableOperations();
    String tableName = "test_dataset.managed_table";
    JdbcColumn[] columns = {
      JdbcColumn.builder().withName("id").withType(Types.LongType.get()).withNullable(false).build()
    };

    Map<String, String> properties = new HashMap<>();
    properties.put(BigQueryTablePropertiesMetadata.STORAGE_URI, "gs://my-bucket/my-table/");
    properties.put(BigQueryTablePropertiesMetadata.FILE_FORMAT, "PARQUET");
    properties.put(BigQueryTablePropertiesMetadata.TABLE_FORMAT, "ICEBERG");

    String sql =
        tableOperations.generateCreateTableSql(
            tableName,
            columns,
            "Managed table test",
            properties,
            new Transform[0],
            Distributions.NONE,
            new Index[0]);

    assertTrue(sql.contains("storage_uri=\"gs://my-bucket/my-table/\""));
    assertTrue(sql.contains("file_format=\"PARQUET\""));
    assertTrue(sql.contains("table_format=\"ICEBERG\""));
  }

  @Test
  void testPreviewFeatureProperties() {
    BigQueryTableOperations tableOperations = createTableOperations();
    String tableName = "test_dataset.preview_table";
    JdbcColumn[] columns = {
      JdbcColumn.builder().withName("id").withType(Types.LongType.get()).withNullable(false).build()
    };

    Map<String, String> properties = new HashMap<>();
    properties.put(BigQueryTablePropertiesMetadata.ENABLE_CHANGE_HISTORY, "true");
    properties.put(BigQueryTablePropertiesMetadata.ENABLE_FINE_GRAINED_MUTATIONS, "false");
    properties.put(
        BigQueryTablePropertiesMetadata.MAX_STALENESS, "INTERVAL \"2:30:0\" HOUR TO SECOND");

    String sql =
        tableOperations.generateCreateTableSql(
            tableName,
            columns,
            "Preview features test",
            properties,
            new Transform[0],
            Distributions.NONE,
            new Index[0]);

    assertTrue(sql.contains("enable_change_history=true"));
    assertTrue(sql.contains("enable_fine_grained_mutations=false"));
    assertTrue(sql.contains("max_staleness=INTERVAL \"2:30:0\" HOUR TO SECOND"));
  }

  @Test
  void testEncryptionAndSecurityProperties() {
    BigQueryTableOperations tableOperations = createTableOperations();
    String tableName = "test_dataset.secure_table";
    JdbcColumn[] columns = {
      JdbcColumn.builder().withName("id").withType(Types.LongType.get()).withNullable(false).build()
    };

    Map<String, String> properties = new HashMap<>();
    properties.put(
        BigQueryTablePropertiesMetadata.KMS_KEY_NAME,
        "projects/test/locations/us/keyRings/ring/cryptoKeys/key");
    properties.put(BigQueryTablePropertiesMetadata.DEFAULT_ROUNDING_MODE, "ROUND_HALF_EVEN");
    properties.put(
        BigQueryTablePropertiesMetadata.TAGS,
        "[{\"security\":\"high\"},{\"compliance\":\"gdpr\"}]");

    String sql =
        tableOperations.generateCreateTableSql(
            tableName,
            columns,
            "Secure table test",
            properties,
            new Transform[0],
            Distributions.NONE,
            new Index[0]);

    assertTrue(
        sql.contains("kms_key_name=\"projects/test/locations/us/keyRings/ring/cryptoKeys/key\""));
    assertTrue(sql.contains("default_rounding_mode=\"ROUND_HALF_EVEN\""));
    assertTrue(sql.contains("tags="));
  }
}
