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
package org.apache.gravitino.catalog.lakehouse.iceberg;

import com.google.common.collect.ImmutableMap;
import java.util.Map;
import java.util.stream.Stream;
import org.apache.gravitino.catalog.PropertiesMetadataHelpers;
import org.apache.gravitino.connector.PropertyEntry;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.ValueSource;

public class TestIcebergTablePropertiesMetadata {

  private IcebergTablePropertiesMetadata metadata;

  @BeforeEach
  void setUp() {
    metadata = new IcebergTablePropertiesMetadata();
  }

  @Test
  void testFormatVersionDefaultsToIcebergDefault() {
    // Gravitino owns the default format version (2).
    Assertions.assertEquals(
        IcebergTablePropertiesMetadata.ICEBERG_DEFAULT_FORMAT_VERSION,
        metadata.getDefaultValue(IcebergTablePropertiesMetadata.FORMAT_VERSION));
  }

  @Test
  void testEmptyFormatVersionResolvesToDefault() {
    // An unset (empty) value resolves to the Gravitino default via the decoder.
    Assertions.assertEquals(
        IcebergTablePropertiesMetadata.ICEBERG_DEFAULT_FORMAT_VERSION,
        metadata.getOrDefault(
            ImmutableMap.of(IcebergTablePropertiesMetadata.FORMAT_VERSION, ""),
            IcebergTablePropertiesMetadata.FORMAT_VERSION));
  }

  @ParameterizedTest
  @ValueSource(strings = {"", "1", "2", "3", "4"})
  void testFormatVersionAcceptsValidValues(String value) {
    // Empty defers to the default; 1-4 are the versions the bundled Iceberg can write.
    Assertions.assertDoesNotThrow(() -> validateFormatVersion(value));
  }

  @ParameterizedTest
  @MethodSource("invalidFormatVersions")
  void testFormatVersionRejectsInvalidValues(String value) {
    Assertions.assertThrows(IllegalArgumentException.class, () -> validateFormatVersion(value));
  }

  @Test
  void testEncryptionPropertiesMetadata() {
    for (String property : encryptionProperties()) {
      Assertions.assertTrue(metadata.containsProperty(property));
      Assertions.assertTrue(metadata.isImmutableProperty(property));
      Assertions.assertFalse(metadata.isRequiredProperty(property));
      Assertions.assertFalse(metadata.isHiddenProperty(property));
      Assertions.assertNull(metadata.getDefaultValue(property));

      PropertyEntry<?> entry = metadata.propertyEntries().get(property);
      Assertions.assertNotNull(entry);
      Assertions.assertFalse(entry.isReserved());
      Assertions.assertEquals(String.class, entry.getJavaType());
    }
  }

  @Test
  void testEncryptionPropertiesRequireNonBlankValues() {
    for (String property : encryptionProperties()) {
      for (String value : new String[] {"", "   "}) {
        Assertions.assertThrows(
            IllegalArgumentException.class,
            () ->
                PropertiesMetadataHelpers.validatePropertyForCreate(
                    metadata, ImmutableMap.of(property, value)));
      }
    }
  }

  @Test
  void testEncryptionPropertiesPreserveOpaqueValuesAndFormatVersion() {
    Map<String, String> properties =
        ImmutableMap.of(
            IcebergTablePropertiesMetadata.ENCRYPTION_KEY_PROVIDER,
            "openbao-production",
            IcebergTablePropertiesMetadata.ENCRYPTION_KEY_ID,
            "transit/customer-key-v1",
            IcebergTablePropertiesMetadata.FORMAT_VERSION,
            "3");

    Assertions.assertDoesNotThrow(
        () -> PropertiesMetadataHelpers.validatePropertyForCreate(metadata, properties));
    Assertions.assertEquals(
        "openbao-production",
        metadata.getOrDefault(properties, IcebergTablePropertiesMetadata.ENCRYPTION_KEY_PROVIDER));
    Assertions.assertEquals(
        "transit/customer-key-v1",
        metadata.getOrDefault(properties, IcebergTablePropertiesMetadata.ENCRYPTION_KEY_ID));
    Assertions.assertEquals(
        3, metadata.getOrDefault(properties, IcebergTablePropertiesMetadata.FORMAT_VERSION));
  }

  @Test
  void testEncryptionPropertiesRejectSetAndRemoveAfterCreate() {
    for (String property : encryptionProperties()) {
      IllegalArgumentException setException =
          Assertions.assertThrows(
              IllegalArgumentException.class,
              () ->
                  PropertiesMetadataHelpers.validatePropertyForAlter(
                      metadata, ImmutableMap.of(property, "replacement"), ImmutableMap.of()));
      Assertions.assertTrue(setException.getMessage().contains("cannot be set"));

      IllegalArgumentException removeException =
          Assertions.assertThrows(
              IllegalArgumentException.class,
              () ->
                  PropertiesMetadataHelpers.validatePropertyForAlter(
                      metadata, ImmutableMap.of(), ImmutableMap.of(property, "original")));
      Assertions.assertTrue(removeException.getMessage().contains("cannot be deleted"));
    }
  }

  private static Stream<String> invalidFormatVersions() {
    // Just outside the range (0, 5) and clearly out of range / non-numeric.
    return Stream.of(
        "0",
        "5",
        "100",
        "-1",
        String.valueOf(Integer.MAX_VALUE),
        String.valueOf(Integer.MIN_VALUE),
        "not-a-number");
  }

  private static String[] encryptionProperties() {
    return new String[] {
      IcebergTablePropertiesMetadata.ENCRYPTION_KEY_PROVIDER,
      IcebergTablePropertiesMetadata.ENCRYPTION_KEY_ID
    };
  }

  private void validateFormatVersion(String value) {
    PropertiesMetadataHelpers.validatePropertyForCreate(
        metadata, ImmutableMap.of(IcebergTablePropertiesMetadata.FORMAT_VERSION, value));
  }
}
