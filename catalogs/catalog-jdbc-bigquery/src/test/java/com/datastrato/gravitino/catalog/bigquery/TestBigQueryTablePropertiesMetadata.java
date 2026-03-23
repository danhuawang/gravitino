/*
 * Copyright 2026 Datastrato Pvt Ltd.
 * This software is licensed under the Apache License version 2.
 */

package com.datastrato.gravitino.catalog.bigquery;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.datastrato.gravitino.catalog.bigquery.utils.BigQueryUtils;
import java.util.Map;
import org.apache.gravitino.connector.PropertyEntry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/** Unit tests for {@link BigQueryTablePropertiesMetadata}. */
public class TestBigQueryTablePropertiesMetadata {

  private BigQueryTablePropertiesMetadata metadata;

  @BeforeEach
  void setUp() {
    metadata = new BigQueryTablePropertiesMetadata();
  }

  @Test
  void testGetPropertyEntries() {
    Map<String, PropertyEntry<?>> properties = metadata.propertyEntries();

    // Check that all expected BigQuery-specific properties are present
    assertTrue(properties.containsKey(BigQueryTablePropertiesMetadata.DESCRIPTION));
    assertTrue(properties.containsKey(BigQueryTablePropertiesMetadata.FRIENDLY_NAME));
    assertTrue(properties.containsKey(BigQueryTablePropertiesMetadata.PARTITION_EXPIRATION_DAYS));
    assertTrue(properties.containsKey(BigQueryTablePropertiesMetadata.REQUIRE_PARTITION_FILTER));
    assertTrue(properties.containsKey(BigQueryTablePropertiesMetadata.CLUSTERING_FIELDS));
    assertTrue(properties.containsKey(BigQueryTablePropertiesMetadata.EXPIRATION_TIMESTAMP));
    assertTrue(properties.containsKey(BigQueryTablePropertiesMetadata.LABELS));

    // Check that all properties are optional
    assertFalse(properties.get(BigQueryTablePropertiesMetadata.DESCRIPTION).isRequired());
    assertFalse(properties.get(BigQueryTablePropertiesMetadata.FRIENDLY_NAME).isRequired());
    assertFalse(
        properties.get(BigQueryTablePropertiesMetadata.PARTITION_EXPIRATION_DAYS).isRequired());
    assertFalse(
        properties.get(BigQueryTablePropertiesMetadata.REQUIRE_PARTITION_FILTER).isRequired());
    assertFalse(properties.get(BigQueryTablePropertiesMetadata.CLUSTERING_FIELDS).isRequired());
    assertFalse(properties.get(BigQueryTablePropertiesMetadata.EXPIRATION_TIMESTAMP).isRequired());
    assertFalse(properties.get(BigQueryTablePropertiesMetadata.LABELS).isRequired());
  }

  @Test
  void testPropertyDescriptions() {
    Map<String, PropertyEntry<?>> properties = metadata.propertyEntries();

    // Check that properties have meaningful descriptions
    PropertyEntry<?> descriptionEntry = properties.get(BigQueryTablePropertiesMetadata.DESCRIPTION);
    assertTrue(descriptionEntry.getDescription().contains("Table description"));

    PropertyEntry<?> friendlyNameEntry =
        properties.get(BigQueryTablePropertiesMetadata.FRIENDLY_NAME);
    assertTrue(friendlyNameEntry.getDescription().contains("Friendly name"));

    PropertyEntry<?> partitionExpirationEntry =
        properties.get(BigQueryTablePropertiesMetadata.PARTITION_EXPIRATION_DAYS);
    assertTrue(partitionExpirationEntry.getDescription().contains("partitions expire"));

    PropertyEntry<?> requirePartitionFilterEntry =
        properties.get(BigQueryTablePropertiesMetadata.REQUIRE_PARTITION_FILTER);
    assertTrue(requirePartitionFilterEntry.getDescription().contains("partition filter"));

    PropertyEntry<?> clusteringFieldsEntry =
        properties.get(BigQueryTablePropertiesMetadata.CLUSTERING_FIELDS);
    assertTrue(clusteringFieldsEntry.getDescription().contains("clustering field"));

    PropertyEntry<?> expirationTimestampEntry =
        properties.get(BigQueryTablePropertiesMetadata.EXPIRATION_TIMESTAMP);
    assertTrue(expirationTimestampEntry.getDescription().contains("expires"));

    PropertyEntry<?> labelsEntry = properties.get(BigQueryTablePropertiesMetadata.LABELS);
    assertTrue(labelsEntry.getDescription().contains("labels"));
  }

