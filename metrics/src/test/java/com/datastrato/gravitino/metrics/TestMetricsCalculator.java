/*
 * Copyright 2024 Datastrato Inc.
 */
package com.datastrato.gravitino.metrics;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.when;

import com.datastrato.gravitino.metrics.dto.MetricState;
import com.datastrato.gravitino.metrics.storage.relational.MetricPO;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.ImmutableSet;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;
import org.apache.gravitino.Catalog;
import org.apache.gravitino.Config;
import org.apache.gravitino.Configs;
import org.apache.gravitino.GravitinoEnv;
import org.apache.gravitino.MetadataObject;
import org.apache.gravitino.authorization.Owner;
import org.apache.gravitino.authorization.Privileges;
import org.apache.gravitino.authorization.SecurableObject;
import org.apache.gravitino.authorization.SecurableObjects;
import org.apache.gravitino.utils.NameIdentifierUtil;
import org.junit.jupiter.api.Test;
import org.mockito.MockedStatic;

class TestMetricsCalculator {
  private static final String METALAKE = "test_metalake";
  private static final String UNAVAILABLE_MESSAGE = "Metric data is temporarily unavailable.";

  @Test
  void testCompleteMetricsUseOnlyAssetsAndNewMetricNames() {
    SnapshotBuilder builder = new SnapshotBuilder();
    AssetNode relational =
        builder.catalog(101L, "relational", Catalog.Type.RELATIONAL, "jdbc-postgresql", false);
    AssetNode relationalSchema = builder.schema(102L, "schema", relational);
    builder.asset(103L, "table", MetadataObject.Type.TABLE, relationalSchema);
    builder.asset(
        104L, "view", MetadataObject.Type.VIEW, relationalSchema, ImmutableSet.of(owner()));
    builder.asset(105L, "function", MetadataObject.Type.FUNCTION, relationalSchema);
    AssetNode messaging =
        builder.catalog(201L, "messaging", Catalog.Type.MESSAGING, "kafka", false);
    AssetNode messagingSchema = builder.schema(202L, "schema", messaging);
    AssetNode topic = builder.asset(203L, "topic", MetadataObject.Type.TOPIC, messagingSchema);
    AssetNode fileset = builder.catalog(301L, "fileset", Catalog.Type.FILESET, "fileset", false);
    AssetNode filesetSchema = builder.schema(302L, "schema", fileset);
    builder.asset(303L, "fileset", MetadataObject.Type.FILESET, filesetSchema);
    AssetNode model = builder.catalog(401L, "model", Catalog.Type.MODEL, "acme-model", false);
    AssetNode modelSchema = builder.schema(402L, "schema", model);
    AssetNode modelAsset = builder.asset(403L, "model", MetadataObject.Type.MODEL, modelSchema);

    builder.tag(relationalSchema);
    builder.tag(topic);
    builder.policy(relational);
    builder.policy(modelAsset);
    builder.policyCounts(3, 1);

    Map<String, MetricPO> metrics = calculate(builder.build());

    assertMetric(metrics, "asset_count", 6.0, MetricState.COMPLETE, null);
    assertMetric(metrics, "tagged_asset_count", 4.0, MetricState.COMPLETE, null);
    assertMetric(metrics, "owned_asset_count", 1.0, MetricState.COMPLETE, null);
    assertMetric(metrics, "policy_covered_asset_count", 4.0, MetricState.COMPLETE, null);
    assertMetric(metrics, "policy_count", 3.0, MetricState.COMPLETE, null);
    assertMetric(metrics, "disabled_policy_count", 1.0, MetricState.COMPLETE, null);
    assertMetric(
        metrics,
        "by_catalog::relational::jdbc-postgresql::asset_count",
        3.0,
        MetricState.COMPLETE,
        null);
    assertMetric(
        metrics, "by_catalog::messaging::kafka::asset_count", 1.0, MetricState.COMPLETE, null);
    assertMetric(
        metrics, "by_catalog::fileset::fileset::asset_count", 1.0, MetricState.COMPLETE, null);
    assertMetric(
        metrics, "by_catalog::model::acme-model::asset_count", 1.0, MetricState.COMPLETE, null);
    assertMetric(
        metrics, "by_asset_type::VIEW::owned_asset_count", 1.0, MetricState.COMPLETE, null);
    assertMetric(
        metrics,
        "by_asset_type::TABLE::policy_covered_asset_count",
        1.0,
        MetricState.COMPLETE,
        null);
    assertMetric(
        metrics, "by_asset_type::TOPIC::tagged_asset_count", 1.0, MetricState.COMPLETE, null);
    assertMetric(
        metrics,
        "by_asset_type::FUNCTION::policy_covered_asset_count",
        1.0,
        MetricState.COMPLETE,
        null);
    assertMetric(
        metrics,
        "by_asset_type::MODEL::policy_covered_asset_count",
        1.0,
        MetricState.COMPLETE,
        null);
    assertFalse(metrics.containsKey("by_catalog::relational::asset_count"));
    assertFalse(metrics.containsKey("by_catalog::relational::jdbc-postgresql::policy_count"));
    assertFalse(metrics.containsKey("by_asset_type::TABLE::disabled_policy_count"));
    assertMetric(
        metrics,
        DirectChildCountMetricNames.forCatalog("relational"),
        1.0,
        MetricState.COMPLETE,
        null);
    assertMetric(
        metrics,
        DirectChildCountMetricNames.forSchema("relational", "schema"),
        3.0,
        MetricState.COMPLETE,
        null);
    assertEquals(54, metrics.size());
  }

