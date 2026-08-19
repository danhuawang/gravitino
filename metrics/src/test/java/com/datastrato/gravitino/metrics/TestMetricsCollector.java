/*
 * Copyright 2024 Datastrato Pvt Ltd.
 * This software is licensed under the Apache License version 2.
 */
package com.datastrato.gravitino.metrics;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyMap;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.clearInvocations;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.datastrato.gravitino.metrics.config.MetricsConfig;
import com.datastrato.gravitino.metrics.storage.relational.service.MetricDataService;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.ImmutableSet;
import java.io.IOException;
import java.time.Instant;
import java.util.Collections;
import org.apache.gravitino.Catalog;
import org.apache.gravitino.Configs;
import org.apache.gravitino.Entity;
import org.apache.gravitino.EntityStore;
import org.apache.gravitino.GravitinoEnv;
import org.apache.gravitino.NameIdentifier;
import org.apache.gravitino.authorization.AccessControlDispatcher;
import org.apache.gravitino.authorization.OwnerDispatcher;
import org.apache.gravitino.catalog.CatalogDispatcher;
import org.apache.gravitino.catalog.CatalogManager;
import org.apache.gravitino.catalog.FilesetDispatcher;
import org.apache.gravitino.catalog.ModelDispatcher;
import org.apache.gravitino.catalog.SchemaDispatcher;
import org.apache.gravitino.catalog.TableDispatcher;
import org.apache.gravitino.catalog.TopicDispatcher;
import org.apache.gravitino.connector.capability.Capability;
import org.apache.gravitino.exceptions.NoSuchEntityException;
import org.apache.gravitino.meta.AuditInfo;
import org.apache.gravitino.meta.BaseMetalake;
import org.apache.gravitino.meta.CatalogEntity;
import org.apache.gravitino.meta.RoleEntity;
import org.apache.gravitino.meta.SchemaVersion;
import org.apache.gravitino.meta.TableEntity;
import org.apache.gravitino.meta.UserEntity;
import org.apache.gravitino.metalake.MetalakeDispatcher;
import org.apache.gravitino.server.ServerConfig;
import org.apache.gravitino.tag.TagDispatcher;
import org.apache.gravitino.utils.NameIdentifierUtil;
import org.apache.gravitino.utils.NamespaceUtil;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.MockedStatic;
import org.mockito.Mockito;

class TestMetricsCollector {

  private ServerConfig serverConfig;
  private GravitinoEnv gravitinoEnv;
  private MetalakeDispatcher metalakeDispatcher;
  private CatalogDispatcher catalogDispatcher;
  private CatalogManager catalogManager;
  private SchemaDispatcher schemaDispatcher;
  private TableDispatcher tableDispatcher;
  private FilesetDispatcher filesetDispatcher;
  private TopicDispatcher topicDispatcher;
  private ModelDispatcher modelDispatcher;
  private TagDispatcher tagDispatcher;
  private AccessControlDispatcher accessControlDispatcher;
  private OwnerDispatcher ownerDispatcher;
  private MetricDataService metricDataService;
  private EntityStore store;

  private final long mockId = 1L;
  private final String metalakeName = "test_metalake";
  private final String relationalCatalogName = "rel_catalog";
  private final String relationalSchemaName = "rel_schema";
  private final String tableName = "table";

