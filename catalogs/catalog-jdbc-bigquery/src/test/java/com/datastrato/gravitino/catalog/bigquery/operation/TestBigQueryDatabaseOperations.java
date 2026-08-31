/*
 * Copyright 2026 Datastrato Inc.
 */

package com.datastrato.gravitino.catalog.bigquery.operation;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.datastrato.gravitino.catalog.bigquery.BigQuerySchemaPropertiesMetadata;
import java.util.HashMap;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/** Unit tests for BigQueryDatabaseOperations. */
public class TestBigQueryDatabaseOperations {

  private BigQueryDatabaseOperations operations;

  @BeforeEach
  void setUp() {
    operations = new BigQueryDatabaseOperations();
  }

  @Test
  void testGenerateCreateDatabaseSql() {
    // Test basic CREATE SCHEMA
    String sql = operations.generateCreateDatabaseSql("test_dataset", null, null);
    assertEquals("CREATE SCHEMA `test_dataset`;", sql);

    // Test with comment only
    sql = operations.generateCreateDatabaseSql("test_dataset", "Test description", null);
    assertTrue(sql.contains("CREATE SCHEMA `test_dataset`"));
    assertTrue(sql.contains("OPTIONS"));
    assertTrue(sql.contains("description=\"Test description\""));

    // Test with comprehensive properties
    Map<String, String> properties = new HashMap<>();
    properties.put(BigQuerySchemaPropertiesMetadata.LOCATION, "us-central1");
    properties.put(BigQuerySchemaPropertiesMetadata.DEFAULT_TABLE_EXPIRATION_DAYS, "30");
    properties.put(BigQuerySchemaPropertiesMetadata.DEFAULT_PARTITION_EXPIRATION_DAYS, "365");
    properties.put(BigQuerySchemaPropertiesMetadata.STORAGE_BILLING_MODEL, "LOGICAL");
    properties.put(BigQuerySchemaPropertiesMetadata.MAX_TIME_TRAVEL_HOURS, "168");
    properties.put(BigQuerySchemaPropertiesMetadata.DEFAULT_ROUNDING_MODE, "ROUND_HALF_EVEN");
    properties.put(BigQuerySchemaPropertiesMetadata.IS_CASE_INSENSITIVE, "true");
    properties.put(BigQuerySchemaPropertiesMetadata.FRIENDLY_NAME, "Test Dataset");

    sql = operations.generateCreateDatabaseSql("test_dataset", "Test description", properties);
    assertTrue(sql.contains("CREATE SCHEMA `test_dataset`"));
    assertTrue(sql.contains("OPTIONS"));
    assertTrue(sql.contains("location=\"us-central1\""));
    assertTrue(sql.contains("default_table_expiration_days=30"));
    assertTrue(sql.contains("default_partition_expiration_days=365"));
    assertTrue(sql.contains("storage_billing_model=\"LOGICAL\""));
    assertTrue(sql.contains("max_time_travel_hours=168"));
    assertTrue(sql.contains("default_rounding_mode=\"ROUND_HALF_EVEN\""));
    assertTrue(sql.contains("is_case_insensitive=true"));
    assertTrue(sql.contains("friendly_name=\"Test Dataset\""));
    assertTrue(sql.contains("description=\"Test description\""));

    // Test with default collation
    properties.put(BigQuerySchemaPropertiesMetadata.DEFAULT_COLLATION, "und:ci");
    sql = operations.generateCreateDatabaseSql("test_dataset", "Test description", properties);
    assertTrue(sql.contains("DEFAULT COLLATE 'und:ci'"));
  }

  @Test
  void testGenerateDropDatabaseSql() {
    // Test DROP SCHEMA without cascade
    String sql = operations.generateDropDatabaseSql("test_dataset", false);
    assertEquals("DROP SCHEMA `test_dataset` RESTRICT", sql);

    // Test DROP SCHEMA with cascade
    sql = operations.generateDropDatabaseSql("test_dataset", true);
    assertEquals("DROP SCHEMA `test_dataset` CASCADE", sql);
  }

  @Test
  void testGenerateCreateDatabaseSqlWithLabels() {
    // Test with labels in JSON format
    Map<String, String> properties = new HashMap<>();
    properties.put(BigQuerySchemaPropertiesMetadata.LOCATION, "us-central1");
    properties.put(
        BigQuerySchemaPropertiesMetadata.LABELS,
        "[{\"environment\":\"test\"},{\"department\":\"data-engineering\"}]");

    String sql =
        operations.generateCreateDatabaseSql("test_dataset", "Test with labels", properties);
    assertTrue(sql.contains("CREATE SCHEMA `test_dataset`"));
    assertTrue(sql.contains("location=\"us-central1\""));
    assertTrue(
        sql.contains("labels=[(\"environment\",\"test\"),(\"department\",\"data-engineering\")]"));
    assertTrue(sql.contains("description=\"Test with labels\""));
  }

  @Test
  void testGenerateCreateDatabaseSqlWithKmsKey() {
    // Test with KMS key
    Map<String, String> properties = new HashMap<>();
    properties.put(
        BigQuerySchemaPropertiesMetadata.DEFAULT_KMS_KEY_NAME,
        "projects/my-project/locations/us-central1/keyRings/my-ring/cryptoKeys/my-key");

    String sql = operations.generateCreateDatabaseSql("test_dataset", null, properties);
    assertTrue(sql.contains("CREATE SCHEMA `test_dataset`"));
    assertTrue(
        sql.contains(
            "default_kms_key_name=\"projects/my-project/locations/us-central1/keyRings/my-ring/cryptoKeys/my-key\""));
  }

  @Test
  void testSupportSchemaComment() {
    assertTrue(operations.supportSchemaComment());
  }

  @Test
  void testCreateSysDatabaseNameSet() {
    assertTrue(operations.createSysDatabaseNameSet().contains("information_schema"));
    assertTrue(operations.createSysDatabaseNameSet().contains("INFORMATION_SCHEMA"));
  }
}
