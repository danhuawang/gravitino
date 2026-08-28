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
import org.apache.gravitino.connector.PropertyEntry;
import org.apache.gravitino.iceberg.common.IcebergConfig;
import org.apache.gravitino.iceberg.common.cache.LocalTableMetadataCache;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

public class TestIcebergCatalogPropertiesMetadata {

  private IcebergCatalogPropertiesMetadata metadata;

  @BeforeEach
  void setUp() {
    metadata = new IcebergCatalogPropertiesMetadata();
  }

  @Test
  void testTableMetadataCacheImplDefaultValue() {
    Assertions.assertEquals(
        LocalTableMetadataCache.class.getName(),
        metadata.getDefaultValue(IcebergConstants.TABLE_METADATA_CACHE_IMPL));
    Assertions.assertEquals(
        IcebergConfig.TABLE_METADATA_CACHE_IMPL.getDefaultValue(),
        metadata.getDefaultValue(IcebergConstants.TABLE_METADATA_CACHE_IMPL));
  }

  @Test
  void testTableMetadataCacheCapacityDefaultValue() {
    Assertions.assertEquals(
        1000, metadata.getDefaultValue(IcebergConstants.TABLE_METADATA_CACHE_CAPACITY));
    Assertions.assertEquals(
        IcebergConfig.TABLE_METADATA_CACHE_CAPACITY.getDefaultValue(),
        metadata.getDefaultValue(IcebergConstants.TABLE_METADATA_CACHE_CAPACITY));
  }

  @Test
  void testTableMetadataCacheDefaultsViaGetOrDefault() {
    Map<String, String> catalogProperties =
        ImmutableMap.of(
            IcebergCatalogPropertiesMetadata.CATALOG_BACKEND,
            "hive",
            IcebergCatalogPropertiesMetadata.URI,
            "thrift://127.0.0.1:9083",
            IcebergCatalogPropertiesMetadata.WAREHOUSE,
            "/tmp/warehouse");

    Assertions.assertEquals(
        IcebergConfig.TABLE_METADATA_CACHE_IMPL.getDefaultValue(),
        metadata.getOrDefault(catalogProperties, IcebergConstants.TABLE_METADATA_CACHE_IMPL));
    Assertions.assertEquals(
        IcebergConfig.TABLE_METADATA_CACHE_CAPACITY.getDefaultValue(),
        metadata.getOrDefault(catalogProperties, IcebergConstants.TABLE_METADATA_CACHE_CAPACITY));
  }

  @Test
  void testRESTCatalogBackendClientTimeoutDefaultValues() {
    Assertions.assertEquals(
        IcebergConfig.REST_CATALOG_BACKEND_CLIENT_CONNECTION_TIMEOUT_MS.getDefaultValue(),
        metadata.getDefaultValue(
            IcebergConstants.REST_CATALOG_BACKEND_CLIENT_CONNECTION_TIMEOUT_MS));
    Assertions.assertEquals(
        IcebergConfig.REST_CATALOG_BACKEND_CLIENT_SOCKET_TIMEOUT_MS.getDefaultValue(),
        metadata.getDefaultValue(IcebergConstants.REST_CATALOG_BACKEND_CLIENT_SOCKET_TIMEOUT_MS));
  }

  @Test
  void testRESTCatalogBackendClientTimeoutsViaGetOrDefault() {
    Map<String, String> catalogProperties =
        ImmutableMap.of(
            IcebergCatalogPropertiesMetadata.CATALOG_BACKEND,
            "rest",
            IcebergCatalogPropertiesMetadata.URI,
            "http://127.0.0.1:9001/iceberg");

    Assertions.assertEquals(
        IcebergConfig.REST_CATALOG_BACKEND_CLIENT_CONNECTION_TIMEOUT_MS.getDefaultValue(),
        metadata.getOrDefault(
            catalogProperties, IcebergConstants.REST_CATALOG_BACKEND_CLIENT_CONNECTION_TIMEOUT_MS));
    Assertions.assertEquals(
        IcebergConfig.REST_CATALOG_BACKEND_CLIENT_SOCKET_TIMEOUT_MS.getDefaultValue(),
        metadata.getOrDefault(
            catalogProperties, IcebergConstants.REST_CATALOG_BACKEND_CLIENT_SOCKET_TIMEOUT_MS));

    catalogProperties =
        ImmutableMap.of(
            IcebergCatalogPropertiesMetadata.CATALOG_BACKEND,
            "rest",
            IcebergCatalogPropertiesMetadata.URI,
            "http://127.0.0.1:9001/iceberg",
            IcebergConstants.REST_CATALOG_BACKEND_CLIENT_CONNECTION_TIMEOUT_MS,
            "1234",
            IcebergConstants.REST_CATALOG_BACKEND_CLIENT_SOCKET_TIMEOUT_MS,
            "5678");

    Assertions.assertEquals(
        1234,
        metadata.getOrDefault(
            catalogProperties, IcebergConstants.REST_CATALOG_BACKEND_CLIENT_CONNECTION_TIMEOUT_MS));
    Assertions.assertEquals(
        5678,
        metadata.getOrDefault(
            catalogProperties, IcebergConstants.REST_CATALOG_BACKEND_CLIENT_SOCKET_TIMEOUT_MS));
  }

