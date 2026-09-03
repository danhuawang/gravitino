/*
 * Copyright 2024 Datastrato Inc.
 */
package com.datastrato.gravitino.metrics;

import com.datastrato.gravitino.metrics.dto.MetricState;
import com.datastrato.gravitino.metrics.storage.relational.MetricPO;
import com.datastrato.gravitino.metrics.storage.relational.service.MetricDataService;
import java.io.IOException;
import java.security.Principal;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;
import java.util.stream.Collectors;
import javax.annotation.Nullable;
import org.apache.gravitino.Catalog;
import org.apache.gravitino.Entity;
import org.apache.gravitino.MetadataObject;
import org.apache.gravitino.NameIdentifier;
import org.apache.gravitino.UserPrincipal;
import org.apache.gravitino.authorization.GravitinoAuthorizer;
import org.apache.gravitino.server.authorization.MetadataAuthzHelper;
import org.apache.gravitino.server.authorization.expression.AuthorizationExpressionConstants;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/** Calculates dashboard asset metrics from one immutable metalake snapshot. */
public class MetricsCalculator {
  private static final Logger LOG = LoggerFactory.getLogger(MetricsCalculator.class);
  private static final String BY_ASSET_TYPE_PREFIX =
      "by_asset_type" + DashboardMetricNames.SEPARATOR;
  private static final String PARTIAL_MESSAGE = "Some catalog data is temporarily unavailable.";
  private static final String UNAVAILABLE_MESSAGE = "Metric data is temporarily unavailable.";
  private static final List<MetadataObject.Type> ASSET_TYPES =
      Arrays.asList(
          MetadataObject.Type.TABLE,
          MetadataObject.Type.VIEW,
          MetadataObject.Type.FUNCTION,
          MetadataObject.Type.TOPIC,
          MetadataObject.Type.FILESET,
          MetadataObject.Type.MODEL);
  private static final List<MetricDataService.Metric> ASSET_METRICS =
      Arrays.asList(
          MetricDataService.Metric.ASSET_COUNT,
          MetricDataService.Metric.TAGGED_ASSET_COUNT,
          MetricDataService.Metric.OWNED_ASSET_COUNT,
          MetricDataService.Metric.POLICY_COVERED_ASSET_COUNT);

  private final MetalakeSnapshot metalakeSnapshot;
  private final String metalakeName;

  /**
   * Creates a calculator for one metalake snapshot.
   *
   * @param metalakeSnapshot complete shared data plus per-catalog collection outcomes
   */
  public MetricsCalculator(MetalakeSnapshot metalakeSnapshot) {
    this.metalakeSnapshot = metalakeSnapshot;
    this.metalakeName = metalakeSnapshot.getAssetTreeRoot().getName();
  }

  /**
   * Calculates metrics with every enabled catalog and asset visible.
   *
   * @return immutable metric rows
   */
  public List<MetricPO> calculateMetricsForDisableAuthz() {
    return calculateMetricsForUser("", null);
  }

  /**
   * Calculates metrics for the assets visible to one user.
   *
   * @param username user name
   * @param enableAuthz whether authorization filtering is enabled
   * @param forMetalakeOwner whether the result is stored under the metalake-owner synthetic ID
   * @return immutable metric rows
   */
  public List<MetricPO> calculateMetricsForUser(
      String username, boolean enableAuthz, boolean forMetalakeOwner) {
    if (!enableAuthz) {
      return calculateMetricsForUser(username, null);
    }

    GravitinoAuthorizer authorizer = new MemoizedJcasbinAuthorizer();
    authorizer.initialize();
    try {
      return calculateMetricsForUser(username, authorizer);
    } finally {
      try {
        authorizer.close();
      } catch (IOException e) {
        LOG.warn("Error closing authorizer", e);
      }
    }
  }

