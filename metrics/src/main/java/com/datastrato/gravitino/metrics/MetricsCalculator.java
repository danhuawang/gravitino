/*
 * Copyright 2024 Datastrato Inc.
 */
package com.datastrato.gravitino.metrics;

import com.datastrato.gravitino.metrics.dto.MetricState;
import com.datastrato.gravitino.metrics.storage.relational.MetricPO;
import com.datastrato.gravitino.metrics.storage.relational.service.MetricDataService;
import com.google.common.collect.ImmutableMap;
import java.io.IOException;
import java.security.Principal;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Comparator;
import java.util.EnumMap;
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
      Collections.unmodifiableList(
          MetricsAssetAuthorizationRule.rules().stream()
              .map(MetricsAssetAuthorizationRule::assetType)
              .collect(Collectors.toList()));
  private static final List<MetricDataService.Metric> ASSET_METRICS =
      Arrays.asList(
          MetricDataService.Metric.ASSET_COUNT,
          MetricDataService.Metric.TAGGED_ASSET_COUNT,
          MetricDataService.Metric.OWNED_ASSET_COUNT,
          MetricDataService.Metric.POLICY_COVERED_ASSET_COUNT);
  private static final Map<MetadataObject.Type, MetricDataService.Metric>
      CATALOG_OBJECT_COUNT_METRICS =
          ImmutableMap.<MetadataObject.Type, MetricDataService.Metric>builder()
              .put(MetadataObject.Type.TABLE, MetricDataService.Metric.TABLE_COUNT)
              .put(MetadataObject.Type.VIEW, MetricDataService.Metric.VIEW_COUNT)
              .put(MetadataObject.Type.FUNCTION, MetricDataService.Metric.FUNCTION_COUNT)
              .put(MetadataObject.Type.FILESET, MetricDataService.Metric.FILESET_COUNT)
              .put(MetadataObject.Type.TOPIC, MetricDataService.Metric.TOPIC_COUNT)
              .put(MetadataObject.Type.MODEL, MetricDataService.Metric.MODEL_COUNT)
              .build();

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
    return calculateMetricsForUser(username, authorizer, null);
  }

  List<MetricPO> calculateMetricsForVisibility(
      String username, MetricsVisibility visibility, @Nullable GravitinoAuthorizer authorizer) {
    return calculateMetricsForUser(username, authorizer, visibility);
  }

  private List<MetricPO> calculateMetricsForUser(
      String username,
      @Nullable GravitinoAuthorizer authorizer,
      @Nullable MetricsVisibility visibility) {
    if (authorizer != null && !metalakeSnapshot.getUserNameToUserId().containsKey(username)) {
      LOG.warn("User {} not found in snapshot, skipping metrics calculation", username);
      return Collections.emptyList();
    }

    MetricsVisibility resolvedVisibility =
        visibility != null ? visibility : resolveVisibility(username, authorizer);
    Set<AssetNode> visibleCatalogNodes = resolvedVisibility.catalogNodes();
    Set<AssetNode> directCountVisibleSchemaNodes =
        new HashSet<>(resolvedVisibility.schemaNodesForDirectCounts());
    Set<AssetNode> directCountVisibleAssetNodes =
        new HashSet<>(resolvedVisibility.assetNodesForDirectCounts());
    Set<AssetNode> visibleAssetNodes = visibleAssetNodes(resolvedVisibility);

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
    Map<MetadataObject.Type, Map<String, Long>> visibleCountsByType =
        new EnumMap<>(MetadataObject.Type.class);
    visibleCountsByType.put(
        MetadataObject.Type.SCHEMA, countByCatalog(directCountVisibleSchemaNodes));
    for (MetadataObject.Type assetType : ASSET_TYPES) {
      visibleCountsByType.put(assetType, countByCatalog(resolvedVisibility.assetNodes(assetType)));
    }
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
              boolean collectionFailed = failedVisibleCatalogNames.contains(catalogName);
              if (collectionFailed) {
                addUnavailableMetrics(metrics, metricNameBuilder);
              } else {
                Set<AssetNode> catalogAssets =
                    assetsByCatalog.getOrDefault(catalogName, Collections.emptySet());
                addCompleteMetrics(
                    metrics, metricNameBuilder, catalogAssets, taggedCache, policyCache);
              }

              addCatalogCountMetrics(metrics, catalogNode, collectionFailed, visibleCountsByType);
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

    addSchemaFailureDetails(metrics, visibleCatalogNames, username, authorizer);
    return Collections.unmodifiableList(metrics);
  }

  private MetricsVisibility resolveVisibility(
      String username, @Nullable GravitinoAuthorizer authorizer) {
    if (authorizer == null) {
      return fullVisibility();
    }
    return authorizationVisibility(new UserPrincipal(username), authorizer);
  }

  private MetricsVisibility authorizationVisibility(
      Principal principal, GravitinoAuthorizer authorizer) {
    Set<AssetNode> visibleCatalogs =
        getVisibleNodes(
            metalakeName,
            principal,
            authorizer,
            AuthorizationExpressionConstants.LOAD_CATALOG_AUTHORIZATION_EXPRESSION,
            Entity.EntityType.CATALOG,
            metalakeSnapshot.getCatalogNodes());
    Map<MetadataObject.Type, Set<AssetNode>> visibleAssetsByType =
        new EnumMap<>(MetadataObject.Type.class);
    Set<AssetNode> visibleAssetsForDirectCounts = new HashSet<>();
    for (MetricsAssetAuthorizationRule rule : MetricsAssetAuthorizationRule.rules()) {
      Set<AssetNode> assetNodes = rule.nodes(metalakeSnapshot);
      visibleAssetsByType.put(
          rule.assetType(),
          getVisibleNodes(
              metalakeName,
              principal,
              authorizer,
              rule.loadExpression(),
              rule.entityType(),
              assetNodes));
      visibleAssetsForDirectCounts.addAll(
          getVisibleNodes(
              metalakeName,
              principal,
              authorizer,
              rule.filterExpression(),
              rule.entityType(),
              assetNodes));
    }
    Set<AssetNode> visibleSchemasForDirectCounts =
        getVisibleNodes(
            metalakeName,
            principal,
            authorizer,
            AuthorizationExpressionConstants.FILTER_SCHEMA_AUTHORIZATION_EXPRESSION,
            Entity.EntityType.SCHEMA,
            metalakeSnapshot.getSchemaNodes());
    return new MetricsVisibility(
        visibleCatalogs,
        visibleAssetsByType,
        visibleSchemasForDirectCounts,
        visibleAssetsForDirectCounts);
  }

  private MetricsVisibility fullVisibility() {
    Map<MetadataObject.Type, Set<AssetNode>> assetsByType =
        new EnumMap<>(MetadataObject.Type.class);
    Set<AssetNode> allAssets = new HashSet<>();
    for (MetricsAssetAuthorizationRule rule : MetricsAssetAuthorizationRule.rules()) {
      Set<AssetNode> assetNodes = rule.nodes(metalakeSnapshot);
      assetsByType.put(rule.assetType(), assetNodes);
      allAssets.addAll(assetNodes);
    }
    return new MetricsVisibility(
        metalakeSnapshot.getCatalogNodes(),
        assetsByType,
        metalakeSnapshot.getSchemaNodes(),
        allAssets);
  }

  private void addSchemaFailureDetails(
      List<MetricPO> metrics,
      Set<String> visibleCatalogNames,
      String username,
      @Nullable GravitinoAuthorizer authorizer) {
    for (Map.Entry<String, NameIdentifier> failure :
        metalakeSnapshot.getFailedSchemaIdentifiers().entrySet()) {
      String catalogName = failure.getKey();
      if (!visibleCatalogNames.contains(catalogName)) {
        continue;
      }
      NameIdentifier schemaIdent = failure.getValue();
      if (authorizer != null
          && MetricsAuthorizationFilter.filterVisibleEntities(
                  metalakeName,
                  AuthorizationExpressionConstants.FILTER_SCHEMA_AUTHORIZATION_EXPRESSION,
                  Entity.EntityType.SCHEMA,
                  Collections.singleton(schemaIdent),
                  Function.identity(),
                  new UserPrincipal(username),
                  authorizer)
              .isEmpty()) {
        continue;
      }
      // Never expose connector exception messages: they may contain credentials or connection URLs.
      String message = "Could not collect schema " + schemaIdent + ". " + UNAVAILABLE_MESSAGE;
      if (message.length() > 1024) {
        message = message.substring(0, 1021) + "...";
      }
      String catalogPrefix =
          "by_catalog"
              + DashboardMetricNames.SEPARATOR
              + catalogName
              + DashboardMetricNames.SEPARATOR;
      String childCountName = DirectChildCountMetricNames.forCatalog(catalogName);
      for (int i = 0; i < metrics.size(); i++) {
        MetricPO metric = metrics.get(i);
        if (metric.getMetricState() != MetricState.COMPLETE
            && (metric.getMetricName().startsWith(catalogPrefix)
                || metric.getMetricName().equals(childCountName))) {
          metrics.set(
              i,
              createMetricPO(
                  metric.getMetricName(),
                  metric.getMetricValue(),
                  metric.getMetricState(),
                  message));
        }
      }
    }
  }

  private static Set<AssetNode> visibleAssetNodes(MetricsVisibility visibility) {
    Set<AssetNode> assets = new HashSet<>();
    for (MetadataObject.Type assetType : ASSET_TYPES) {
      assets.addAll(visibility.assetNodes(assetType));
    }
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

  private void addCatalogCountMetrics(
      List<MetricPO> metrics,
      AssetNode catalogNode,
      boolean collectionFailed,
      Map<MetadataObject.Type, Map<String, Long>> visibleCountsByType) {
    String catalogName = catalogNode.getName();

    addCatalogObjectCountMetric(
        metrics,
        catalogName,
        MetadataObject.Type.SCHEMA,
        MetricDataService.Metric.SCHEMA_COUNT,
        collectionFailed,
        visibleCountsByType);

    for (Map.Entry<MetadataObject.Type, MetricDataService.Metric> entry :
        CATALOG_OBJECT_COUNT_METRICS.entrySet()) {
      if (catalogSupports(catalogName, entry.getKey())) {
        addCatalogObjectCountMetric(
            metrics,
            catalogName,
            entry.getKey(),
            entry.getValue(),
            collectionFailed,
            visibleCountsByType);
      }
    }

    addCatalogCountMetric(
        metrics,
        catalogName,
        MetricDataService.Metric.ASSOCIATED_TAG_COUNT,
        metalakeSnapshot.getAssociatedTagCountsByCatalogId().getOrDefault(catalogNode.getId(), 0L),
        MetricState.COMPLETE,
        null);
    addCatalogCountMetric(
        metrics,
        catalogName,
        MetricDataService.Metric.ASSOCIATED_POLICY_COUNT,
        metalakeSnapshot
            .getAssociatedPolicyCountsByCatalogId()
            .getOrDefault(catalogNode.getId(), 0L),
        MetricState.COMPLETE,
        null);
  }

  private void addCatalogObjectCountMetric(
      List<MetricPO> metrics,
      String catalogName,
      MetadataObject.Type objectType,
      MetricDataService.Metric metric,
      boolean collectionFailed,
      Map<MetadataObject.Type, Map<String, Long>> visibleCountsByType) {
    if (collectionFailed) {
      addCatalogCountMetric(
          metrics, catalogName, metric, null, MetricState.UNAVAILABLE, UNAVAILABLE_MESSAGE);
      return;
    }

    long count =
        visibleCountsByType
            .getOrDefault(objectType, Collections.emptyMap())
            .getOrDefault(catalogName, 0L);
    addCatalogCountMetric(metrics, catalogName, metric, count, MetricState.COMPLETE, null);
  }

  private void addCatalogCountMetric(
      List<MetricPO> metrics,
      String catalogName,
      MetricDataService.Metric metric,
      @Nullable Long value,
      MetricState state,
      @Nullable String message) {
    metrics.add(
        createMetricPO(
            DashboardMetricNames.forCatalog(catalogName, metric.getName()),
            value == null ? null : value.doubleValue(),
            state,
            message));
  }

  private static void countDirectChild(
      Map<NameIdentifier, Long> countByParent, @Nullable NameIdentifier parentIdent) {
    if (parentIdent != null) {
      countByParent.merge(parentIdent, 1L, Long::sum);
    }
  }

  private static Map<String, Long> countByCatalog(Set<AssetNode> nodes) {
    return nodes.stream()
        .collect(Collectors.groupingBy(MetricsCalculator::catalogName, Collectors.counting()));
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
    // Ancestor ownership may grant visibility, but this metric counts only direct asset owners.
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
    return MetricsAuthorizationFilter.filterVisibleEntities(
        metalakeName,
        authorizationExpression,
        entityType,
        nodes,
        AssetNode::getNameIdent,
        principal,
        authorizer);
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
