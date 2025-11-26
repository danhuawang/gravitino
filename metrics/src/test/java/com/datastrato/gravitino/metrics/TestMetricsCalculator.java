/*
 * Copyright 2024 Datastrato Pvt Ltd.
 * This software is licensed under the Apache License version 2.
 */
package com.datastrato.gravitino.metrics;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.datastrato.gravitino.metrics.storage.relational.MetricPO;
import com.datastrato.gravitino.metrics.storage.relational.service.MetricDataService;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.ImmutableSet;
import java.util.Arrays;
import java.util.Collections;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;
import org.apache.gravitino.NameIdentifier;
import org.apache.gravitino.Namespace;
import org.apache.gravitino.authorization.Owner;
import org.apache.gravitino.utils.NamespaceUtil;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class TestMetricsCalculator {

  private MetalakeSnapshot metalakeSnapshot;
  private EnumMap<MetricsCollector.TagCategory, Set<String>> categoryToTagNames;
  private MetricsCalculator metricsCalculator;
  private String metalakeName = "testMetalake";

  @BeforeEach
  void setUp() {
    metalakeSnapshot = mock(MetalakeSnapshot.class);
    categoryToTagNames = new EnumMap<>(MetricsCollector.TagCategory.class);
    AssetNode rootNode = mock(AssetNode.class);
    when(rootNode.getName()).thenReturn(metalakeName);
    when(metalakeSnapshot.getAssetTreeRoot()).thenReturn(rootNode);
    when(metalakeSnapshot.getUserNameToUserId())
        .thenReturn(ImmutableMap.of("testUser", 1L, "", -1L));

    metricsCalculator = new MetricsCalculator(metalakeSnapshot, categoryToTagNames);
  }

  @Test
  void testCalculateMetricsForDisableAuthz() {
    // Mock assets
    AssetNode rootNode = createMockAssetNode(metalakeName, null);
    AssetNode catalog = createMockAssetNode("catalog1", rootNode.getNameIdent());
    AssetNode schema = createMockAssetNode("schema1", catalog.getNameIdent());
    AssetNode table = createMockAssetNode("table1", schema.getNameIdent());
    when(table.getOwners()).thenReturn(ImmutableSet.of(mock(Owner.class)));

    when(metalakeSnapshot.getCatalogNodes()).thenReturn(ImmutableSet.of(catalog));
    when(metalakeSnapshot.getSchemaNodes()).thenReturn(ImmutableSet.of(schema));
    when(metalakeSnapshot.getTableNodes()).thenReturn(ImmutableSet.of(table));
    when(metalakeSnapshot.getFilesetNodes()).thenReturn(Collections.emptySet());
    when(metalakeSnapshot.getTopicNodes()).thenReturn(Collections.emptySet());
    when(metalakeSnapshot.getModelNodes()).thenReturn(Collections.emptySet());

    NameIdentifier catalogIdent = catalog.getNameIdent();
    NameIdentifier schemaIdent = schema.getNameIdent();
    NameIdentifier tableIdent = table.getNameIdent();
    when(metalakeSnapshot.getAssetIdentToTagNames())
        .thenReturn(
            ImmutableMap.of(
                schemaIdent,
                ImmutableSet.of("tag_on_schema"),
                tableIdent,
                ImmutableSet.of("tag_on_table")));
    when(metalakeSnapshot.getTagCount()).thenReturn(2L);
    when(metalakeSnapshot.getAssetNodeByIdent())
        .thenReturn(ImmutableMap.of(catalogIdent, catalog, schemaIdent, schema, tableIdent, table));

    List<MetricPO> metrics = metricsCalculator.calculateMetricsForDisableAuthz();
    Map<String, Double> metricMap =
        metrics.stream()
            .collect(Collectors.toMap(MetricPO::getMetricName, MetricPO::getMetricValue));

    assertEquals(1.0, metricMap.get(MetricDataService.Metric.CATALOG_COUNT.getName()));
    assertEquals(1.0, metricMap.get(MetricDataService.Metric.SCHEMA_COUNT.getName()));
    assertEquals(1.0, metricMap.get(MetricDataService.Metric.TABLE_COUNT.getName()));
    assertEquals(0.0, metricMap.get(MetricDataService.Metric.FILESET_COUNT.getName()));
    assertEquals(0.0, metricMap.get(MetricDataService.Metric.TOPIC_COUNT.getName()));
    assertEquals(0.0, metricMap.get(MetricDataService.Metric.MODEL_COUNT.getName()));
    assertEquals(3.0, metricMap.get(MetricDataService.Metric.ASSET_COUNT.getName()));
    assertEquals(2.0, metricMap.get(MetricDataService.Metric.TAG_COUNT.getName()));
    assertEquals(2.0, metricMap.get(MetricDataService.Metric.TAGGED_ASSET_COUNT.getName()));
    assertEquals(1.0, metricMap.get(MetricDataService.Metric.UNTAGGED_ASSET_COUNT.getName()));
    assertEquals(0.0, metricMap.get(MetricDataService.Metric.PII_TAGGED_ASSET_COUNT.getName()));
    assertEquals(0.0, metricMap.get(MetricDataService.Metric.PUBLIC_TAGGED_ASSET_COUNT.getName()));
    assertEquals(
        0.0, metricMap.get(MetricDataService.Metric.CONFIDENTIAL_TAGGED_ASSET_COUNT.getName()));
    assertEquals(0.0, metricMap.get(MetricDataService.Metric.PRIVATE_TAGGED_ASSET_COUNT.getName()));
    assertFalse(metricMap.containsKey(MetricDataService.Metric.OWNED_ASSET_COUNT.getName()));
  }

  @Test
  void testCalculateMetricsForUser_userNotFound() {
    when(metalakeSnapshot.getUserNameToUserId()).thenReturn(Collections.emptyMap());
    List<MetricPO> metrics = metricsCalculator.calculateMetricsForUser("unknownUser", true, false);
    assertEquals(0, metrics.size());
  }

  private AssetNode createMockAssetNode(String name, NameIdentifier parentIdent) {
    AssetNode node = mock(AssetNode.class);

    NameIdentifier ident;
    if (parentIdent == null) {
      ident = NameIdentifier.of(NamespaceUtil.ofMetalake(), name);
    } else {
      Namespace parentNs = parentIdent.namespace();
      String[] levels = parentNs.levels();
      String[] newLevels = Arrays.copyOf(levels, levels.length + 1);
      newLevels[levels.length] = parentIdent.name();
      ident = NameIdentifier.of(Namespace.of(newLevels), name);
    }
    when(node.getNameIdent()).thenReturn(ident);
    when(node.getParentIdent()).thenReturn(parentIdent);
    when(node.getName()).thenReturn(name);
    when(node.getOwners()).thenReturn(Collections.emptySet());
    return node;
  }
}
