/*
 * Copyright 2024 Datastrato Pvt Ltd.
 * This software is licensed under the Apache License version 2.
 */
package com.datastrato.gravitino.server.web.metric;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.datastrato.gravitino.metrics.MetricsConfig;
import com.datastrato.gravitino.storage.relational.MetricPO;
import com.datastrato.gravitino.storage.relational.service.MetricDataService;
import com.google.common.collect.Maps;
import java.time.Instant;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import org.apache.gravitino.Catalog;
import org.apache.gravitino.Configs;
import org.apache.gravitino.MetadataObject;
import org.apache.gravitino.MetadataObjects;
import org.apache.gravitino.Metalake;
import org.apache.gravitino.NameIdentifier;
import org.apache.gravitino.auth.AuthConstants;
import org.apache.gravitino.authorization.AccessControlDispatcher;
import org.apache.gravitino.catalog.CatalogDispatcher;
import org.apache.gravitino.catalog.FilesetDispatcher;
import org.apache.gravitino.catalog.ModelDispatcher;
import org.apache.gravitino.catalog.SchemaDispatcher;
import org.apache.gravitino.catalog.TableDispatcher;
import org.apache.gravitino.catalog.TopicDispatcher;
import org.apache.gravitino.meta.AuditInfo;
import org.apache.gravitino.meta.BaseMetalake;
import org.apache.gravitino.meta.SchemaVersion;
import org.apache.gravitino.metalake.MetalakeDispatcher;
import org.apache.gravitino.server.ServerConfig;
import org.apache.gravitino.tag.TagDispatcher;
import org.apache.gravitino.utils.NameIdentifierUtil;
import org.apache.gravitino.utils.NamespaceUtil;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

class TestMetricsCollector {

  private ServerConfig serverConfig;
  private MetalakeDispatcher metalakeDispatcher;
  private CatalogDispatcher catalogDispatcher;
  private SchemaDispatcher schemaDispatcher;
  private TableDispatcher tableDispatcher;
  private FilesetDispatcher filesetDispatcher;
  private TopicDispatcher topicDispatcher;
  private ModelDispatcher modelDispatcher;
  private TagDispatcher tagDispatcher;
  private AccessControlDispatcher accessControlDispatcher;
  private MetricDataService metricDataService;

  private final long mockId = 1L;
  private final String metalakeName = "test_metalake";
  private final String relationalCatalogName = "rel_catalog";
  private final String filesetCatalogName = "fileset_catalog";
  private final String messagingCatalogName = "messaging_catalog";
  private final String modelCatalogName = "model_catalog";
  private final String relationalSchemaName = "rel_schema";
  private final String filesetSchemaName = "fileset_schema";
  private final String messagingSchemaName = "messaging_schema";
  private final String modelSchemaName = "model_schema";
  private final String tableName = "table";
  private final String filesetName = "fileset";
  private final String topicName = "topic";
  private final String modelName = "model";
  private final String filesetTagName = "fileset_tag";

  @BeforeEach
  public void setUp() {
    serverConfig = mock(ServerConfig.class);
    metalakeDispatcher = mock(MetalakeDispatcher.class);
    catalogDispatcher = mock(CatalogDispatcher.class);
    schemaDispatcher = mock(SchemaDispatcher.class);
    tableDispatcher = mock(TableDispatcher.class);
    filesetDispatcher = mock(FilesetDispatcher.class);
    topicDispatcher = mock(TopicDispatcher.class);
    modelDispatcher = mock(ModelDispatcher.class);
    tagDispatcher = mock(TagDispatcher.class);
    accessControlDispatcher = mock(AccessControlDispatcher.class);
    metricDataService = mock(MetricDataService.class);
    when(metricDataService.getAssetWithOwnerCount(metalakeName)).thenReturn(0L);

    // mock config
    when(serverConfig.get(MetricsConfig.PII_TAGS_CONFIG)).thenReturn(Collections.emptyList());
    when(serverConfig.get(MetricsConfig.PUBLIC_TAGS_CONFIG)).thenReturn(Collections.emptyList());
    when(serverConfig.get(MetricsConfig.CONFIDENTIAL_TAGS_CONFIG))
        .thenReturn(Collections.emptyList());
    when(serverConfig.get(MetricsConfig.PRIVATE_TAGS_CONFIG)).thenReturn(Collections.emptyList());
    when(serverConfig.get(Configs.SERVICE_ADMINS))
        .thenReturn(Collections.singletonList(AuthConstants.ANONYMOUS_USER));
    when(serverConfig.get(MetricsConfig.RETENTION_DAYS_CONFIG)).thenReturn(30);
    when(serverConfig.get(Configs.ENABLE_AUTHORIZATION)).thenReturn(false);

    // mock dispatchers
    mockMetalakeDispatcher();
    mockCatalogDispatcher();
    mockSchemaDispatcher();
    mockTableDispatcher();
    mockFilesetDispatcher();
    mockTopicDispatcher();
    mockModelDispatcher();
    mockTagDispatcher();
  }

