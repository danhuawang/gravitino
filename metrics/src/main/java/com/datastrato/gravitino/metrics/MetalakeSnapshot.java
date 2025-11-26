/*
 * Copyright 2024 Datastrato Pvt Ltd.
 * This software is licensed under the Apache License version 2.
 */
package com.datastrato.gravitino.metrics;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import lombok.Getter;
import org.apache.gravitino.NameIdentifier;
import org.apache.gravitino.authorization.SecurableObject;

/**
 * A data holder for all data needed to calculate metrics for a single Metalake. This object is
 * designed to be immutable after creation.
 */
@Getter
public class MetalakeSnapshot {

  // The root of the asset tree for the metalake.
  private final AssetNode assetTreeRoot;

  // Fast lookup map from asset ID to its node in the tree.
  private final Map<Long, AssetNode> assetNodeById;

  private final Map<NameIdentifier, AssetNode> assetNodeByIdent;

  // Map from asset identifier to a set of tag names directly associated with it.
  // we don't use id mapping here because some assets may not have been imported yet (meaning no id)
  private final Map<NameIdentifier, Set<String>> assetIdentToTagNames;
  private final long tagCount;

  private final Map<String, Long> userNameToUserId;
  private final Map<Long, Set<Long>> userIdToRoleIds;
  private final Map<Long, List<SecurableObject>> roleIdToSecurableObjects;

  private final Set<AssetNode> catalogNodes;
  private final Set<AssetNode> schemaNodes;
  private final Set<AssetNode> tableNodes;
  private final Set<AssetNode> filesetNodes;
  private final Set<AssetNode> topicNodes;
  private final Set<AssetNode> modelNodes;

  public MetalakeSnapshot(
      AssetNode assetTreeRoot,
      Map<Long, AssetNode> assetNodeById,
      Map<String, Long> userNameToUserId,
      Map<Long, List<SecurableObject>> roleIdToSecurableObjects,
      Map<Long, Set<Long>> userIdToRoleIds,
      long tagCount,
      Map<NameIdentifier, Set<String>> assetIdentToTagNames,
      Set<AssetNode> catalogNodes,
      Set<AssetNode> schemaNodes,
      Set<AssetNode> tableNodes,
      Set<AssetNode> filesetNodes,
      Set<AssetNode> topicNodes,
      Set<AssetNode> modelNodes) {
    this.assetTreeRoot = assetTreeRoot;
    this.assetNodeById = assetNodeById;
    this.userNameToUserId = userNameToUserId;
    this.roleIdToSecurableObjects = roleIdToSecurableObjects;
    this.userIdToRoleIds = userIdToRoleIds;
    this.tagCount = tagCount;
    this.assetIdentToTagNames = assetIdentToTagNames;
    this.assetNodeByIdent =
        assetNodeById.values().stream()
            .collect(HashMap::new, (m, v) -> m.put(v.getNameIdent(), v), HashMap::putAll);
    this.catalogNodes = catalogNodes;
    this.schemaNodes = schemaNodes;
    this.tableNodes = tableNodes;
    this.filesetNodes = filesetNodes;
    this.topicNodes = topicNodes;
    this.modelNodes = modelNodes;
  }
}
