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

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.google.common.collect.ImmutableMap;
import java.time.Instant;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import org.apache.gravitino.Catalog;
import org.apache.gravitino.NameIdentifier;
import org.apache.gravitino.Namespace;
import org.apache.gravitino.connector.BaseCatalog;
import org.apache.gravitino.connector.CatalogInfo;
import org.apache.gravitino.connector.SupportsAtomicIntegrity;
import org.apache.gravitino.exceptions.GravitinoRuntimeException;
import org.apache.gravitino.exceptions.MetadataIntegrityException;
import org.apache.gravitino.iceberg.common.IcebergConfig;
import org.apache.gravitino.iceberg.common.ops.IcebergCatalogWrapper;
import org.apache.gravitino.iceberg.common.utils.IcebergCatalogUtil;
import org.apache.gravitino.meta.AuditInfo;
import org.apache.iceberg.rest.responses.ListNamespacesResponse;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

public class TestIcebergCatalogOperations {

  private static final String METALAKE = "metalake";
  private static final String CATALOG = "catalog";

  @Test
  public void testTestConnection() {
    IcebergCatalogOperations catalogOperations = new IcebergCatalogOperations();
    Exception exception =
        Assertions.assertThrows(
            GravitinoRuntimeException.class,
            () ->
                catalogOperations.testConnection(
                    NameIdentifier.of(METALAKE, CATALOG),
                    Catalog.Type.RELATIONAL,
                    "iceberg",
                    "comment",
                    ImmutableMap.of()));
    Assertions.assertTrue(
        exception.getMessage().contains("Failed to run listNamespace on Iceberg catalog"));
  }

  @Test
  public void testInitializeCarriesGovernedEncryptionStatePrivately() {
    Map<String, String> properties =
        ImmutableMap.of(
            IcebergConstants.CATALOG_BACKEND,
            "memory",
            IcebergConstants.ENCRYPTION_KMS_SOURCE,
            "openbao");
    CatalogInfo catalogInfo =
        new CatalogInfo(
            1L,
            CATALOG,
            Catalog.Type.RELATIONAL,
            "lakehouse-iceberg",
            null,
            properties,
            AuditInfo.builder().withCreator("test").withCreateTime(Instant.now()).build(),
            Namespace.of(METALAKE));

    try (IcebergCatalogOperations catalogOperations = new IcebergCatalogOperations()) {
      catalogOperations.initialize(
          properties, catalogInfo, TestIcebergCatalog.ICEBERG_PROPERTIES_METADATA);

      IcebergConfig config = catalogOperations.icebergCatalogWrapper.getIcebergConfig();
      Assertions.assertTrue(config.isGovernedEncryptionConfigured());
      Assertions.assertFalse(
          config.getIcebergCatalogProperties().containsKey(IcebergConstants.ENCRYPTION_KMS_SOURCE));
    }
  }

  /**
   * R2: Gravitino declares no format version on the Iceberg catalog it loads, whether or not the
   * catalog binds a {@code table-default.}/{@code table-override.} encryption key in either
   * spelling. The operator's own {@code table-default.format-version} is the only source, so
   * nothing here raises a version behind their back; admission refuses a keyed create below 3
   * instead.
   */
  @Test
  public void testInitializeDeclaresNoFormatVersionWhetherOrNotTheCatalogBindsAKey() {
    Map<String, String> unkeyed =
        ImmutableMap.of(
            IcebergConstants.CATALOG_BACKEND,
            "memory",
            IcebergConstants.ENCRYPTION_KMS_SOURCE,
            "openbao");
    List<Map<String, String>> catalogs =
        Arrays.asList(
            unkeyed,
            keyedWith(unkeyed, IcebergEncryptionCatalogBinding.TABLE_DEFAULT_KEY_ID),
            keyedWith(
                unkeyed,
                BaseCatalog.CATALOG_BYPASS_PREFIX
                    + IcebergEncryptionCatalogBinding.TABLE_OVERRIDE_KEY_ID));

    for (Map<String, String> properties : catalogs) {
      try (IcebergCatalogOperations catalogOperations = new IcebergCatalogOperations()) {
        catalogOperations.initialize(
            properties, catalogInfo(properties), TestIcebergCatalog.ICEBERG_PROPERTIES_METADATA);

        IcebergConfig config = catalogOperations.icebergCatalogWrapper.getIcebergConfig();
        Assertions.assertFalse(
            config
                .getIcebergCatalogProperties()
                .containsKey(IcebergFormatVersionResolution.TABLE_DEFAULT_FORMAT_VERSION),
            properties.toString());
      }
    }
  }

  private static Map<String, String> keyedWith(Map<String, String> base, String keyProperty) {
    return ImmutableMap.<String, String>builder()
        .putAll(base)
        .put(keyProperty, "catalog-key")
        .build();
  }

  private static CatalogInfo catalogInfo(Map<String, String> properties) {
    return new CatalogInfo(
        1L,
        CATALOG,
        Catalog.Type.RELATIONAL,
        "lakehouse-iceberg",
        null,
        properties,
        AuditInfo.builder().withCreator("test").withCreateTime(Instant.now()).build(),
        Namespace.of(METALAKE));
  }