  @Test
  public void testRefreshMetricsForUser() throws Exception {
    MetricsCollector collector =
        new MetricsCollector(
            serverConfig,
            metalakeDispatcher,
            catalogDispatcher,
            schemaDispatcher,
            tableDispatcher,
            filesetDispatcher,
            topicDispatcher,
            modelDispatcher,
            tagDispatcher,
            accessControlDispatcher,
            metricDataService);

    collector.refreshMetricsForUser(metalakeName, "user1");

    ArgumentCaptor<List<MetricPO>> metricsCaptor = ArgumentCaptor.forClass(List.class);
    // Since enableAuthorization is false, ANONYMOUS_USER will be used
    verify(metricDataService)
        .insertMetrics(
            eq(metalakeName), eq(AuthConstants.ANONYMOUS_USER), metricsCaptor.capture(), eq(false));

    List<MetricPO> capturedMetrics = metricsCaptor.getValue();
    Map<String, MetricPO> actualMetrics =
        Maps.uniqueIndex(capturedMetrics, MetricPO::getMetricName);

    assertEquals(MetricDataService.Metric.values().length - 1, actualMetrics.size());
    assertEquals(
        12, actualMetrics.get(MetricDataService.Metric.ASSET_COUNT.getName()).getMetricValue());
    assertEquals(
        1, actualMetrics.get(MetricDataService.Metric.TAG_COUNT.getName()).getMetricValue());
    assertEquals(
        4, actualMetrics.get(MetricDataService.Metric.CATALOG_COUNT.getName()).getMetricValue());
    assertEquals(
        4, actualMetrics.get(MetricDataService.Metric.SCHEMA_COUNT.getName()).getMetricValue());
    assertEquals(
        1, actualMetrics.get(MetricDataService.Metric.TABLE_COUNT.getName()).getMetricValue());
    assertEquals(
        1, actualMetrics.get(MetricDataService.Metric.FILESET_COUNT.getName()).getMetricValue());
    assertEquals(
        1, actualMetrics.get(MetricDataService.Metric.TOPIC_COUNT.getName()).getMetricValue());
    assertEquals(
        1, actualMetrics.get(MetricDataService.Metric.MODEL_COUNT.getName()).getMetricValue());
    assertEquals(
        3,
        actualMetrics.get(MetricDataService.Metric.TAGGED_ASSET_COUNT.getName()).getMetricValue());
    assertEquals(
        9,
        actualMetrics
            .get(MetricDataService.Metric.UNTAGGED_ASSET_COUNT.getName())
            .getMetricValue());
    assertEquals(
        0,
        actualMetrics
            .get(MetricDataService.Metric.PII_TAGGED_ASSET_COUNT.getName())
            .getMetricValue());
    assertEquals(
        0,
        actualMetrics
            .get(MetricDataService.Metric.PUBLIC_TAGGED_ASSET_COUNT.getName())
            .getMetricValue());
    assertEquals(
        0,
        actualMetrics
            .get(MetricDataService.Metric.CONFIDENTIAL_TAGGED_ASSET_COUNT.getName())
            .getMetricValue());
    assertEquals(
        0,
        actualMetrics
            .get(MetricDataService.Metric.PRIVATE_TAGGED_ASSET_COUNT.getName())
            .getMetricValue());
  }

  private void mockMetalakeDispatcher() {
    BaseMetalake metalake =
        BaseMetalake.builder()
            .withId(mockId)
            .withName(metalakeName)
            .withVersion(SchemaVersion.V_0_1)
            .withAuditInfo(
                AuditInfo.builder().withCreator("test").withCreateTime(Instant.now()).build())
            .build();
    NameIdentifier metalakeIdent = NameIdentifier.of(metalakeName);

    when(metalakeDispatcher.metalakeExists(metalakeIdent)).thenReturn(true);
    when(metalakeDispatcher.listMetalakes()).thenReturn(new Metalake[] {metalake});
  }