  @Test
  void testOneCatalogFailureMakesCatalogUnavailableAndDependenciesPartial() {
    SnapshotBuilder builder = new SnapshotBuilder();
    AssetNode healthy =
        builder.catalog(101L, "healthy", Catalog.Type.RELATIONAL, "jdbc-postgresql", false);
    AssetNode schema = builder.schema(102L, "schema", healthy);
    builder.asset(103L, "table", MetadataObject.Type.TABLE, schema);
    builder.catalog(201L, "failed", Catalog.Type.RELATIONAL, "vendor-custom", true);

    Map<String, MetricPO> metrics = calculate(builder.build());

    assertMetric(
        metrics,
        "by_catalog::healthy::jdbc-postgresql::asset_count",
        1.0,
        MetricState.COMPLETE,
        null);
    assertMetric(
        metrics,
        "by_catalog::failed::vendor-custom::asset_count",
        null,
        MetricState.UNAVAILABLE,
        UNAVAILABLE_MESSAGE);
    assertMetric(
        metrics,
        "asset_count",
        1.0,
        MetricState.PARTIAL,
        "Some catalog data is temporarily unavailable.");
    assertMetric(
        metrics,
        "by_asset_type::TABLE::asset_count",
        1.0,
        MetricState.PARTIAL,
        "Some catalog data is temporarily unavailable.");
    assertMetric(
        metrics,
        "by_asset_type::FUNCTION::asset_count",
        0.0,
        MetricState.PARTIAL,
        "Some catalog data is temporarily unavailable.");
    assertMetric(metrics, "by_asset_type::TOPIC::asset_count", 0.0, MetricState.COMPLETE, null);
    assertMetric(
        metrics,
        DirectChildCountMetricNames.forCatalog("healthy"),
        1.0,
        MetricState.COMPLETE,
        null);
    assertMetric(
        metrics,
        DirectChildCountMetricNames.forCatalog("failed"),
        null,
        MetricState.UNAVAILABLE,
        UNAVAILABLE_MESSAGE);
  }

  @Test
  void testDirectChildCountsUseHierarchicalParentsAndIncludeZero() {
    SnapshotBuilder builder = new SnapshotBuilder();
    AssetNode catalog =
        builder.catalog(101L, "catalog", Catalog.Type.RELATIONAL, "jdbc-postgresql", false);
    AssetNode top = builder.schema(102L, "top", catalog);
    AssetNode child = builder.schema(103L, "top:child", top);
    builder.schema(104L, "empty", catalog);
    builder.asset(105L, "table", MetadataObject.Type.TABLE, child);

    Map<String, MetricPO> metrics = calculate(builder.build());

    assertMetric(
        metrics,
        DirectChildCountMetricNames.forCatalog("catalog"),
        2.0,
        MetricState.COMPLETE,
        null);
    assertMetric(
        metrics,
        DirectChildCountMetricNames.forSchema("catalog", "top"),
        1.0,
        MetricState.COMPLETE,
        null);
    assertMetric(
        metrics,
        DirectChildCountMetricNames.forSchema("catalog", "top:child"),
        1.0,
        MetricState.COMPLETE,
        null);
    assertMetric(
        metrics,
        DirectChildCountMetricNames.forSchema("catalog", "empty"),
        0.0,
        MetricState.COMPLETE,
        null);
  }

