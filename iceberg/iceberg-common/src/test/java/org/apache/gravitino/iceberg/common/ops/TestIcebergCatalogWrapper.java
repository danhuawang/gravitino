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
package org.apache.gravitino.iceberg.common.ops;

import java.io.IOException;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Collections;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import org.apache.gravitino.catalog.lakehouse.iceberg.IcebergConstants;
import org.apache.gravitino.iceberg.common.IcebergConfig;
import org.apache.gravitino.iceberg.common.cache.SupportsMetadataLocation;
import org.apache.gravitino.iceberg.common.cache.TableMetadataCache;
import org.apache.gravitino.iceberg.common.utils.IcebergCatalogUtil;
import org.apache.iceberg.PartitionSpec;
import org.apache.iceberg.Schema;
import org.apache.iceberg.TableMetadata;
import org.apache.iceberg.TableMetadataParser;
import org.apache.iceberg.TableProperties;
import org.apache.iceberg.catalog.Catalog;
import org.apache.iceberg.catalog.Namespace;
import org.apache.iceberg.catalog.TableIdentifier;
import org.apache.iceberg.hive.HiveCatalogWithMetadataLocationSupport;
import org.apache.iceberg.jdbc.JdbcCatalogWithMetadataLocationSupport;
import org.apache.iceberg.rest.requests.CreateNamespaceRequest;
import org.apache.iceberg.types.Types;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.Mockito;

public class TestIcebergCatalogWrapper {

  @Test
  public void testCatalogShouldBeLazyLoaded() {
    IcebergCatalogWrapper wrapper =
        new IcebergCatalogWrapper(new IcebergConfig(unreachableConfig()));

    Assertions.assertThrows(Throwable.class, wrapper::getCatalog);
  }

  @Test
  public void testCloseShouldNotInitializeCatalog() {
    IcebergCatalogWrapper wrapper =
        new IcebergCatalogWrapper(new IcebergConfig(unreachableConfig()));

    Assertions.assertDoesNotThrow(
        () -> {
          wrapper.close();
        });
  }

  @Test
  public void testMemoryCatalogSurvivesWrapperClose() throws Exception {
    String catalogUuid = "catalog-1";
    IcebergConfig config =
        new IcebergConfig(
            Map.of(
                IcebergConstants.CATALOG_BACKEND,
                "memory",
                IcebergConstants.CATALOG_UUID,
                catalogUuid));
    IcebergCatalogWrapper firstWrapper = new IcebergCatalogWrapper(config);
    Catalog firstCatalog = firstWrapper.getCatalog();
    Namespace namespace = Namespace.of("preserved");
    firstWrapper.createNamespace(CreateNamespaceRequest.builder().withNamespace(namespace).build());

    firstWrapper.close();
    IcebergCatalogWrapper secondWrapper = new IcebergCatalogWrapper(config);

    Assertions.assertSame(firstCatalog, secondWrapper.getCatalog());
    Assertions.assertTrue(secondWrapper.namespaceExists(namespace));
    IcebergCatalogUtil.removeMemoryCatalog(catalogUuid);
  }

  @Test
  public void testMetadataCacheShouldInitializeOnFirstAccessAndClose(@TempDir Path warehouseDir)
      throws Exception {
    TrackingTableMetadataCache.reset();
    IcebergCatalogWrapper wrapper =
        new IcebergCatalogWrapper(new IcebergConfig(metadataConfig(warehouseDir)));

    Assertions.assertEquals(0, TrackingTableMetadataCache.INITIALIZE_COUNT.get());

    TableMetadataCache cache = invokeGetMetadataCache(wrapper);
    Assertions.assertNotNull(cache);
    Assertions.assertEquals(1, TrackingTableMetadataCache.INITIALIZE_COUNT.get());
    Assertions.assertFalse(TrackingTableMetadataCache.CLOSED.get());

    wrapper.close();

    Assertions.assertEquals(1, TrackingTableMetadataCache.INITIALIZE_COUNT.get());
    Assertions.assertTrue(TrackingTableMetadataCache.CLOSED.get());
  }

  @Test
  public void testAtomicIntegrityIsTrueForHiveWithoutAskingTheBackend() throws Exception {
    // Hive publishes the pointer and the hash under the HMS lock, so the answer is unconditional.
    HiveCatalogWithMetadataLocationSupport hive =
        Mockito.mock(HiveCatalogWithMetadataLocationSupport.class);

    Assertions.assertTrue(wrapperWithLoadedCatalog(hive).atomicIntegrityAvailable());
    Mockito.verifyNoInteractions(hive);
  }

  @Test
  public void testAtomicIntegrityForJdbcIsTheCatalogsOwnAnswer() throws Exception {
    for (boolean supported : new boolean[] {true, false}) {
      JdbcCatalogWithMetadataLocationSupport jdbc =
          Mockito.mock(JdbcCatalogWithMetadataLocationSupport.class);
      Mockito.when(jdbc.supportsAtomicMetadataIntegrity()).thenReturn(supported);

      Assertions.assertEquals(supported, wrapperWithLoadedCatalog(jdbc).atomicIntegrityAvailable());
      Mockito.verify(jdbc).supportsAtomicMetadataIntegrity();
    }
  }

  @Test
  public void testAtomicIntegrityIsFalseForEveryOtherBackend() throws Exception {
    Catalog other = Mockito.mock(Catalog.class);

    Assertions.assertFalse(wrapperWithLoadedCatalog(other).atomicIntegrityAvailable());
    Mockito.verifyNoInteractions(other);
  }