  List<MetricPO> calculateMetricsForUser(
      String username, @Nullable GravitinoAuthorizer authorizer) {
    if (authorizer != null && !metalakeSnapshot.getUserNameToUserId().containsKey(username)) {
      LOG.warn("User {} not found in snapshot, skipping metrics calculation", username);
      return Collections.emptyList();
    }

    Set<AssetNode> visibleCatalogNodes;
    Set<AssetNode> visibleTableNodes;
    Set<AssetNode> visibleViewNodes;
    Set<AssetNode> visibleFunctionNodes;
    Set<AssetNode> visibleFilesetNodes;
    Set<AssetNode> visibleTopicNodes;
    Set<AssetNode> visibleModelNodes;
    Set<AssetNode> directCountVisibleSchemaNodes;
    Set<AssetNode> directCountVisibleAssetNodes;
    if (authorizer != null) {
      Principal principal = new UserPrincipal(username);
      visibleCatalogNodes =
          getVisibleNodes(
              metalakeName,
              principal,
              authorizer,
              AuthorizationExpressionConstants.LOAD_CATALOG_AUTHORIZATION_EXPRESSION,
              Entity.EntityType.CATALOG,
              metalakeSnapshot.getCatalogNodes());
      visibleTableNodes =
          getVisibleNodes(
              metalakeName,
              principal,
              authorizer,
              AuthorizationExpressionConstants.LOAD_TABLE_AUTHORIZATION_EXPRESSION,
              Entity.EntityType.TABLE,
              metalakeSnapshot.getTableNodes());
      visibleViewNodes =
          getVisibleNodes(
              metalakeName,
              principal,
              authorizer,
              AuthorizationExpressionConstants.LOAD_VIEW_AUTHORIZATION_EXPRESSION,
              Entity.EntityType.VIEW,
              metalakeSnapshot.getViewNodes());
      visibleFunctionNodes =
          getVisibleNodes(
              metalakeName,
              principal,
              authorizer,
              AuthorizationExpressionConstants.LOAD_FUNCTION_AUTHORIZATION_EXPRESSION,
              Entity.EntityType.FUNCTION,
              metalakeSnapshot.getFunctionNodes());
      visibleFilesetNodes =
          getVisibleNodes(
              metalakeName,
              principal,
              authorizer,
              AuthorizationExpressionConstants.LOAD_FILESET_AUTHORIZATION_EXPRESSION,
              Entity.EntityType.FILESET,
              metalakeSnapshot.getFilesetNodes());
      visibleTopicNodes =
          getVisibleNodes(
              metalakeName,
              principal,
              authorizer,
              AuthorizationExpressionConstants.LOAD_TOPICS_AUTHORIZATION_EXPRESSION,
              Entity.EntityType.TOPIC,
              metalakeSnapshot.getTopicNodes());
      visibleModelNodes =
          getVisibleNodes(
              metalakeName,
              principal,
              authorizer,
              AuthorizationExpressionConstants.LOAD_MODEL_AUTHORIZATION_EXPRESSION,
              Entity.EntityType.MODEL,
              metalakeSnapshot.getModelNodes());
      directCountVisibleSchemaNodes =
          getVisibleNodes(
              metalakeName,
              principal,
              authorizer,
              AuthorizationExpressionConstants.FILTER_SCHEMA_AUTHORIZATION_EXPRESSION,
              Entity.EntityType.SCHEMA,
              metalakeSnapshot.getSchemaNodes());
      directCountVisibleAssetNodes = new HashSet<>();
      directCountVisibleAssetNodes.addAll(
          getVisibleNodes(
              metalakeName,
              principal,
              authorizer,
              AuthorizationExpressionConstants.FILTER_TABLE_AUTHORIZATION_EXPRESSION,
              Entity.EntityType.TABLE,
              metalakeSnapshot.getTableNodes()));
      directCountVisibleAssetNodes.addAll(
          getVisibleNodes(
              metalakeName,
              principal,
              authorizer,
              AuthorizationExpressionConstants.FILTER_VIEW_AUTHORIZATION_EXPRESSION,
              Entity.EntityType.VIEW,
              metalakeSnapshot.getViewNodes()));
      directCountVisibleAssetNodes.addAll(
          getVisibleNodes(
              metalakeName,
              principal,
              authorizer,
              AuthorizationExpressionConstants.FILTER_FUNCTION_AUTHORIZATION_EXPRESSION,
              Entity.EntityType.FUNCTION,
              metalakeSnapshot.getFunctionNodes()));
      directCountVisibleAssetNodes.addAll(
          getVisibleNodes(
              metalakeName,
              principal,
              authorizer,
              AuthorizationExpressionConstants.FILTER_FILESET_AUTHORIZATION_EXPRESSION,
              Entity.EntityType.FILESET,
              metalakeSnapshot.getFilesetNodes()));
      directCountVisibleAssetNodes.addAll(
          getVisibleNodes(
              metalakeName,
              principal,
              authorizer,
              AuthorizationExpressionConstants.FILTER_TOPICS_AUTHORIZATION_EXPRESSION,
              Entity.EntityType.TOPIC,
              metalakeSnapshot.getTopicNodes()));
      directCountVisibleAssetNodes.addAll(
          getVisibleNodes(
              metalakeName,
              principal,
              authorizer,
              AuthorizationExpressionConstants.FILTER_MODEL_AUTHORIZATION_EXPRESSION,
              Entity.EntityType.MODEL,
              metalakeSnapshot.getModelNodes()));
    } else {
      visibleCatalogNodes = new HashSet<>(metalakeSnapshot.getCatalogNodes());
      visibleTableNodes = new HashSet<>(metalakeSnapshot.getTableNodes());
      visibleViewNodes = new HashSet<>(metalakeSnapshot.getViewNodes());
      visibleFunctionNodes = new HashSet<>(metalakeSnapshot.getFunctionNodes());
      visibleFilesetNodes = new HashSet<>(metalakeSnapshot.getFilesetNodes());
      visibleTopicNodes = new HashSet<>(metalakeSnapshot.getTopicNodes());
      visibleModelNodes = new HashSet<>(metalakeSnapshot.getModelNodes());
      directCountVisibleSchemaNodes = new HashSet<>(metalakeSnapshot.getSchemaNodes());
      directCountVisibleAssetNodes = new HashSet<>(visibleAssetNodes(metalakeSnapshot));
    }

    Set<AssetNode> visibleAssetNodes = new HashSet<>(visibleTableNodes);
    visibleAssetNodes.addAll(visibleViewNodes);
    visibleAssetNodes.addAll(visibleFunctionNodes);
    visibleAssetNodes.addAll(visibleFilesetNodes);
    visibleAssetNodes.addAll(visibleTopicNodes);
    visibleAssetNodes.addAll(visibleModelNodes);

    Set<String> visibleCatalogNames =
        visibleCatalogNodes.stream().map(AssetNode::getName).collect(Collectors.toSet());
    directCountVisibleSchemaNodes.removeIf(
        schemaNode -> !visibleCatalogNames.contains(catalogName(schemaNode)));
    Set<NameIdentifier> directCountVisibleSchemaIdents =
        directCountVisibleSchemaNodes.stream()
            .map(AssetNode::getNameIdent)
            .collect(Collectors.toSet());
    directCountVisibleAssetNodes.removeIf(
        assetNode -> !directCountVisibleSchemaIdents.contains(assetNode.getParentIdent()));
    Set<String> failedVisibleCatalogNames =
        visibleCatalogNames.stream()
            .filter(metalakeSnapshot.getFailedCatalogNames()::contains)
            .collect(Collectors.toSet());
    Set<String> successfulVisibleCatalogNames = new HashSet<>(visibleCatalogNames);
    successfulVisibleCatalogNames.removeAll(failedVisibleCatalogNames);

    Map<String, Set<AssetNode>> assetsByCatalog = new HashMap<>();
    Map<MetadataObject.Type, Set<AssetNode>> assetsByType = new HashMap<>();
    for (AssetNode node : visibleAssetNodes) {
      assetsByCatalog.computeIfAbsent(catalogName(node), ignored -> new HashSet<>()).add(node);
      assetsByType.computeIfAbsent(node.getType(), ignored -> new HashSet<>()).add(node);
    }

    Map<AssetNode, Boolean> taggedCache = new HashMap<>();
    Map<AssetNode, Boolean> policyCache = new HashMap<>();
    List<MetricPO> metrics = new ArrayList<>();

    addAggregateMetrics(
        metrics,
        MetricDataService.Metric::getName,
        visibleAssetNodes,
        failedVisibleCatalogNames,
        successfulVisibleCatalogNames,
        taggedCache,
        policyCache);
    addPolicyMetrics(metrics);

    visibleCatalogNodes.stream()
        .sorted(Comparator.comparing(AssetNode::getName))
        .forEach(
            catalogNode -> {
              String catalogName = catalogNode.getName();
              String provider = metalakeSnapshot.getCatalogProviders().get(catalogName);
              Function<MetricDataService.Metric, String> metricNameBuilder =
                  metric ->
                      DashboardMetricNames.forCatalog(catalogName, provider, metric.getName());
              if (failedVisibleCatalogNames.contains(catalogName)) {
                addUnavailableMetrics(metrics, metricNameBuilder);
                return;
              }

              Set<AssetNode> catalogAssets =
                  assetsByCatalog.getOrDefault(catalogName, Collections.emptySet());
              addCompleteMetrics(
                  metrics, metricNameBuilder, catalogAssets, taggedCache, policyCache);
            });

    for (MetadataObject.Type assetType : ASSET_TYPES) {
      Set<AssetNode> typeAssets = assetsByType.getOrDefault(assetType, Collections.emptySet());
      Set<String> failedDependencies =
          failedVisibleCatalogNames.stream()
              .filter(catalogName -> catalogSupports(catalogName, assetType))
              .collect(Collectors.toSet());
      Set<String> successfulDependencies =
          successfulVisibleCatalogNames.stream()
              .filter(catalogName -> catalogSupports(catalogName, assetType))
              .collect(Collectors.toSet());
      String prefix = BY_ASSET_TYPE_PREFIX + assetType.name() + DashboardMetricNames.SEPARATOR;
      addAggregateMetrics(
          metrics,
          metric -> prefix + metric.getName(),
          typeAssets,
          failedDependencies,
          successfulDependencies,
          taggedCache,
          policyCache);
    }

    addDirectChildCountMetrics(
        metrics,
        visibleCatalogNodes,
        directCountVisibleSchemaNodes,
        directCountVisibleAssetNodes,
        failedVisibleCatalogNames);

    return Collections.unmodifiableList(metrics);
  }