  @Test
  void testFailedCatalogWithoutViewListingDoesNotAffectViewMetrics() {
    SnapshotBuilder builder = new SnapshotBuilder();
    builder.catalog(101L, "failed", Catalog.Type.RELATIONAL, "hive", true);
    builder.viewListingSupport("failed", false);

    Map<String, MetricPO> metrics = calculate(builder.build());

    assertMetric(
        metrics,
        "by_asset_type::TABLE::asset_count",
        null,
        MetricState.UNAVAILABLE,
        UNAVAILABLE_MESSAGE);
    assertMetric(metrics, "by_asset_type::VIEW::asset_count", 0.0, MetricState.COMPLETE, null);
  }

  @Test
  void testAllRelevantCatalogsFailedMakesMetricUnavailable() {
    SnapshotBuilder builder = new SnapshotBuilder();
    builder.catalog(101L, "failed", Catalog.Type.RELATIONAL, "hive", true);
    builder.policyCounts(2, 1);

    Map<String, MetricPO> metrics = calculate(builder.build());

    assertMetric(metrics, "asset_count", null, MetricState.UNAVAILABLE, UNAVAILABLE_MESSAGE);
    assertMetric(
        metrics,
        "by_asset_type::TABLE::asset_count",
        null,
        MetricState.UNAVAILABLE,
        UNAVAILABLE_MESSAGE);
    assertMetric(
        metrics,
        "by_asset_type::VIEW::asset_count",
        null,
        MetricState.UNAVAILABLE,
        UNAVAILABLE_MESSAGE);
    assertMetric(
        metrics,
        "by_asset_type::FUNCTION::asset_count",
        null,
        MetricState.UNAVAILABLE,
        UNAVAILABLE_MESSAGE);
    assertMetric(metrics, "by_asset_type::FILESET::asset_count", 0.0, MetricState.COMPLETE, null);
    assertMetric(metrics, "policy_count", 2.0, MetricState.COMPLETE, null);
    assertMetric(metrics, "disabled_policy_count", 1.0, MetricState.COMPLETE, null);
  }