  /**
   * Returns a wrapper whose catalog is already loaded, so the capability is read from the backend
   * class rather than from a connection this test does not have.
   */
  private static IcebergCatalogWrapper wrapperWithLoadedCatalog(Catalog loadedCatalog)
      throws Exception {
    IcebergCatalogWrapper wrapper =
        new IcebergCatalogWrapper(new IcebergConfig(unreachableConfig()));
    Field field = IcebergCatalogWrapper.class.getDeclaredField("catalog");
    field.setAccessible(true);
    field.set(wrapper, loadedCatalog);
    return wrapper;
  }

  @Test
  public void testLoadTableMetadataFromLocation(@TempDir Path tempDir) throws Exception {
    Schema schema = new Schema(Types.NestedField.required(1, "id", Types.LongType.get()));
    TableMetadata expected =
        TableMetadata.newTableMetadata(
            schema,
            PartitionSpec.unpartitioned(),
            tempDir.resolve("table").toUri().toString(),
            Map.of());
    Path metadataFile = tempDir.resolve("v1.metadata.json");
    Files.writeString(metadataFile, TableMetadataParser.toJson(expected), StandardCharsets.UTF_8);
    IcebergCatalogWrapper wrapper =
        new IcebergCatalogWrapper(
            new IcebergConfig(Map.of(IcebergConstants.CATALOG_BACKEND, "memory")));

    TableMetadata actual = wrapper.loadTableMetadataFromLocation(metadataFile.toUri().toString());

    Assertions.assertEquals(schema.asStruct(), actual.schema().asStruct());
  }

  private static TableMetadataCache invokeGetMetadataCache(IcebergCatalogWrapper wrapper)
      throws Exception {
    Method method = IcebergCatalogWrapper.class.getDeclaredMethod("getMetadataCache");
    method.setAccessible(true);
    return (TableMetadataCache) method.invoke(wrapper);
  }

  private static Map<String, String> unreachableConfig() {
    Map<String, String> config = new HashMap<>();
    config.put(IcebergConstants.CATALOG_BACKEND, "jdbc");
    config.put(IcebergConstants.URI, "jdbc:invalid://unreachable");
    config.put(IcebergConstants.WAREHOUSE, "unused");
    return config;
  }

  private static Map<String, String> metadataConfig(Path warehouseDir) {
    Map<String, String> config = new HashMap<>();
    config.put(IcebergConstants.CATALOG_BACKEND, "jdbc");
    config.put(IcebergConstants.URI, "jdbc:sqlite::memory:");
    config.put(IcebergConstants.WAREHOUSE, warehouseDir.toString());
    config.put(IcebergConstants.GRAVITINO_JDBC_DRIVER, "org.sqlite.JDBC");
    config.put(IcebergConstants.ICEBERG_JDBC_USER, "test");
    config.put(IcebergConstants.ICEBERG_JDBC_PASSWORD, "test");
    config.put(IcebergConstants.ICEBERG_JDBC_INITIALIZE, "true");
    config.put(
        IcebergConstants.TABLE_METADATA_CACHE_IMPL, TrackingTableMetadataCache.class.getName());
    return config;
  }

  public static class TrackingTableMetadataCache implements TableMetadataCache {
    private static final AtomicInteger INITIALIZE_COUNT = new AtomicInteger();
    private static final AtomicBoolean CLOSED = new AtomicBoolean();

    static void reset() {
      INITIALIZE_COUNT.set(0);
      CLOSED.set(false);
    }

    @Override
    public void initialize(
        int capacity,
        int expireMinutes,
        Map<String, String> catalogProperties,
        SupportsMetadataLocation supportsMetadataLocation) {
      INITIALIZE_COUNT.incrementAndGet();
    }

    @Override
    public void invalidate(TableIdentifier tableIdentifier) {}

    @Override
    public Optional<TableMetadata> getTableMetadata(TableIdentifier tableIdentifier) {
      return Optional.empty();
    }

    @Override
    public void updateTableMetadata(TableIdentifier tableIdentifier, TableMetadata tableMetadata) {}

    @Override
    public void close() throws IOException {
      CLOSED.set(true);
    }
  }

  @Test
  public void testReadTableMetadataUsesConfiguredFileIO(@TempDir Path tempDir) throws IOException {
    String keyId = "customer-key-v1";
    TableMetadata metadata =
        TableMetadata.newTableMetadata(
                new Schema(Types.NestedField.required(1, "id", Types.LongType.get())),
                PartitionSpec.unpartitioned(),
                tempDir.resolve("table").toUri().toString(),
                Collections.emptyMap())
            .upgradeToFormatVersion(3)
            .replaceProperties(
                Collections.singletonMap(TableProperties.ENCRYPTION_TABLE_KEY, keyId));
    Path metadataFile = tempDir.resolve("v1.metadata.json");
    Files.write(
        metadataFile, TableMetadataParser.toJson(metadata).getBytes(StandardCharsets.UTF_8));
    IcebergCatalogWrapper wrapper =
        new IcebergCatalogWrapper(
            new IcebergConfig(Map.of(IcebergConstants.CATALOG_BACKEND, "memory")));

    String location = metadataFile.toUri().toString();
    TableMetadata read = wrapper.readTableMetadata(location);

    Assertions.assertEquals(location, read.metadataFileLocation());
    Assertions.assertEquals(keyId, read.properties().get(TableProperties.ENCRYPTION_TABLE_KEY));
  }
}