  @Test
  void testRESTCatalogBackendClientTimeoutPropertiesAreTransformed() {
    Map<String, String> catalogProperties =
        ImmutableMap.of(
            IcebergConstants.REST_CATALOG_BACKEND_CLIENT_CONNECTION_TIMEOUT_MS,
            "1234",
            IcebergConstants.REST_CATALOG_BACKEND_CLIENT_SOCKET_TIMEOUT_MS,
            "5678");

    Map<String, String> transformedProperties = metadata.transformProperties(catalogProperties);

    Assertions.assertEquals(
        "1234",
        transformedProperties.get(IcebergConstants.ICEBERG_REST_CLIENT_CONNECTION_TIMEOUT_MS));
    Assertions.assertEquals(
        "5678", transformedProperties.get(IcebergConstants.ICEBERG_REST_CLIENT_SOCKET_TIMEOUT_MS));
  }

  @Test
  void testEncryptionKmsSourceMetadata() {
    Assertions.assertTrue(metadata.containsProperty(IcebergConstants.ENCRYPTION_KMS_SOURCE));
    Assertions.assertTrue(metadata.isImmutableProperty(IcebergConstants.ENCRYPTION_KMS_SOURCE));
    Assertions.assertFalse(metadata.isRequiredProperty(IcebergConstants.ENCRYPTION_KMS_SOURCE));
    Assertions.assertFalse(metadata.isHiddenProperty(IcebergConstants.ENCRYPTION_KMS_SOURCE));
    Assertions.assertNull(metadata.getDefaultValue(IcebergConstants.ENCRYPTION_KMS_SOURCE));

    PropertyEntry<?> entry = metadata.propertyEntries().get(IcebergConstants.ENCRYPTION_KMS_SOURCE);
    Assertions.assertNotNull(entry);
    Assertions.assertFalse(entry.isReserved());
    Assertions.assertEquals(String.class, entry.getJavaType());
  }

  @Test
  void testEncryptionKmsSourceRejectsBlankValues() {
    for (String value : new String[] {"", "   "}) {
      Map<String, String> properties =
          ImmutableMap.of(IcebergConstants.ENCRYPTION_KMS_SOURCE, value);
      IllegalArgumentException exception =
          Assertions.assertThrows(
              IllegalArgumentException.class,
              () -> metadata.getOrDefault(properties, IcebergConstants.ENCRYPTION_KMS_SOURCE));
      Assertions.assertTrue(exception.getMessage().contains("cannot be blank"));
    }
  }

  @Test
  void testEncryptionKmsSourceRejectsUnresolvableNames() {
    for (String value :
        new String[] {"bad.name", "bad/name", "-bad", "bad source", " openbao", "openbao "}) {
      Map<String, String> properties =
          ImmutableMap.of(IcebergConstants.ENCRYPTION_KMS_SOURCE, value);
      IllegalArgumentException exception =
          Assertions.assertThrows(
              IllegalArgumentException.class,
              () -> metadata.getOrDefault(properties, IcebergConstants.ENCRYPTION_KMS_SOURCE));
      Assertions.assertTrue(exception.getMessage().contains("must match"));
    }
  }

  @Test
  void testEncryptionKmsSourceRemainsGravitinoOnly() {
    Map<String, String> properties =
        ImmutableMap.of(IcebergConstants.ENCRYPTION_KMS_SOURCE, "openbao");

    Assertions.assertEquals(
        "openbao", metadata.getOrDefault(properties, IcebergConstants.ENCRYPTION_KMS_SOURCE));
    Map<String, String> transformedProperties = metadata.transformProperties(properties);

    Assertions.assertFalse(
        transformedProperties.containsKey(IcebergConstants.ENCRYPTION_KMS_SOURCE));
    Assertions.assertFalse(transformedProperties.containsValue("openbao"));
  }
}