  private void mockCatalogDispatcher() {
    Catalog relationalCatalog = mock(Catalog.class);
    when(relationalCatalog.name()).thenReturn(relationalCatalogName);
    when(relationalCatalog.type()).thenReturn(Catalog.Type.RELATIONAL);

    Catalog filesetCatalog = mock(Catalog.class);
    when(filesetCatalog.name()).thenReturn(filesetCatalogName);
    when(filesetCatalog.type()).thenReturn(Catalog.Type.FILESET);

    Catalog messagingCatalog = mock(Catalog.class);
    when(messagingCatalog.name()).thenReturn(messagingCatalogName);
    when(messagingCatalog.type()).thenReturn(Catalog.Type.MESSAGING);

    Catalog modelCatalog = mock(Catalog.class);
    when(modelCatalog.name()).thenReturn(modelCatalogName);
    when(modelCatalog.type()).thenReturn(Catalog.Type.MODEL);

    Catalog[] catalogs = {relationalCatalog, filesetCatalog, messagingCatalog, modelCatalog};

    when(catalogDispatcher.listCatalogsInfo(eq(NamespaceUtil.ofCatalog(metalakeName))))
        .thenReturn(catalogs);
  }

  private void mockSchemaDispatcher() {
    NameIdentifier relationalSchemaIdent =
        NameIdentifierUtil.ofSchema(metalakeName, relationalCatalogName, relationalSchemaName);
    when(schemaDispatcher.listSchemas(
            eq(NamespaceUtil.ofSchema(metalakeName, relationalCatalogName))))
        .thenReturn(new NameIdentifier[] {relationalSchemaIdent});

    NameIdentifier filesetSchemaIdent =
        NameIdentifierUtil.ofSchema(metalakeName, filesetCatalogName, filesetSchemaName);
    when(schemaDispatcher.listSchemas(eq(NamespaceUtil.ofSchema(metalakeName, filesetCatalogName))))
        .thenReturn(new NameIdentifier[] {filesetSchemaIdent});

    NameIdentifier messagingSchemaIdent =
        NameIdentifierUtil.ofSchema(metalakeName, messagingCatalogName, messagingSchemaName);
    when(schemaDispatcher.listSchemas(
            eq(NamespaceUtil.ofSchema(metalakeName, messagingCatalogName))))
        .thenReturn(new NameIdentifier[] {messagingSchemaIdent});

    NameIdentifier modelSchemaIdent =
        NameIdentifierUtil.ofSchema(metalakeName, modelCatalogName, modelSchemaName);
    when(schemaDispatcher.listSchemas(eq(NamespaceUtil.ofSchema(metalakeName, modelCatalogName))))
        .thenReturn(new NameIdentifier[] {modelSchemaIdent});
  }

  private void mockTableDispatcher() {
    NameIdentifier tableIdent =
        NameIdentifierUtil.ofTable(
            metalakeName, relationalCatalogName, relationalSchemaName, tableName);
    when(tableDispatcher.listTables(
            eq(NamespaceUtil.ofTable(metalakeName, relationalCatalogName, relationalSchemaName))))
        .thenReturn(new NameIdentifier[] {tableIdent});
  }

  private void mockFilesetDispatcher() {
    NameIdentifier filesetIdent =
        NameIdentifierUtil.ofFileset(
            metalakeName, filesetCatalogName, filesetSchemaName, filesetName);
    when(filesetDispatcher.listFilesets(
            eq(NamespaceUtil.ofFileset(metalakeName, filesetCatalogName, filesetSchemaName))))
        .thenReturn(new NameIdentifier[] {filesetIdent});
  }

  private void mockTopicDispatcher() {
    NameIdentifier topicIdent =
        NameIdentifierUtil.ofTopic(
            metalakeName, messagingCatalogName, messagingSchemaName, topicName);
    when(topicDispatcher.listTopics(
            eq(NamespaceUtil.ofTopic(metalakeName, messagingCatalogName, messagingSchemaName))))
        .thenReturn(new NameIdentifier[] {topicIdent});
  }

  private void mockModelDispatcher() {
    NameIdentifier modelIdent =
        NameIdentifierUtil.ofModel(metalakeName, modelCatalogName, modelSchemaName, modelName);
    when(modelDispatcher.listModels(
            eq(NamespaceUtil.ofModel(metalakeName, modelCatalogName, modelSchemaName))))
        .thenReturn(new NameIdentifier[] {modelIdent});
  }

  private void mockTagDispatcher() {
    when(tagDispatcher.listTags(metalakeName)).thenReturn(new String[] {filesetTagName});

    MetadataObject filesetCatalogObject =
        MetadataObjects.of(null, filesetCatalogName, MetadataObject.Type.CATALOG);
    when(tagDispatcher.listMetadataObjectsForTag(metalakeName, filesetTagName))
        .thenReturn(new MetadataObject[] {filesetCatalogObject});
  }
}