  @Test
  void testOwnedAssetCountUsesEveryAuthorizedUsersVisibleAssets() {
    long roleId = 10L;
    long userId = 20L;
    Owner assetOwner = mock(Owner.class);
    when(assetOwner.type()).thenReturn(Owner.Type.USER);
    when(assetOwner.name()).thenReturn("asset_owner");

    AssetNode root =
        new AssetNode(1L, METALAKE, MetadataObject.Type.METALAKE, null, Collections.emptySet());
    AssetNode catalog =
        new AssetNode(2L, "catalog", MetadataObject.Type.CATALOG, root, Collections.emptySet());
    AssetNode schema =
        new AssetNode(3L, "schema", MetadataObject.Type.SCHEMA, catalog, Collections.emptySet());
    AssetNode visibleTable =
        new AssetNode(
            4L, "visible_table", MetadataObject.Type.TABLE, schema, ImmutableSet.of(assetOwner));
    AssetNode hiddenView =
        new AssetNode(
            5L, "hidden_view", MetadataObject.Type.VIEW, schema, ImmutableSet.of(assetOwner));
    AssetNode visibleFunction =
        new AssetNode(
            6L, "visible_function", MetadataObject.Type.FUNCTION, schema, Collections.emptySet());
    root.addChild(catalog);
    catalog.addChild(schema);
    schema.addChild(visibleTable);
    schema.addChild(hiddenView);
    schema.addChild(visibleFunction);

    SecurableObject catalogGrant =
        SecurableObjects.ofCatalog(
            catalog.getName(), ImmutableList.of(Privileges.UseCatalog.allow()));
    SecurableObject schemaGrant =
        SecurableObjects.ofSchema(
            catalogGrant, schema.getName(), ImmutableList.of(Privileges.UseSchema.allow()));
    SecurableObject tableGrant =
        SecurableObjects.ofTable(
            schemaGrant, visibleTable.getName(), ImmutableList.of(Privileges.SelectTable.allow()));
    SecurableObject functionGrant =
        SecurableObjects.ofFunction(
            schemaGrant,
            visibleFunction.getName(),
            ImmutableList.of(Privileges.ExecuteFunction.allow()));
    MetalakeSnapshot snapshot =
        MetalakeSnapshot.builder()
            .assetTreeRoot(root)
            .assetNodeById(
                ImmutableMap.<Long, AssetNode>builder()
                    .put(root.getId(), root)
                    .put(catalog.getId(), catalog)
                    .put(schema.getId(), schema)
                    .put(visibleTable.getId(), visibleTable)
                    .put(hiddenView.getId(), hiddenView)
                    .put(visibleFunction.getId(), visibleFunction)
                    .build())
            .userNameToUserId(ImmutableMap.of("dashboard_user", userId, "asset_owner", 21L))
            .roleIdToSecurableObjects(
                ImmutableMap.of(
                    roleId, ImmutableList.of(catalogGrant, schemaGrant, tableGrant, functionGrant)))
            .userIdToRoleIds(ImmutableMap.of(userId, ImmutableSet.of(roleId)))
            .taggedObjectIds(Collections.emptySet())
            .catalogNodes(ImmutableSet.of(catalog))
            .schemaNodes(ImmutableSet.of(schema))
            .tableNodes(ImmutableSet.of(visibleTable))
            .viewNodes(ImmutableSet.of(hiddenView))
            .functionNodes(ImmutableSet.of(visibleFunction))
            .filesetNodes(Collections.emptySet())
            .topicNodes(Collections.emptySet())
            .modelNodes(Collections.emptySet())
            .enabledPolicyObjectIds(Collections.emptySet())
            .failedCatalogNames(Collections.emptySet())
            .catalogTypes(ImmutableMap.of("catalog", Catalog.Type.RELATIONAL))
            .catalogProviders(ImmutableMap.of("catalog", "hive"))
            .viewListingSupportByCatalog(ImmutableMap.of("catalog", true))
            .build();

    GravitinoEnv env = mock(GravitinoEnv.class);
    Config config = mock(Config.class);
    when(env.config()).thenReturn(config);
    when(config.get(Configs.ENABLE_AUTHORIZATION)).thenReturn(true);
    when(config.get(Configs.GRAVITINO_AUTHORIZATION_THREAD_POOL_SIZE)).thenReturn(2);
    try (MockedStatic<GravitinoEnv> mockedEnv = mockStatic(GravitinoEnv.class)) {
      mockedEnv.when(GravitinoEnv::getInstance).thenReturn(env);
      MetricsCollector.getInstance().getMetalakeSnapshots().put(METALAKE, snapshot);

      Map<String, MetricPO> metrics =
          new MetricsCalculator(snapshot)
              .calculateMetricsForUser("dashboard_user", true, false).stream()
                  .collect(Collectors.toMap(MetricPO::getMetricName, metric -> metric));

      assertMetric(metrics, "asset_count", 2.0, MetricState.COMPLETE, null);
      assertMetric(metrics, "owned_asset_count", 1.0, MetricState.COMPLETE, null);
      assertMetric(
          metrics, "by_asset_type::FUNCTION::asset_count", 1.0, MetricState.COMPLETE, null);
      assertMetric(
          metrics, "by_asset_type::VIEW::owned_asset_count", 0.0, MetricState.COMPLETE, null);
      assertMetric(
          metrics,
          DirectChildCountMetricNames.forCatalog("catalog"),
          1.0,
          MetricState.COMPLETE,
          null);
      assertMetric(
          metrics,
          DirectChildCountMetricNames.forSchema("catalog", "schema"),
          2.0,
          MetricState.COMPLETE,
          null);
    } finally {
      MetricsCollector.getInstance().getMetalakeSnapshots().remove(METALAKE);
    }
  }

  private static Map<String, MetricPO> calculate(MetalakeSnapshot snapshot) {
    List<MetricPO> metrics = new MetricsCalculator(snapshot).calculateMetricsForDisableAuthz();
    return metrics.stream().collect(Collectors.toMap(MetricPO::getMetricName, metric -> metric));
  }

  private static void assertMetric(
      Map<String, MetricPO> metrics,
      String name,
      Double value,
      MetricState state,
      String expectedMessage) {
    MetricPO metric = metrics.get(name);
    assertNotNull(metric, "Missing metric: " + name);
    assertEquals(value, metric.getMetricValue());
    assertEquals(state, metric.getMetricState());
    assertEquals(expectedMessage, metric.getMetricMessage());
  }

  private static Owner owner() {
    return mock(Owner.class);
  }

