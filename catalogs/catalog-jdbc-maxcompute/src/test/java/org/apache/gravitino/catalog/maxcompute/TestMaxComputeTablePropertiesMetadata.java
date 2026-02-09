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
package org.apache.gravitino.catalog.maxcompute;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Map;
import org.apache.gravitino.connector.PropertyEntry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/** Tests for {@link MaxComputeTablePropertiesMetadata}. */
public class TestMaxComputeTablePropertiesMetadata {

  private MaxComputeTablePropertiesMetadata propertiesMetadata;

  @BeforeEach
  void setUp() {
    propertiesMetadata = new MaxComputeTablePropertiesMetadata();
  }

  @Test
  void testPropertyEntriesNotNull() {
    Map<String, PropertyEntry<?>> entries = propertiesMetadata.propertyEntries();
    assertNotNull(entries);
    assertFalse(entries.isEmpty());
  }

  @Test
  void testLifecycleProperty() {
    Map<String, PropertyEntry<?>> entries = propertiesMetadata.propertyEntries();
    assertTrue(entries.containsKey(MaxComputeTablePropertiesMetadata.LIFECYCLE));

    PropertyEntry<?> lifecycleEntry = entries.get(MaxComputeTablePropertiesMetadata.LIFECYCLE);
    assertNotNull(lifecycleEntry);
    assertFalse(lifecycleEntry.isRequired());
    assertFalse(lifecycleEntry.isImmutable());
    assertEquals(
        MaxComputeTablePropertiesMetadata.DEFAULT_LIFECYCLE, lifecycleEntry.getDefaultValue());
  }

  @Test
  void testTransactionalProperty() {
    Map<String, PropertyEntry<?>> entries = propertiesMetadata.propertyEntries();
    assertTrue(entries.containsKey(MaxComputeTablePropertiesMetadata.TRANSACTIONAL));

    PropertyEntry<?> transactionalEntry =
        entries.get(MaxComputeTablePropertiesMetadata.TRANSACTIONAL);
    assertNotNull(transactionalEntry);
    assertFalse(transactionalEntry.isRequired());
    assertFalse(transactionalEntry.isImmutable());
    assertEquals(false, transactionalEntry.getDefaultValue());
  }

  @Test
  void testWriteBucketNumProperty() {
    Map<String, PropertyEntry<?>> entries = propertiesMetadata.propertyEntries();
    assertTrue(entries.containsKey(MaxComputeTablePropertiesMetadata.WRITE_BUCKET_NUM));

    PropertyEntry<?> bucketNumEntry =
        entries.get(MaxComputeTablePropertiesMetadata.WRITE_BUCKET_NUM);
    assertNotNull(bucketNumEntry);
    assertFalse(bucketNumEntry.isRequired());
    assertFalse(bucketNumEntry.isImmutable());
    assertEquals(
        MaxComputeTablePropertiesMetadata.DEFAULT_WRITE_BUCKET_NUM,
        bucketNumEntry.getDefaultValue());
  }

  @Test
  void testAcidDataRetainHoursProperty() {
    Map<String, PropertyEntry<?>> entries = propertiesMetadata.propertyEntries();
    assertTrue(entries.containsKey(MaxComputeTablePropertiesMetadata.ACID_DATA_RETAIN_HOURS));

    PropertyEntry<?> retainHoursEntry =
        entries.get(MaxComputeTablePropertiesMetadata.ACID_DATA_RETAIN_HOURS);
    assertNotNull(retainHoursEntry);
    assertFalse(retainHoursEntry.isRequired());
    assertFalse(retainHoursEntry.isImmutable());
    assertEquals(
        MaxComputeTablePropertiesMetadata.DEFAULT_ACID_DATA_RETAIN_HOURS,
        retainHoursEntry.getDefaultValue());
  }

  @Test
  void testPropertyDescriptions() {
    Map<String, PropertyEntry<?>> entries = propertiesMetadata.propertyEntries();

    // Verify all properties have descriptions
    for (PropertyEntry<?> entry : entries.values()) {
      assertNotNull(entry.getDescription());
      assertFalse(entry.getDescription().isEmpty());
    }
  }

  @Test
  void testDefaultValues() {
    assertEquals(-1, MaxComputeTablePropertiesMetadata.DEFAULT_LIFECYCLE);
    assertEquals(16, MaxComputeTablePropertiesMetadata.DEFAULT_WRITE_BUCKET_NUM);
    assertEquals(24, MaxComputeTablePropertiesMetadata.DEFAULT_ACID_DATA_RETAIN_HOURS);
    assertEquals(24, MaxComputeTablePropertiesMetadata.MIN_ACID_DATA_RETAIN_HOURS);
    assertEquals(168, MaxComputeTablePropertiesMetadata.MAX_ACID_DATA_RETAIN_HOURS);
    assertEquals(4096, MaxComputeTablePropertiesMetadata.MAX_WRITE_BUCKET_NUM);
  }
}