  @BeforeEach
  public void setUp() throws Exception {
    MetricsCollector.getInstance().getMetalakeSnapshots().clear();
    serverConfig = mock(ServerConfig.class);
    metalakeDispatcher = mock(MetalakeDispatcher.class);
    catalogManager = mock(CatalogManager.class);
    catalogDispatcher = mock(CatalogDispatcher.class);
    schemaDispatcher = mock(SchemaDispatcher.class);
    tableDispatcher = mock(TableDispatcher.class);
    filesetDispatcher = mock(FilesetDispatcher.class);
    topicDispatcher = mock(TopicDispatcher.class);
    modelDispatcher = mock(ModelDispatcher.class);
    tagDispatcher = mock(TagDispatcher.class);
    accessControlDispatcher = mock(AccessControlDispatcher.class);
    ownerDispatcher = mock(OwnerDispatcher.class);
    metricDataService = mock(MetricDataService.class);
    store = mock(EntityStore.class);
    gravitinoEnv = mock(GravitinoEnv.class);
    when(gravitinoEnv.metalakeDispatcher()).thenReturn(metalakeDispatcher);
    when(gravitinoEnv.catalogDispatcher()).thenReturn(catalogDispatcher);
    when(gravitinoEnv.schemaDispatcher()).thenReturn(schemaDispatcher);
    when(gravitinoEnv.tableDispatcher()).thenReturn(tableDispatcher);
    when(gravitinoEnv.filesetDispatcher()).thenReturn(filesetDispatcher);
    when(gravitinoEnv.topicDispatcher()).thenReturn(topicDispatcher);
    when(gravitinoEnv.modelDispatcher()).thenReturn(modelDispatcher);
    when(gravitinoEnv.tagDispatcher()).thenReturn(tagDispatcher);
    when(gravitinoEnv.accessControlDispatcher()).thenReturn(accessControlDispatcher);
    when(gravitinoEnv.ownerDispatcher()).thenReturn(ownerDispatcher);
    when(gravitinoEnv.entityStore()).thenReturn(store);
    when(gravitinoEnv.catalogManager()).thenReturn(catalogManager);

    // mock config
    when(serverConfig.get(MetricsConfig.PII_TAGS_CONFIG)).thenReturn(ImmutableList.of("pii_tag1"));
    when(serverConfig.get(MetricsConfig.PUBLIC_TAGS_CONFIG))
        .thenReturn(ImmutableList.of("public_tag1"));
    when(serverConfig.get(MetricsConfig.CONFIDENTIAL_TAGS_CONFIG))
        .thenReturn(ImmutableList.of("confidential_tag1"));
    when(serverConfig.get(MetricsConfig.PRIVATE_TAGS_CONFIG))
        .thenReturn(ImmutableList.of("private_tag1"));
    when(serverConfig.get(MetricsConfig.RETENTION_DAYS_CONFIG)).thenReturn(30);
    when(serverConfig.get(Configs.ENABLE_AUTHORIZATION)).thenReturn(false);

    // mock store
    mockListCatalogFromStore();
    mockLoadCatalog();
    mockListUserFromStore();
    mockLoadRoleSecurableObjectsRel();
    mockListOwners();
    mockSchemaDispatcher();
    mockTableDispatcher();
  }

  @Test
  void testInitialize() {
    MetricsCollector collector = MetricsCollector.getInstance();
    collector.initialize(serverConfig, gravitinoEnv);

    assertEquals(4, collector.getCategoryToTagNames().size());
    assertEquals(
        ImmutableSet.of("pii_tag1"),
        collector.getCategoryToTagNames().get(MetricsCollector.TagCategory.PII));
    assertEquals(
        ImmutableSet.of("public_tag1"),
        collector.getCategoryToTagNames().get(MetricsCollector.TagCategory.PUBLIC));
    assertEquals(
        ImmutableSet.of("confidential_tag1"),
        collector.getCategoryToTagNames().get(MetricsCollector.TagCategory.CONFIDENTIAL));
    assertEquals(
        ImmutableSet.of("private_tag1"),
        collector.getCategoryToTagNames().get(MetricsCollector.TagCategory.PRIVATE));
  }

  @Test
  void testLoadAllDataForMetalake() throws Exception {
    try (MockedStatic<MetricDataService> mockedMetricDataService =
        Mockito.mockStatic(MetricDataService.class)) {
      mockedMetricDataService.when(MetricDataService::getInstance).thenReturn(metricDataService);

      MetricsCollector collector = MetricsCollector.getInstance();
      collector.initialize(serverConfig, gravitinoEnv);

      BaseMetalake metalake =
          BaseMetalake.builder()
              .withId(mockId)
              .withName(metalakeName)
              .withVersion(SchemaVersion.V_0_1)
              .withAuditInfo(
                  AuditInfo.builder().withCreator("test").withCreateTime(Instant.now()).build())
              .build();

      MetalakeSnapshot snapshot = collector.loadAllDataForMetalake(metalake);
      assertNotNull(snapshot);
      assertEquals(metalakeName, snapshot.getAssetTreeRoot().getName());
      assertEquals(1, snapshot.getAssetTreeRoot().getChildren().size());
      assertEquals(1, snapshot.getCatalogNodes().size());
      assertEquals(1, snapshot.getSchemaNodes().size());
      assertEquals(
          snapshot.getSchemaNodes(), snapshot.getCatalogNodes().iterator().next().getChildren());
      assertEquals(1, snapshot.getTableNodes().size());
      assertEquals(
          snapshot.getTableNodes(), snapshot.getSchemaNodes().iterator().next().getChildren());
    }
  }

