/*
 * Copyright 2026 Datastrato Inc.
 */
package com.datastrato.gravitino.metrics;

import java.util.Collections;
import java.util.EnumMap;
import java.util.Map;
import java.util.Set;
import org.apache.gravitino.MetadataObject;

/** Precomputed authorization visibility for one dashboard-metrics access profile. */
final class MetricsVisibility {
  private final Set<AssetNode> catalogNodes;
  private final Map<MetadataObject.Type, Set<AssetNode>> assetNodesByType;
  private final Set<AssetNode> schemaNodesForDirectCounts;
  private final Set<AssetNode> assetNodesForDirectCounts;

  MetricsVisibility(
      Set<AssetNode> catalogNodes,
      Map<MetadataObject.Type, Set<AssetNode>> assetNodesByType,
      Set<AssetNode> schemaNodesForDirectCounts,
      Set<AssetNode> assetNodesForDirectCounts) {
    this.catalogNodes = Collections.unmodifiableSet(catalogNodes);
    EnumMap<MetadataObject.Type, Set<AssetNode>> immutableNodesByType =
        new EnumMap<>(MetadataObject.Type.class);
    assetNodesByType.forEach(
        (type, nodes) -> immutableNodesByType.put(type, Collections.unmodifiableSet(nodes)));
    this.assetNodesByType = Collections.unmodifiableMap(immutableNodesByType);
    this.schemaNodesForDirectCounts = Collections.unmodifiableSet(schemaNodesForDirectCounts);
    this.assetNodesForDirectCounts = Collections.unmodifiableSet(assetNodesForDirectCounts);
  }

  Set<AssetNode> catalogNodes() {
    return catalogNodes;
  }

  Set<AssetNode> assetNodes(MetadataObject.Type type) {
    return assetNodesByType.getOrDefault(type, Collections.emptySet());
  }

  Set<AssetNode> schemaNodesForDirectCounts() {
    return schemaNodesForDirectCounts;
  }

  Set<AssetNode> assetNodesForDirectCounts() {
    return assetNodesForDirectCounts;
  }
}