  private static Set<AssetNode> visibleAssetNodes(MetalakeSnapshot snapshot) {
    Set<AssetNode> assets = new HashSet<>(snapshot.getTableNodes());
    assets.addAll(snapshot.getViewNodes());
    assets.addAll(snapshot.getFunctionNodes());
    assets.addAll(snapshot.getFilesetNodes());
    assets.addAll(snapshot.getTopicNodes());
    assets.addAll(snapshot.getModelNodes());
    return assets;
  }

  private void addDirectChildCountMetrics(
      List<MetricPO> metrics,
      Set<AssetNode> visibleCatalogNodes,
      Set<AssetNode> visibleSchemaNodes,
      Set<AssetNode> visibleAssetNodes,
      Set<String> failedVisibleCatalogNames) {
    Map<NameIdentifier, Long> countByParent = new HashMap<>();
    visibleSchemaNodes.forEach(
        schemaNode -> countDirectChild(countByParent, schemaNode.getParentIdent()));
    visibleAssetNodes.forEach(
        assetNode -> countDirectChild(countByParent, assetNode.getParentIdent()));

    visibleCatalogNodes.stream()
        .sorted(Comparator.comparing(AssetNode::getName))
        .forEach(
            catalogNode -> {
              String metricName = DirectChildCountMetricNames.forCatalog(catalogNode.getName());
              if (failedVisibleCatalogNames.contains(catalogNode.getName())) {
                metrics.add(
                    createMetricPO(metricName, null, MetricState.UNAVAILABLE, UNAVAILABLE_MESSAGE));
              } else {
                metrics.add(
                    createMetricPO(
                        metricName,
                        countByParent.getOrDefault(catalogNode.getNameIdent(), 0L).doubleValue(),
                        MetricState.COMPLETE,
                        null));
              }
            });

    visibleSchemaNodes.stream()
        .filter(schemaNode -> !failedVisibleCatalogNames.contains(catalogName(schemaNode)))
        .sorted(Comparator.comparing(node -> node.getNameIdent().toString()))
        .forEach(
            schemaNode ->
                metrics.add(
                    createMetricPO(
                        DirectChildCountMetricNames.forSchema(
                            catalogName(schemaNode), schemaNode.getName()),
                        countByParent.getOrDefault(schemaNode.getNameIdent(), 0L).doubleValue(),
                        MetricState.COMPLETE,
                        null)));
  }