  private static class SnapshotBuilder {
    private final AssetNode root =
        new AssetNode(1L, METALAKE, MetadataObject.Type.METALAKE, null, Collections.emptySet());
    private final Map<Long, AssetNode> nodesById = new HashMap<>();
    private final Set<AssetNode> catalogs = new HashSet<>();
    private final Set<AssetNode> schemas = new HashSet<>();
    private final Set<AssetNode> tables = new HashSet<>();
    private final Set<AssetNode> views = new HashSet<>();
    private final Set<AssetNode> functions = new HashSet<>();
    private final Set<AssetNode> topics = new HashSet<>();
    private final Set<AssetNode> filesets = new HashSet<>();
    private final Set<AssetNode> models = new HashSet<>();
    private final Set<Long> taggedObjectIds = new HashSet<>();
    private final Set<Long> policyObjectIds = new HashSet<>();
    private final Set<String> failedCatalogs = new HashSet<>();
    private final Map<String, Catalog.Type> catalogTypes = new HashMap<>();
    private final Map<String, String> catalogProviders = new HashMap<>();
    private final Map<String, Boolean> viewListingSupportByCatalog = new HashMap<>();
    private long policyCount;
    private long disabledPolicyCount;

    private SnapshotBuilder() {
      nodesById.put(root.getId(), root);
    }

    private AssetNode catalog(
        long id, String name, Catalog.Type type, String provider, boolean collectionFailed) {
      AssetNode catalog =
          new AssetNode(id, name, MetadataObject.Type.CATALOG, root, Collections.emptySet());
      root.addChild(catalog);
      nodesById.put(id, catalog);
      catalogs.add(catalog);
      catalogTypes.put(name, type);
      catalogProviders.put(name, provider);
      if (collectionFailed) {
        failedCatalogs.add(name);
      }
      return catalog;
    }

    private void viewListingSupport(String catalogName, boolean supported) {
      viewListingSupportByCatalog.put(catalogName, supported);
    }

    private AssetNode schema(long id, String name, AssetNode parent) {
      String catalogName =
          parent.getType() == MetadataObject.Type.CATALOG
              ? parent.getName()
              : parent.getNameIdent().namespace().level(1);
      AssetNode schema =
          new AssetNode(
              id,
              name,
              NameIdentifierUtil.ofSchema(METALAKE, catalogName, name),
              MetadataObject.Type.SCHEMA,
              parent,
              Collections.emptySet());
      parent.addChild(schema);
      nodesById.put(id, schema);
      schemas.add(schema);
      return schema;
    }

    private AssetNode asset(long id, String name, MetadataObject.Type type, AssetNode schema) {
      return asset(id, name, type, schema, Collections.emptySet());
    }

    private AssetNode asset(
        long id, String name, MetadataObject.Type type, AssetNode schema, Set<Owner> owners) {
      AssetNode asset = new AssetNode(id, name, type, schema, owners);
      schema.addChild(asset);
      nodesById.put(id, asset);
      switch (type) {
        case TABLE:
          tables.add(asset);
          break;
        case VIEW:
          views.add(asset);
          break;
        case FUNCTION:
          functions.add(asset);
          break;
        case TOPIC:
          topics.add(asset);
          break;
        case FILESET:
          filesets.add(asset);
          break;
        case MODEL:
          models.add(asset);
          break;
        default:
          throw new IllegalArgumentException("Unsupported asset type: " + type);
      }
      return asset;
    }

    private void tag(AssetNode node) {
      taggedObjectIds.add(node.getId());
    }

    private void policy(AssetNode node) {
      policyObjectIds.add(node.getId());
    }

    private void policyCounts(long count, long disabledCount) {
      policyCount = count;
      disabledPolicyCount = disabledCount;
    }

    private MetalakeSnapshot build() {
      return MetalakeSnapshot.builder()
          .assetTreeRoot(root)
          .assetNodeById(nodesById)
          .userNameToUserId(ImmutableMap.of())
          .roleIdToSecurableObjects(ImmutableMap.of())
          .userIdToRoleIds(ImmutableMap.of())
          .taggedObjectIds(taggedObjectIds)
          .catalogNodes(catalogs)
          .schemaNodes(schemas)
          .tableNodes(tables)
          .viewNodes(views)
          .functionNodes(functions)
          .filesetNodes(filesets)
          .topicNodes(topics)
          .modelNodes(models)
          .enabledPolicyObjectIds(policyObjectIds)
          .policyCount(policyCount)
          .disabledPolicyCount(disabledPolicyCount)
          .failedCatalogNames(failedCatalogs)
          .catalogTypes(catalogTypes)
          .catalogProviders(catalogProviders)
          .viewListingSupportByCatalog(viewListingSupportByCatalog)
          .build();
    }
  }
}
