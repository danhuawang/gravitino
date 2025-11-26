/*
 * Copyright 2024 Datastrato Pvt Ltd.
 * This software is licensed under the Apache License version 2.
 */
package com.datastrato.gravitino.metrics;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.datastrato.gravitino.metrics.config.MetricsConfig;
import com.datastrato.gravitino.metrics.storage.relational.service.MetricDataService;
import com.google.common.collect.ImmutableList;
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
import org.apache.gravitino.meta.AuditInfo;
import org.apache.gravitino.meta.BaseMetalake;
import org.apache.gravitino.meta.CatalogEntity;
import org.apache.gravitino.meta.RoleEntity;
import org.apache.gravitino.meta.SchemaVersion;
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
