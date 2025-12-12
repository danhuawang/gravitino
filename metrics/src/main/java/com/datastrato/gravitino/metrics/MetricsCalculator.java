/*
 * Copyright 2024 Datastrato Pvt Ltd.
 * This software is licensed under the Apache License version 2.
 */
package com.datastrato.gravitino.metrics;

import com.datastrato.gravitino.metrics.storage.relational.MetricPO;
import com.datastrato.gravitino.metrics.storage.relational.service.MetricDataService;
import com.google.common.collect.Maps;
import com.sun.security.auth.UserPrincipal;
import java.io.IOException;
import java.security.Principal;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.EnumMap;
import java.util.EnumSet;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;
import org.apache.gravitino.Entity;
import org.apache.gravitino.NameIdentifier;
import org.apache.gravitino.authorization.GravitinoAuthorizer;
import org.apache.gravitino.server.authorization.MetadataAuthzHelper;
import org.apache.gravitino.server.authorization.expression.AuthorizationExpressionConstants;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class MetricsCalculator {
  private static final Logger LOG = LoggerFactory.getLogger(MetricsCalculator.class);

  private final MetalakeSnapshot metalakeSnapshot;
  private final String metalakeName;
  private final EnumMap<MetricsCollector.TagCategory, Set<String>> categoryToTagNames;

  public MetricsCalculator(
      MetalakeSnapshot metalakeSnapshot,
      EnumMap<MetricsCollector.TagCategory, Set<String>> categoryToTagNames) {
    this.metalakeSnapshot = metalakeSnapshot;
    this.metalakeName = metalakeSnapshot.getAssetTreeRoot().getName();
    this.categoryToTagNames = categoryToTagNames;
  }

  public List<MetricPO> calculateMetricsForDisableAuthz() {
    return calculateMetricsForUser("", false, false);
  }

  public List<MetricPO> calculateMetricsForUser(
      String username, boolean enableAuthz, boolean forMetalakeOwner) {
    if (enableAuthz && !metalakeSnapshot.getUserNameToUserId().containsKey(username)) {
      LOG.warn("User {} not found in snapshot, skipping metrics calculation", username);
      return Collections.emptyList();
    }

    Set<AssetNode> visibleCatalogNodes;
    Set<AssetNode> visibleSchemaNodes;
    Set<AssetNode> visibleTableNodes;
    Set<AssetNode> visibleFilesetNodes;
    Set<AssetNode> visibleTopicNodes;
    Set<AssetNode> visibleModelNodes;
    if (enableAuthz) {
      Principal principal = new UserPrincipal(username);
      GravitinoAuthorizer authorizer = new MemoizedJcasbinAuthorizer();
      authorizer.initialize();
      visibleCatalogNodes = getVisibleCatalogNodes(metalakeName, principal, authorizer);
      visibleSchemaNodes = getVisibleSchemaNodes(metalakeName, principal, authorizer);
      visibleTableNodes = getVisibleTableNodes(metalakeName, principal, authorizer);
      visibleFilesetNodes = getVisibleFilesetNodes(metalakeName, principal, authorizer);
      visibleTopicNodes = getVisibleTopicNodes(metalakeName, principal, authorizer);
      visibleModelNodes = getVisibleModelNodes(metalakeName, principal, authorizer);
      try {
        authorizer.close();
      } catch (IOException e) {
        LOG.warn("Error closing authorizer", e);
      }
    } else {
      // if authz is disabled, all assets are visible
      visibleCatalogNodes = new HashSet<>(metalakeSnapshot.getCatalogNodes());
      visibleSchemaNodes = new HashSet<>(metalakeSnapshot.getSchemaNodes());
      visibleTableNodes = new HashSet<>(metalakeSnapshot.getTableNodes());
      visibleFilesetNodes = new HashSet<>(metalakeSnapshot.getFilesetNodes());
      visibleTopicNodes = new HashSet<>(metalakeSnapshot.getTopicNodes());
      visibleModelNodes = new HashSet<>(metalakeSnapshot.getModelNodes());
    }

    Set<AssetNode> visibleAssetNodes = new HashSet<>(visibleCatalogNodes);
    visibleAssetNodes.addAll(visibleSchemaNodes);
    visibleAssetNodes.addAll(visibleTableNodes);
    visibleAssetNodes.addAll(visibleFilesetNodes);
    visibleAssetNodes.addAll(visibleTopicNodes);
    visibleAssetNodes.addAll(visibleModelNodes);

    // calculate tagged asset counts
    EnumMap<MetricsCollector.TagCategory, Long> taggedCounts =
        Maps.newEnumMap(MetricsCollector.TagCategory.class);
    // Initialize all possible tag category counts to 0
    for (MetricsCollector.TagCategory category : MetricsCollector.TagCategory.values()) {
      taggedCounts.put(category, 0L);
    }

    Map<AssetNode, EnumSet<MetricsCollector.TagCategory>> categoriesCache = new HashMap<>();
    for (AssetNode assetNode : visibleAssetNodes) {
      EnumSet<MetricsCollector.TagCategory> categories =
          getInheritedTagCategories(
              assetNode, metalakeSnapshot, categoriesCache, categoryToTagNames);

      categories.forEach(c -> taggedCounts.put(c, taggedCounts.getOrDefault(c, 0L) + 1));
    }

    // prepare metrics
    List<MetricPO> metrics = new ArrayList<>();
    metrics.add(createMetricPO(MetricDataService.Metric.CATALOG_COUNT, visibleCatalogNodes.size()));
    metrics.add(createMetricPO(MetricDataService.Metric.SCHEMA_COUNT, visibleSchemaNodes.size()));
    metrics.add(createMetricPO(MetricDataService.Metric.TABLE_COUNT, visibleTableNodes.size()));
    metrics.add(createMetricPO(MetricDataService.Metric.FILESET_COUNT, visibleFilesetNodes.size()));
    metrics.add(createMetricPO(MetricDataService.Metric.TOPIC_COUNT, visibleTopicNodes.size()));
    metrics.add(createMetricPO(MetricDataService.Metric.MODEL_COUNT, visibleModelNodes.size()));
    metrics.add(createMetricPO(MetricDataService.Metric.ASSET_COUNT, visibleAssetNodes.size()));
    metrics.add(createMetricPO(MetricDataService.Metric.TAG_COUNT, metalakeSnapshot.getTagCount()));
    taggedCounts.forEach(
        (c, count) ->
            metrics.add(createMetricPO(MetricDataService.Metric.fromName(c.metricName()), count)));
    Long taggedCount = taggedCounts.get(MetricsCollector.TagCategory.ANY_TAG);
    metrics.add(
        createMetricPO(
            MetricDataService.Metric.UNTAGGED_ASSET_COUNT, visibleAssetNodes.size() - taggedCount));
    if (forMetalakeOwner) {
      long ownedAssetCount =
          visibleAssetNodes.stream().filter(n -> !n.getOwners().isEmpty()).count();
      metrics.add(createMetricPO(MetricDataService.Metric.OWNED_ASSET_COUNT, ownedAssetCount));
    }
    return Collections.unmodifiableList(metrics);
  }

  private MetricPO createMetricPO(MetricDataService.Metric metric, double value) {
    return MetricPO.builder().withMetricName(metric.getName()).withMetricValue(value).build();
  }

  /**
   * A recursive function to get all tag categories that an asset node belongs to, including
   * inherited tags from its parents.
   *
   * @param node The asset node to check.
   * @param metalakeSnapshot The snapshot object containing all preloaded data.
   * @param cache A cache to store the computed results (Node -> EnumSet of TagCategory) to avoid
   *     redundant calculations.
   * @return An EnumSet containing all applicable tag categories.
   */
  private EnumSet<MetricsCollector.TagCategory> getInheritedTagCategories(
      AssetNode node,
      MetalakeSnapshot metalakeSnapshot,
      Map<AssetNode, EnumSet<MetricsCollector.TagCategory>> cache,
      EnumMap<MetricsCollector.TagCategory, Set<String>> categoryToTagNames) {

    if (cache.containsKey(node)) {
      return cache.get(node);
    }

    // 1. Recursively retrieve the classification results of parent nodes
    EnumSet<MetricsCollector.TagCategory> inheritedCategories;
    NameIdentifier parentIdent = node.getParentIdent();
    if (parentIdent != null) {
      AssetNode parentNode = metalakeSnapshot.getAssetNodeByIdent().get(parentIdent);
      if (parentNode != null) {
        // Copy the parent node's classification, to avoid modifying the collection in the cache
        inheritedCategories =
            EnumSet.copyOf(
                getInheritedTagCategories(parentNode, metalakeSnapshot, cache, categoryToTagNames));
      } else {
        inheritedCategories = EnumSet.noneOf(MetricsCollector.TagCategory.class);
      }
    } else {
      inheritedCategories = EnumSet.noneOf(MetricsCollector.TagCategory.class);
    }

    // 2. Check the direct tags of the current node and add them to the classification.
    Set<String> directTags = metalakeSnapshot.getAssetIdentToTagNames().get(node.getNameIdent());
    if (directTags != null && !directTags.isEmpty()) {
      inheritedCategories.add(MetricsCollector.TagCategory.ANY_TAG);
      categoryToTagNames.forEach(
          (c, t) -> {
            if (!Collections.disjoint(directTags, t)) {
              inheritedCategories.add(c);
            }
          });
    }

    cache.put(node, inheritedCategories);
    return inheritedCategories;
  }

  private Set<AssetNode> getVisibleCatalogNodes(
      String metalakeName, Principal principal, GravitinoAuthorizer authorizer) {
    AssetNode[] visibleCatalogNodes =
        MetadataAuthzHelper.filterByExpression(
            metalakeName,
            AuthorizationExpressionConstants.loadCatalogAuthorizationExpression,
            Entity.EntityType.CATALOG,
            metalakeSnapshot.getCatalogNodes().toArray(new AssetNode[0]),
            AssetNode::getNameIdent,
            principal,
            authorizer);
    return Arrays.stream(visibleCatalogNodes).collect(Collectors.toSet());
  }

  private Set<AssetNode> getVisibleSchemaNodes(
      String metalakeName, Principal principal, GravitinoAuthorizer authorizer) {
    AssetNode[] visibleSchemaNodes =
        MetadataAuthzHelper.filterByExpression(
            metalakeName,
            AuthorizationExpressionConstants.loadSchemaAuthorizationExpression,
            Entity.EntityType.SCHEMA,
            metalakeSnapshot.getSchemaNodes().toArray(new AssetNode[0]),
            AssetNode::getNameIdent,
            principal,
            authorizer);
    return Arrays.stream(visibleSchemaNodes).collect(Collectors.toSet());
  }

  private Set<AssetNode> getVisibleTableNodes(
      String metalakeName, Principal principal, GravitinoAuthorizer authorizer) {
    AssetNode[] visibleTableNodes =
        MetadataAuthzHelper.filterByExpression(
            metalakeName,
            AuthorizationExpressionConstants.loadTableAuthorizationExpression,
            Entity.EntityType.TABLE,
            metalakeSnapshot.getTableNodes().toArray(new AssetNode[0]),
            AssetNode::getNameIdent,
            principal,
            authorizer);
    return Arrays.stream(visibleTableNodes).collect(Collectors.toSet());
  }

  private Set<AssetNode> getVisibleFilesetNodes(
      String metalakeName, Principal principal, GravitinoAuthorizer authorizer) {
    AssetNode[] visibleFilesetNodes =
        MetadataAuthzHelper.filterByExpression(
            metalakeName,
            AuthorizationExpressionConstants.loadFilesetAuthorizationExpression,
            Entity.EntityType.FILESET,
            metalakeSnapshot.getFilesetNodes().toArray(new AssetNode[0]),
            AssetNode::getNameIdent,
            principal,
            authorizer);
    return Arrays.stream(visibleFilesetNodes).collect(Collectors.toSet());
  }

  private Set<AssetNode> getVisibleTopicNodes(
      String metalakeName, Principal principal, GravitinoAuthorizer authorizer) {
    AssetNode[] visibleTopicNodes =
        MetadataAuthzHelper.filterByExpression(
            metalakeName,
            AuthorizationExpressionConstants.loadTopicsAuthorizationExpression,
            Entity.EntityType.TOPIC,
            metalakeSnapshot.getTopicNodes().toArray(new AssetNode[0]),
            AssetNode::getNameIdent,
            principal,
            authorizer);
    return Arrays.stream(visibleTopicNodes).collect(Collectors.toSet());
  }

  private Set<AssetNode> getVisibleModelNodes(
      String metalakeName, Principal principal, GravitinoAuthorizer authorizer) {
    AssetNode[] visibleModelNodes =
        MetadataAuthzHelper.filterByExpression(
            metalakeName,
            AuthorizationExpressionConstants.loadModelAuthorizationExpression,
            Entity.EntityType.MODEL,
            metalakeSnapshot.getModelNodes().toArray(new AssetNode[0]),
            AssetNode::getNameIdent,
            principal,
            authorizer);
    return Arrays.stream(visibleModelNodes).collect(Collectors.toSet());
  }
}