  @Test
  void testValidateClusteringFieldsValid() {
    // Test valid cases
    BigQueryUtils.validateClusteringFields(null);
    BigQueryUtils.validateClusteringFields("");
    BigQueryUtils.validateClusteringFields("   ");
    BigQueryUtils.validateClusteringFields("field1");
    BigQueryUtils.validateClusteringFields("field1,field2");
    BigQueryUtils.validateClusteringFields("field1,field2,field3");
    BigQueryUtils.validateClusteringFields("field1,field2,field3,field4");
    BigQueryUtils.validateClusteringFields("field1, field2, field3, field4");
    BigQueryUtils.validateClusteringFields("field1,  ,field2"); // Empty field should be ignored
  }

  @Test
  void testValidateClusteringFieldsInvalid() {
    // Test invalid case - more than 4 fields
    IllegalArgumentException exception =
        assertThrows(
            IllegalArgumentException.class,
            () -> BigQueryUtils.validateClusteringFields("field1,field2,field3,field4,field5"));

    assertTrue(exception.getMessage().contains("maximum 4 clustering fields"));
    assertTrue(exception.getMessage().contains("but got 5 fields"));
  }

  @Test
  void testValidateClusteringFieldsWithSpaces() {
    // Test with various spacing
    BigQueryUtils.validateClusteringFields(" field1 , field2 , field3 , field4 ");

    // Test with empty fields that should be ignored
    BigQueryUtils.validateClusteringFields("field1,,field2,  ,field3");

    // Test invalid case with spaces
    IllegalArgumentException exception =
        assertThrows(
            IllegalArgumentException.class,
            () ->
                BigQueryUtils.validateClusteringFields(
                    " field1 , field2 , field3 , field4 , field5 "));

    assertTrue(exception.getMessage().contains("maximum 4 clustering fields"));
  }

  @Test
  void testValidateClusteringFieldsEmptyFields() {
    // Test case where all fields are empty (fieldCount = 0)
    IllegalArgumentException exception =
        assertThrows(
            IllegalArgumentException.class,
            () -> BigQueryUtils.validateClusteringFields("  ,  ,  "));

    assertTrue(exception.getMessage().contains("Clustering fields cannot be empty"));

    // Test another case with only commas
    exception =
        assertThrows(
            IllegalArgumentException.class, () -> BigQueryUtils.validateClusteringFields(",,,"));

    assertTrue(exception.getMessage().contains("Clustering fields cannot be empty"));
  }

  @Test
  void testGetMaxClusteringFields() {
    assertEquals(4, BigQueryUtils.getMaxClusteringFields());
  }

  @Test
  void testPropertyConstants() {
    // Test that all property constants are defined correctly
    assertEquals(
        "partition_expiration_days", BigQueryTablePropertiesMetadata.PARTITION_EXPIRATION_DAYS);
    assertEquals(
        "require_partition_filter", BigQueryTablePropertiesMetadata.REQUIRE_PARTITION_FILTER);
    assertEquals("clustering_fields", BigQueryTablePropertiesMetadata.CLUSTERING_FIELDS);
    assertEquals("expiration_timestamp", BigQueryTablePropertiesMetadata.EXPIRATION_TIMESTAMP);
    assertEquals("friendly_name", BigQueryTablePropertiesMetadata.FRIENDLY_NAME);
    assertEquals("description", BigQueryTablePropertiesMetadata.DESCRIPTION);
    assertEquals("labels", BigQueryTablePropertiesMetadata.LABELS);
  }
}