  private static void countDirectChild(
      Map<NameIdentifier, Long> countByParent, @Nullable NameIdentifier parentIdent) {
    if (parentIdent != null) {
      countByParent.merge(parentIdent, 1L, Long::sum);
    }
  }

  private void addAggregateMetrics(
      List<MetricPO> metrics,
      Function<MetricDataService.Metric, String> metricNameBuilder,
      Set<AssetNode> assets,
      Set<String> failedDependencies,
      Set<String> successfulDependencies,
      Map<AssetNode, Boolean> taggedCache,
      Map<AssetNode, Boolean> policyCache) {
    if (failedDependencies.isEmpty()) {
      addCompleteMetrics(metrics, metricNameBuilder, assets, taggedCache, policyCache);
      return;
    }
    if (successfulDependencies.isEmpty()) {
      addUnavailableMetrics(metrics, metricNameBuilder);
      return;
    }

    AssetCounts counts = countAssets(assets, taggedCache, policyCache);
    addMetrics(metrics, metricNameBuilder, counts, MetricState.PARTIAL, PARTIAL_MESSAGE);
  }

  private void addCompleteMetrics(
      List<MetricPO> metrics,
      Function<MetricDataService.Metric, String> metricNameBuilder,
      Set<AssetNode> assets,
      Map<AssetNode, Boolean> taggedCache,
      Map<AssetNode, Boolean> policyCache) {
    addMetrics(
        metrics,
        metricNameBuilder,
        countAssets(assets, taggedCache, policyCache),
        MetricState.COMPLETE,
        null);
  }

