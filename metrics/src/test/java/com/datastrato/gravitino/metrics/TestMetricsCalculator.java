/*
 * Copyright 2024 Datastrato Pvt Ltd.
 * This software is licensed under the Apache License version 2.
 */
package com.datastrato.gravitino.metrics;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.when;

import com.datastrato.gravitino.metrics.storage.relational.MetricPO;
import com.datastrato.gravitino.metrics.storage.relational.service.MetricDataService;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.ImmutableSet;
import java.util.Arrays;
import java.util.Collections;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;
import org.apache.gravitino.Config;
import org.apache.gravitino.Configs;
import org.apache.gravitino.GravitinoEnv;
import org.apache.gravitino.MetadataObject;
import org.apache.gravitino.NameIdentifier;
import org.apache.gravitino.Namespace;
import org.apache.gravitino.authorization.Owner;
import org.apache.gravitino.authorization.Privileges;
import org.apache.gravitino.authorization.SecurableObject;
import org.apache.gravitino.authorization.SecurableObjects;
import org.apache.gravitino.utils.NamespaceUtil;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.MockedStatic;

class TestMetricsCalculator {

  private static final long TEST_USER_ID = 1L;
  private static final long ALLOW_ROLE_ID = 10L;
  private static final long DENY_ROLE_ID = 20L;

  private static MockedStatic<GravitinoEnv> mockedStaticGravitinoEnv;

  private MetalakeSnapshot metalakeSnapshot;
  private EnumMap<MetricsCollector.TagCategory, Set<String>> categoryToTagNames;
  private MetricsCalculator metricsCalculator;
  private String metalakeName = "testMetalake";

  @BeforeAll
  static void setUpAuthorization() {
    mockedStaticGravitinoEnv = mockStatic(GravitinoEnv.class);
    GravitinoEnv gravitinoEnv = mock(GravitinoEnv.class);
    mockedStaticGravitinoEnv.when(GravitinoEnv::getInstance).thenReturn(gravitinoEnv);
    Config config = mock(Config.class);
    when(gravitinoEnv.config()).thenReturn(config);
    when(config.get(Configs.ENABLE_AUTHORIZATION)).thenReturn(true);
    when(config.get(Configs.GRAVITINO_AUTHORIZATION_THREAD_POOL_SIZE)).thenReturn(2);
  }

  @AfterAll
  static void tearDownAuthorization() {
    mockedStaticGravitinoEnv.close();
  }

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

  @AfterEach
  void tearDown() {
    MetricsCollector.getInstance().getMetalakeSnapshots().clear();
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

  @Test
  void testCalculateMetricsForUserWithAllowedRole() {
    SecurableObject allowedCatalog =
        SecurableObjects.ofCatalog("catalog1", ImmutableList.of(Privileges.UseCatalog.allow()));
    MetalakeSnapshot snapshot =
        createRoleSnapshot(
            ImmutableMap.of(ALLOW_ROLE_ID, ImmutableList.of(allowedCatalog)),
            ImmutableSet.of(ALLOW_ROLE_ID));

    List<MetricPO> metrics = calculateMetrics(snapshot);

    assertEquals(1.0, metricValue(metrics, MetricDataService.Metric.CATALOG_COUNT));
    assertEquals(1.0, metricValue(metrics, MetricDataService.Metric.ASSET_COUNT));
  }

  @Test
  void testCalculateMetricsForUserWithDenyRoleOverridingAllowRole() {
    SecurableObject allowedCatalog =
        SecurableObjects.ofCatalog("catalog1", ImmutableList.of(Privileges.UseCatalog.allow()));
    SecurableObject deniedCatalog =
        SecurableObjects.ofCatalog("catalog1", ImmutableList.of(Privileges.UseCatalog.deny()));
    MetalakeSnapshot snapshot =
        createRoleSnapshot(
            ImmutableMap.of(
                ALLOW_ROLE_ID,
                ImmutableList.of(allowedCatalog),
                DENY_ROLE_ID,
                ImmutableList.of(deniedCatalog)),
            ImmutableSet.of(ALLOW_ROLE_ID, DENY_ROLE_ID));

    List<MetricPO> metrics = calculateMetrics(snapshot);

    assertEquals(0.0, metricValue(metrics, MetricDataService.Metric.CATALOG_COUNT));
    assertEquals(0.0, metricValue(metrics, MetricDataService.Metric.ASSET_COUNT));
  }

  private List<MetricPO> calculateMetrics(MetalakeSnapshot snapshot) {
    MetricsCollector.getInstance().getMetalakeSnapshots().put(metalakeName, snapshot);
    return new MetricsCalculator(snapshot, categoryToTagNames)
        .calculateMetricsForUser("testUser", true, false);
  }

  private MetalakeSnapshot createRoleSnapshot(
      Map<Long, List<SecurableObject>> roleIdToSecurableObjects, Set<Long> roleIds) {
    AssetNode root =
        new AssetNode(
            100L, metalakeName, MetadataObject.Type.METALAKE, null, Collections.emptySet());
    AssetNode catalog =
        new AssetNode(101L, "catalog1", MetadataObject.Type.CATALOG, root, Collections.emptySet());
    root.addChild(catalog);

    return new MetalakeSnapshot(
        root,
        ImmutableMap.of(root.getId(), root, catalog.getId(), catalog),
        ImmutableMap.of("testUser", TEST_USER_ID),
        roleIdToSecurableObjects,
        ImmutableMap.of(TEST_USER_ID, roleIds),
        0L,
        Collections.emptyMap(),
        ImmutableSet.of(catalog),
        Collections.emptySet(),
        Collections.emptySet(),
        Collections.emptySet(),
        Collections.emptySet(),
        Collections.emptySet());
  }

  private static double metricValue(List<MetricPO> metrics, MetricDataService.Metric metric) {
    return metrics.stream()
        .filter(metricPO -> metric.getName().equals(metricPO.getMetricName()))
        .findFirst()
        .orElseThrow(() -> new AssertionError("Missing metric: " + metric.getName()))
        .getMetricValue();
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