  @Test
  void testLoadAllDataForMetalakeWithSchemaNotInStore() throws Exception {
    try (MockedStatic<MetricDataService> mockedMetricDataService =
        Mockito.mockStatic(MetricDataService.class)) {
      mockedMetricDataService.when(MetricDataService::getInstance).thenReturn(metricDataService);

      // Mock store.list() for tables to throw NoSuchEntityException (schema not in store)
      when(store.list(
              eq(NamespaceUtil.ofTable(metalakeName, relationalCatalogName, relationalSchemaName)),
              eq(TableEntity.class),
              eq(Entity.EntityType.TABLE)))
          .thenThrow(new NoSuchEntityException("No such schema entity: %s", relationalSchemaName));

      MetricsCollector collector = MetricsCollector.getInstance();
      collector.initialize(serverConfig, gravitinoEnv);

      BaseMetalake metalake =
          BaseMetalake.builder()
              .withId(mockId)
              .withName(metalakeName)
              .withVersion(SchemaVersion.V_0_1)
              .withAuditInfo(
                  AuditInfo.builder().withCreator("test").withCreateTime(Instant.now()).build())
              .build();

      MetalakeSnapshot snapshot = collector.loadAllDataForMetalake(metalake);
      assertNotNull(snapshot);
      assertEquals(metalakeName, snapshot.getAssetTreeRoot().getName());
      // Tables should still be loaded from tableDispatcher since managedTable=false
      assertEquals(1, snapshot.getTableNodes().size());
      assertEquals(tableName, snapshot.getTableNodes().iterator().next().getName());
    }
  }

  @Test
  void testDisabledCatalogIsExcludedWithoutLoadingItsDispatcher() throws Exception {
    CatalogEntity disabledCatalog =
        CatalogEntity.builder()
            .withId(2L)
            .withName("disabled_catalog")
            .withNamespace(NamespaceUtil.ofCatalog(metalakeName))
            .withType(Catalog.Type.RELATIONAL)
            .withProvider("hive")
            .withProperties(ImmutableMap.of(Catalog.PROPERTY_IN_USE, "false"))
            .withAuditInfo(
                AuditInfo.builder().withCreator("test").withCreateTime(Instant.now()).build())
            .build();
    when(store.list(
            NamespaceUtil.ofCatalog(metalakeName), CatalogEntity.class, Entity.EntityType.CATALOG))
        .thenReturn(ImmutableList.of(disabledCatalog));

    try (MockedStatic<MetricDataService> mockedMetricDataService =
        Mockito.mockStatic(MetricDataService.class)) {
      mockedMetricDataService.when(MetricDataService::getInstance).thenReturn(metricDataService);
      MetricsCollector collector = MetricsCollector.getInstance();
      collector.initialize(serverConfig, gravitinoEnv);
      clearInvocations(catalogManager);

      MetalakeSnapshot snapshot = collector.loadAllDataForMetalake(metalake());

      assertEquals(0, snapshot.getCatalogNodes().size());
      verify(catalogManager, never()).loadCatalogAndWrap(any());
    }
  }

  @Test
  void testPublishModesWriteCurrentAndHistoryAsConfigured() throws Exception {
    try (MockedStatic<MetricDataService> mockedMetricDataService =
        Mockito.mockStatic(MetricDataService.class)) {
      mockedMetricDataService.when(MetricDataService::getInstance).thenReturn(metricDataService);
      MetricsCollector collector = MetricsCollector.getInstance();
      collector.initialize(serverConfig, gravitinoEnv);
      BaseMetalake metalake = metalake();

      collector.collectAndPublish(metalake, MetricsCollector.PublishMode.CURRENT_ONLY, 1_000L);
      verify(metricDataService).replaceCurrentMetrics(eq(mockId), anyMap(), eq(1_000L));
      verify(metricDataService, never())
          .replaceCurrentAndAppendHistory(eq(mockId), anyMap(), eq(1_000L));

      collector.collectAndPublish(
          metalake, MetricsCollector.PublishMode.CURRENT_AND_HISTORY, 2_000L);
      verify(metricDataService).replaceCurrentAndAppendHistory(eq(mockId), anyMap(), eq(2_000L));
    }
  }

  @Test
  void testFailedPublishRestoresPreviousSnapshot() throws Exception {
    try (MockedStatic<MetricDataService> mockedMetricDataService =
        Mockito.mockStatic(MetricDataService.class)) {
      mockedMetricDataService.when(MetricDataService::getInstance).thenReturn(metricDataService);
      MetricsCollector collector = MetricsCollector.getInstance();
      collector.initialize(serverConfig, gravitinoEnv);
      BaseMetalake metalake = metalake();

      collector.collectAndPublish(metalake, MetricsCollector.PublishMode.CURRENT_ONLY, 1_000L);
      MetalakeSnapshot previous = collector.getMetalakeSnapshots().get(metalakeName);
      doThrow(new RuntimeException("write failed"))
          .when(metricDataService)
          .replaceCurrentMetrics(eq(mockId), anyMap(), eq(2_000L));

      assertThrows(
          RuntimeException.class,
          () ->
              collector.collectAndPublish(
                  metalake, MetricsCollector.PublishMode.CURRENT_ONLY, 2_000L));
      assertSame(previous, collector.getMetalakeSnapshots().get(metalakeName));
    }
  }