  private void addUnavailableMetrics(
      List<MetricPO> metrics, Function<MetricDataService.Metric, String> metricNameBuilder) {
    for (MetricDataService.Metric metric : ASSET_METRICS) {
      metrics.add(
          createMetricPO(
              metricNameBuilder.apply(metric), null, MetricState.UNAVAILABLE, UNAVAILABLE_MESSAGE));
    }
  }

  private void addPolicyMetrics(List<MetricPO> metrics) {
    metrics.add(
        createMetricPO(
            MetricDataService.Metric.POLICY_COUNT.getName(),
            (double) metalakeSnapshot.getPolicyCount(),
            MetricState.COMPLETE,
            null));
    metrics.add(
        createMetricPO(
            MetricDataService.Metric.DISABLED_POLICY_COUNT.getName(),
            (double) metalakeSnapshot.getDisabledPolicyCount(),
            MetricState.COMPLETE,
            null));
  }

  private void addMetrics(
      List<MetricPO> metrics,
      Function<MetricDataService.Metric, String> metricNameBuilder,
      AssetCounts counts,
      MetricState state,
      @Nullable String message) {
    metrics.add(
        createMetricPO(
            metricNameBuilder.apply(MetricDataService.Metric.ASSET_COUNT),
            (double) counts.assetCount,
            state,
            message));
    metrics.add(
        createMetricPO(
            metricNameBuilder.apply(MetricDataService.Metric.TAGGED_ASSET_COUNT),
            (double) counts.taggedAssetCount,
            state,
            message));
    metrics.add(
        createMetricPO(
            metricNameBuilder.apply(MetricDataService.Metric.OWNED_ASSET_COUNT),
            (double) counts.ownedAssetCount,
            state,
            message));
    metrics.add(
        createMetricPO(
            metricNameBuilder.apply(MetricDataService.Metric.POLICY_COVERED_ASSET_COUNT),
            (double) counts.policyCoveredAssetCount,
            state,
            message));
  }

