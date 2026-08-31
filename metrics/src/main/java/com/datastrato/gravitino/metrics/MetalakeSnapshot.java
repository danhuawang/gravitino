/*
 * Copyright 2024 Datastrato Pvt Ltd.
 * This software is licensed under the Apache License version 2.
 */
package com.datastrato.gravitino.metrics;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import lombok.Builder;
import lombok.Getter;
import org.apache.gravitino.Catalog;
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

  // IDs of persisted metadata objects with at least one directly associated tag.
  private final Set<Long> taggedObjectIds;

  private final Map<String, Long> userNameToUserId;
  private final Map<Long, Set<Long>> userIdToRoleIds;
  private final Map<Long, List<SecurableObject>> roleIdToSecurableObjects;

  private final Set<AssetNode> catalogNodes;
  private final Set<AssetNode> schemaNodes;
  private final Set<AssetNode> tableNodes;
  private final Set<AssetNode> viewNodes;
  private final Set<AssetNode> functionNodes;
  private final Set<AssetNode> filesetNodes;
  private final Set<AssetNode> topicNodes;
  private final Set<AssetNode> modelNodes;
  private final Set<Long> enabledPolicyObjectIds;
  private final long policyCount;
  private final long disabledPolicyCount;
  private final Set<String> failedCatalogNames;
  private final Map<String, Catalog.Type> catalogTypes;
  private final Map<String, Boolean> viewListingSupportByCatalog;

  /**
   * Creates an immutable input snapshot for dashboard metric calculation.
   *
   * @param assetTreeRoot metalake root node
   * @param assetNodeById nodes indexed by persisted metadata object ID
   * @param userNameToUserId users indexed by name
   * @param roleIdToSecurableObjects securable objects grouped by role ID
   * @param userIdToRoleIds role IDs grouped by user ID
   * @param taggedObjectIds IDs with at least one direct tag
   * @param catalogNodes enabled catalog nodes
   * @param schemaNodes schema nodes from successfully collected catalogs
   * @param tableNodes table asset nodes
   * @param viewNodes view asset nodes
   * @param functionNodes function asset nodes
   * @param filesetNodes fileset asset nodes
   * @param topicNodes topic asset nodes
   * @param modelNodes model asset nodes
   * @param enabledPolicyObjectIds IDs with at least one direct enabled policy
   * @param policyCount number of current policies in the metalake
   * @param disabledPolicyCount number of current policies whose current version is disabled
   * @param failedCatalogNames enabled catalogs whose connector collection failed
   * @param catalogTypes enabled catalog types indexed by catalog name
   * @param viewListingSupportByCatalog known view-listing support indexed by catalog name; a
   *     missing entry means support could not be determined
   */
  @Builder
  public MetalakeSnapshot(
      AssetNode assetTreeRoot,
      Map<Long, AssetNode> assetNodeById,
      Map<String, Long> userNameToUserId,
      Map<Long, List<SecurableObject>> roleIdToSecurableObjects,
      Map<Long, Set<Long>> userIdToRoleIds,
      Set<Long> taggedObjectIds,
      Set<AssetNode> catalogNodes,
      Set<AssetNode> schemaNodes,
      Set<AssetNode> tableNodes,
      Set<AssetNode> viewNodes,
      Set<AssetNode> functionNodes,
      Set<AssetNode> filesetNodes,
      Set<AssetNode> topicNodes,
      Set<AssetNode> modelNodes,
      Set<Long> enabledPolicyObjectIds,
      long policyCount,
      long disabledPolicyCount,
      Set<String> failedCatalogNames,
      Map<String, Catalog.Type> catalogTypes,
      Map<String, Boolean> viewListingSupportByCatalog) {
    this.assetTreeRoot = assetTreeRoot;
    this.assetNodeById = assetNodeById;
    this.userNameToUserId = userNameToUserId;
    this.roleIdToSecurableObjects = roleIdToSecurableObjects;
    this.userIdToRoleIds = userIdToRoleIds;
    this.taggedObjectIds = taggedObjectIds;
    this.catalogNodes = catalogNodes;
    this.schemaNodes = schemaNodes;
    this.tableNodes = tableNodes;
    this.viewNodes = viewNodes;
    this.functionNodes = functionNodes;
    this.filesetNodes = filesetNodes;
    this.topicNodes = topicNodes;
    this.modelNodes = modelNodes;
    this.enabledPolicyObjectIds = enabledPolicyObjectIds;
    this.policyCount = policyCount;
    this.disabledPolicyCount = disabledPolicyCount;
    this.failedCatalogNames = failedCatalogNames;
    this.catalogTypes = catalogTypes;
    this.viewListingSupportByCatalog = viewListingSupportByCatalog;
    this.assetNodeByIdent = new HashMap<>();
    this.assetNodeByIdent.put(assetTreeRoot.getNameIdent(), assetTreeRoot);
    catalogNodes.forEach(node -> this.assetNodeByIdent.put(node.getNameIdent(), node));
    schemaNodes.forEach(node -> this.assetNodeByIdent.put(node.getNameIdent(), node));
    tableNodes.forEach(node -> this.assetNodeByIdent.put(node.getNameIdent(), node));
    viewNodes.forEach(node -> this.assetNodeByIdent.put(node.getNameIdent(), node));
    functionNodes.forEach(node -> this.assetNodeByIdent.put(node.getNameIdent(), node));
    filesetNodes.forEach(node -> this.assetNodeByIdent.put(node.getNameIdent(), node));
    topicNodes.forEach(node -> this.assetNodeByIdent.put(node.getNameIdent(), node));
    modelNodes.forEach(node -> this.assetNodeByIdent.put(node.getNameIdent(), node));
  }
}