  /**
   * The capability core reads has a single source: the initialized catalog. The operations object
   * only relays what the wrapper answers, and it declares the core interface directly so a proxy
   * that copies declared interfaces cannot drop it.
   */
  @Test
  public void testAtomicIntegrityAvailableRelaysTheWrapperAnswer() {
    IcebergCatalogWrapper mockWrapper = mock(IcebergCatalogWrapper.class);
    IcebergCatalogOperations catalogOperations = new IcebergCatalogOperations();

    Assertions.assertFalse(
        catalogOperations.atomicIntegrityAvailable(), "an uninitialized catalog reports nothing");

    catalogOperations.icebergCatalogWrapper = mockWrapper;
    when(mockWrapper.atomicIntegrityAvailable()).thenReturn(true);
    Assertions.assertTrue(catalogOperations.atomicIntegrityAvailable());

    when(mockWrapper.atomicIntegrityAvailable()).thenReturn(false);
    Assertions.assertFalse(catalogOperations.atomicIntegrityAvailable());

    Assertions.assertTrue(
        Arrays.asList(IcebergCatalogOperations.class.getInterfaces())
            .contains(SupportsAtomicIntegrity.class),
        "SupportsAtomicIntegrity must be declared directly on the operations class");
  }

  @Test
  public void testPurgePreservesDirectMetadataIntegrityType() {
    IcebergCatalogWrapper mockWrapper = mock(IcebergCatalogWrapper.class);
    IcebergCatalogOperations catalogOperations = new IcebergCatalogOperations();
    catalogOperations.icebergCatalogWrapper = mockWrapper;
    MetadataIntegrityException integrityFailure =
        new MetadataIntegrityException("unexpected digest detail");
    NameIdentifier tableIdent = NameIdentifier.of(METALAKE, CATALOG, "schema", "table");
    doThrow(integrityFailure).when(mockWrapper).purgeTable(any());

    MetadataIntegrityException thrown =
        Assertions.assertThrows(
            MetadataIntegrityException.class, () -> catalogOperations.purgeTable(tableIdent));

    Assertions.assertSame(integrityFailure, thrown);
  }

  @Test
  public void testListSchemasConvertsMultiLevelNamespacesToLogicalNames() {
    IcebergCatalogWrapper mockWrapper = mock(IcebergCatalogWrapper.class);
    IcebergCatalogOperations catalogOperations = new IcebergCatalogOperations();
    catalogOperations.icebergCatalogWrapper = mockWrapper;

    org.apache.iceberg.catalog.Namespace flatNs = org.apache.iceberg.catalog.Namespace.of("mydb");
    org.apache.iceberg.catalog.Namespace hierarchicalNs =
        org.apache.iceberg.catalog.Namespace.of("A", "B", "C");
    ListNamespacesResponse mockResponse =
        ListNamespacesResponse.builder().addAll(Arrays.asList(flatNs, hierarchicalNs)).build();
    when(mockWrapper.listNamespace(any())).thenReturn(mockResponse);

    NameIdentifier[] result = catalogOperations.listSchemas(Namespace.of(METALAKE, CATALOG));

    Assertions.assertEquals(2, result.length);
    // Flat namespace stays as-is.
    Assertions.assertTrue(Arrays.stream(result).anyMatch(id -> "mydb".equals(id.name())));
    // Multi-level Iceberg namespace is joined with the configured separator.
    Assertions.assertTrue(Arrays.stream(result).anyMatch(id -> "A:B:C".equals(id.name())));
  }

  @Test
  public void testListSchemasFlatOnlyReturnsUnchangedNames() {
    IcebergCatalogWrapper mockWrapper = mock(IcebergCatalogWrapper.class);
    IcebergCatalogOperations catalogOperations = new IcebergCatalogOperations();
    catalogOperations.icebergCatalogWrapper = mockWrapper;

    org.apache.iceberg.catalog.Namespace ns1 = org.apache.iceberg.catalog.Namespace.of("db1");
    org.apache.iceberg.catalog.Namespace ns2 = org.apache.iceberg.catalog.Namespace.of("db2");
    ListNamespacesResponse mockResponse =
        ListNamespacesResponse.builder().addAll(Arrays.asList(ns1, ns2)).build();
    when(mockWrapper.listNamespace(any())).thenReturn(mockResponse);

    NameIdentifier[] result = catalogOperations.listSchemas(Namespace.of(METALAKE, CATALOG));

    Assertions.assertEquals(2, result.length);
    Assertions.assertTrue(Arrays.stream(result).anyMatch(id -> "db1".equals(id.name())));
    Assertions.assertTrue(Arrays.stream(result).anyMatch(id -> "db2".equals(id.name())));
  }

  @Test
  public void testCatalogDropRemovesInternedMemoryCatalog() {
    String catalogUuid = "catalog-uuid";
    IcebergConfig config =
        new IcebergConfig(
            ImmutableMap.of(
                IcebergConstants.CATALOG_BACKEND,
                "memory",
                IcebergConstants.CATALOG_UUID,
                catalogUuid));
    Object original = IcebergCatalogUtil.loadCatalogBackend(IcebergCatalogBackend.MEMORY, config);

    IcebergCatalogOperations catalogOperations = new IcebergCatalogOperations();
    catalogOperations.catalogUuid = catalogUuid;
    catalogOperations.onCatalogDropped();

    Object replacement =
        IcebergCatalogUtil.loadCatalogBackend(IcebergCatalogBackend.MEMORY, config);
    Assertions.assertNotSame(original, replacement);
    IcebergCatalogUtil.removeMemoryCatalog(catalogUuid);
  }
}