  private AssetCounts countAssets(
      Set<AssetNode> assets,
      Map<AssetNode, Boolean> taggedCache,
      Map<AssetNode, Boolean> policyCache) {
    long taggedAssetCount = assets.stream().filter(node -> isTagged(node, taggedCache)).count();
    long ownedAssetCount = assets.stream().filter(node -> !node.getOwners().isEmpty()).count();
    long policyCoveredAssetCount =
        assets.stream().filter(node -> isPolicyCovered(node, policyCache)).count();
    return new AssetCounts(
        assets.size(), taggedAssetCount, ownedAssetCount, policyCoveredAssetCount);
  }

  private boolean isTagged(AssetNode node, Map<AssetNode, Boolean> cache) {
    Boolean cached = cache.get(node);
    if (cached != null) {
      return cached;
    }
    boolean tagged =
        metalakeSnapshot.getTaggedObjectIds().contains(node.getId())
            || isParentMatching(node, parent -> isTagged(parent, cache));
    cache.put(node, tagged);
    return tagged;
  }

  private boolean isPolicyCovered(AssetNode node, Map<AssetNode, Boolean> cache) {
    Boolean cached = cache.get(node);
    if (cached != null) {
      return cached;
    }
    boolean covered =
        metalakeSnapshot.getEnabledPolicyObjectIds().contains(node.getId())
            || isParentMatching(node, parent -> isPolicyCovered(parent, cache));
    cache.put(node, covered);
    return covered;
  }

  private boolean isParentMatching(AssetNode node, ParentPredicate predicate) {
    NameIdentifier parentIdent = node.getParentIdent();
    if (parentIdent == null) {
      return false;
    }
    AssetNode parent = metalakeSnapshot.getAssetNodeByIdent().get(parentIdent);
    return parent != null && predicate.test(parent);
  }

  private boolean catalogSupports(String catalogName, MetadataObject.Type assetType) {
    Catalog.Type catalogType = metalakeSnapshot.getCatalogTypes().get(catalogName);
    if (catalogType == null) {
      return false;
    }
    if (assetType == MetadataObject.Type.FUNCTION) {
      return catalogType != Catalog.Type.UNSUPPORTED;
    }
    switch (catalogType) {
      case RELATIONAL:
        if (assetType == MetadataObject.Type.VIEW) {
          return metalakeSnapshot.getViewListingSupportByCatalog().getOrDefault(catalogName, true);
        }
        return assetType == MetadataObject.Type.TABLE;
      case FILESET:
        return assetType == MetadataObject.Type.FILESET;
      case MESSAGING:
        return assetType == MetadataObject.Type.TOPIC;
      case MODEL:
        return assetType == MetadataObject.Type.MODEL;
      default:
        return false;
    }
  }

  private static String catalogName(AssetNode node) {
    return node.getNameIdent().namespace().level(1);
  }

  private static MetricPO createMetricPO(
      String name, @Nullable Double value, MetricState state, @Nullable String message) {
    return MetricPO.builder()
        .withMetricName(name)
        .withMetricValue(value)
        .withMetricState(state)
        .withMetricMessage(message)
        .build();
  }

  private Set<AssetNode> getVisibleNodes(
      String metalakeName,
      Principal principal,
      GravitinoAuthorizer authorizer,
      String authorizationExpression,
      Entity.EntityType entityType,
      Set<AssetNode> nodes) {
    AssetNode[] visibleNodes =
        MetadataAuthzHelper.filterByExpression(
            metalakeName,
            authorizationExpression,
            entityType,
            nodes.toArray(new AssetNode[0]),
            AssetNode::getNameIdent,
            principal,
            authorizer);
    return Arrays.stream(visibleNodes).collect(Collectors.toSet());
  }

  private interface ParentPredicate {
    boolean test(AssetNode parent);
  }

  private static class AssetCounts {
    private final long assetCount;
    private final long taggedAssetCount;
    private final long ownedAssetCount;
    private final long policyCoveredAssetCount;

    private AssetCounts(
        long assetCount,
        long taggedAssetCount,
        long ownedAssetCount,
        long policyCoveredAssetCount) {
      this.assetCount = assetCount;
      this.taggedAssetCount = taggedAssetCount;
      this.ownedAssetCount = ownedAssetCount;
      this.policyCoveredAssetCount = policyCoveredAssetCount;
    }
  }
}