  @Test
  void testFailedAuthoritativeLoadDoesNotPublishPartialCurrent() throws Exception {
    try (MockedStatic<MetricDataService> mockedMetricDataService =
        Mockito.mockStatic(MetricDataService.class)) {
      mockedMetricDataService.when(MetricDataService::getInstance).thenReturn(metricDataService);
      MetricsCollector collector = MetricsCollector.getInstance();
      collector.initialize(serverConfig, gravitinoEnv);
      BaseMetalake metalake = metalake();

      collector.collectAndPublish(metalake, MetricsCollector.PublishMode.CURRENT_ONLY, 1_000L);
      MetalakeSnapshot previous = collector.getMetalakeSnapshots().get(metalakeName);
      when(tableDispatcher.listTables(
              eq(NamespaceUtil.ofTable(metalakeName, relationalCatalogName, relationalSchemaName))))
          .thenThrow(new RuntimeException("catalog unavailable"));

      assertThrows(
          RuntimeException.class,
          () ->
              collector.collectAndPublish(
                  metalake, MetricsCollector.PublishMode.CURRENT_ONLY, 2_000L));
      verify(metricDataService, never()).replaceCurrentMetrics(eq(mockId), anyMap(), eq(2_000L));
      assertSame(previous, collector.getMetalakeSnapshots().get(metalakeName));
    }
  }

  private void mockListCatalogFromStore() throws IOException {
    CatalogEntity catalogEntity =
        CatalogEntity.builder()
            .withId(1L)
            .withName(relationalCatalogName)
            .withNamespace(NamespaceUtil.ofCatalog(metalakeName))
            .withType(Catalog.Type.RELATIONAL)
            .withProvider("hive")
            .withAuditInfo(
                AuditInfo.builder().withCreator("test").withCreateTime(Instant.now()).build())
            .build();
    when(store.list(
            NamespaceUtil.ofCatalog(metalakeName), CatalogEntity.class, Entity.EntityType.CATALOG))
        .thenReturn(ImmutableList.of(catalogEntity));
  }

  private BaseMetalake metalake() {
    return BaseMetalake.builder()
        .withId(mockId)
        .withName(metalakeName)
        .withVersion(SchemaVersion.V_0_1)
        .withAuditInfo(
            AuditInfo.builder().withCreator("test").withCreateTime(Instant.now()).build())
        .build();
  }

  private void mockLoadCatalog() throws Exception {
    CatalogManager.CatalogWrapper catalogWrapper = mock(CatalogManager.CatalogWrapper.class);
    when(catalogManager.loadCatalogAndWrap(
            eq(NameIdentifierUtil.ofCatalog(metalakeName, relationalCatalogName))))
        .thenReturn(catalogWrapper);

    when(catalogWrapper.capabilities()).thenReturn(Capability.DEFAULT);
  }

  private void mockListUserFromStore() throws IOException {
    when(store.list(NamespaceUtil.ofUser(metalakeName), UserEntity.class, Entity.EntityType.USER))
        .thenReturn(ImmutableList.of());
  }

  private void mockLoadRoleSecurableObjectsRel() throws IOException {
    when(store.list(NamespaceUtil.ofRole(metalakeName), RoleEntity.class, Entity.EntityType.ROLE))
        .thenReturn(ImmutableList.of());
  }

  private void mockListOwners() throws IOException {
    when(metricDataService.listOwnerNameRelsByMetalakeId(eq(mockId)))
        .thenReturn(Collections.emptyList());
  }

  private void mockSchemaDispatcher() {
    NameIdentifier relationalSchemaIdent =
        NameIdentifierUtil.ofSchema(metalakeName, relationalCatalogName, relationalSchemaName);
    when(schemaDispatcher.listSchemas(
            eq(NamespaceUtil.ofSchema(metalakeName, relationalCatalogName))))
        .thenReturn(new NameIdentifier[] {relationalSchemaIdent});
  }

  private void mockTableDispatcher() {
    NameIdentifier tableIdent =
        NameIdentifierUtil.ofTable(
            metalakeName, relationalCatalogName, relationalSchemaName, tableName);
    when(tableDispatcher.listTables(
            eq(NamespaceUtil.ofTable(metalakeName, relationalCatalogName, relationalSchemaName))))
        .thenReturn(new NameIdentifier[] {tableIdent});
  }
}
