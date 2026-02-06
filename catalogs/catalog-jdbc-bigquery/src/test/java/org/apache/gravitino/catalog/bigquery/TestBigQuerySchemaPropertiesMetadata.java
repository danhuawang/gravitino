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
package org.apache.gravitino.catalog.bigquery;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Map;
import org.apache.gravitino.connector.PropertyEntry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/** Unit tests for BigQuerySchemaPropertiesMetadata. */
public class TestBigQuerySchemaPropertiesMetadata {

  private BigQuerySchemaPropertiesMetadata metadata;

  @BeforeEach
  void setUp() {
    metadata = new BigQuerySchemaPropertiesMetadata();
  }

  @Test
  void testPropertyEntries() {
    Map<String, PropertyEntry<?>> properties = metadata.propertyEntries();
    assertNotNull(properties);

    // Check that all expected properties are present
    assertTrue(properties.containsKey(BigQuerySchemaPropertiesMetadata.LOCATION));
    assertTrue(
        properties.containsKey(BigQuerySchemaPropertiesMetadata.DEFAULT_TABLE_EXPIRATION_DAYS));
    assertTrue(
        properties.containsKey(BigQuerySchemaPropertiesMetadata.DEFAULT_PARTITION_EXPIRATION_DAYS));
    assertTrue(properties.containsKey(BigQuerySchemaPropertiesMetadata.IS_CASE_INSENSITIVE));
    assertTrue(properties.containsKey(BigQuerySchemaPropertiesMetadata.DESCRIPTION));
    assertTrue(properties.containsKey(BigQuerySchemaPropertiesMetadata.LABELS));
    assertTrue(properties.containsKey(BigQuerySchemaPropertiesMetadata.DEFAULT_COLLATION));
    assertTrue(properties.containsKey(BigQuerySchemaPropertiesMetadata.STORAGE_BILLING_MODEL));
    assertTrue(properties.containsKey(BigQuerySchemaPropertiesMetadata.MAX_TIME_TRAVEL_HOURS));
    assertTrue(properties.containsKey(BigQuerySchemaPropertiesMetadata.DEFAULT_KMS_KEY_NAME));
    assertTrue(properties.containsKey(BigQuerySchemaPropertiesMetadata.DEFAULT_ROUNDING_MODE));
    assertTrue(properties.containsKey(BigQuerySchemaPropertiesMetadata.FRIENDLY_NAME));
    assertTrue(properties.containsKey(BigQuerySchemaPropertiesMetadata.FAILOVER_RESERVATION));
    assertTrue(properties.containsKey(BigQuerySchemaPropertiesMetadata.IS_PRIMARY));
    assertTrue(properties.containsKey(BigQuerySchemaPropertiesMetadata.PRIMARY_REPLICA));
    assertTrue(properties.containsKey(BigQuerySchemaPropertiesMetadata.TAGS));
  }

  @Test
  void testLocationProperty() {
    Map<String, PropertyEntry<?>> properties = metadata.propertyEntries();
    PropertyEntry<?> locationProperty = properties.get(BigQuerySchemaPropertiesMetadata.LOCATION);

    assertNotNull(locationProperty);
    assertTrue(locationProperty.isImmutable());
    assertFalse(locationProperty.isRequired());
  }

  @Test
  void testDefaultCollationProperty() {
    Map<String, PropertyEntry<?>> properties = metadata.propertyEntries();
    PropertyEntry<?> collationProperty =
        properties.get(BigQuerySchemaPropertiesMetadata.DEFAULT_COLLATION);

    assertNotNull(collationProperty);
    assertTrue(collationProperty.isImmutable());
    assertFalse(collationProperty.isRequired());
  }

  @Test
  void testMutableProperties() {
    Map<String, PropertyEntry<?>> properties = metadata.propertyEntries();

    // These properties should be mutable
    PropertyEntry<?> expirationProperty =
        properties.get(BigQuerySchemaPropertiesMetadata.DEFAULT_TABLE_EXPIRATION_DAYS);
    assertNotNull(expirationProperty);
    assertFalse(expirationProperty.isImmutable());

    PropertyEntry<?> partitionExpirationProperty =
        properties.get(BigQuerySchemaPropertiesMetadata.DEFAULT_PARTITION_EXPIRATION_DAYS);
    assertNotNull(partitionExpirationProperty);
    assertFalse(partitionExpirationProperty.isImmutable());

    PropertyEntry<?> caseInsensitiveProperty =
        properties.get(BigQuerySchemaPropertiesMetadata.IS_CASE_INSENSITIVE);
    assertNotNull(caseInsensitiveProperty);
    assertFalse(caseInsensitiveProperty.isImmutable());

    PropertyEntry<?> descriptionProperty =
        properties.get(BigQuerySchemaPropertiesMetadata.DESCRIPTION);
    assertNotNull(descriptionProperty);
    assertFalse(descriptionProperty.isImmutable());

    PropertyEntry<?> labelsProperty = properties.get(BigQuerySchemaPropertiesMetadata.LABELS);
    assertNotNull(labelsProperty);
    assertFalse(labelsProperty.isImmutable());

    PropertyEntry<?> storageBillingProperty =
        properties.get(BigQuerySchemaPropertiesMetadata.STORAGE_BILLING_MODEL);
    assertNotNull(storageBillingProperty);
    assertFalse(storageBillingProperty.isImmutable());

    PropertyEntry<?> timeTravelProperty =
        properties.get(BigQuerySchemaPropertiesMetadata.MAX_TIME_TRAVEL_HOURS);
    assertNotNull(timeTravelProperty);
    assertFalse(timeTravelProperty.isImmutable());

    PropertyEntry<?> kmsKeyProperty =
        properties.get(BigQuerySchemaPropertiesMetadata.DEFAULT_KMS_KEY_NAME);
    assertNotNull(kmsKeyProperty);
    assertFalse(kmsKeyProperty.isImmutable());

    PropertyEntry<?> roundingModeProperty =
        properties.get(BigQuerySchemaPropertiesMetadata.DEFAULT_ROUNDING_MODE);
    assertNotNull(roundingModeProperty);
    assertFalse(roundingModeProperty.isImmutable());

    PropertyEntry<?> friendlyNameProperty =
        properties.get(BigQuerySchemaPropertiesMetadata.FRIENDLY_NAME);
    assertNotNull(friendlyNameProperty);
    assertFalse(friendlyNameProperty.isImmutable());
  }
}
