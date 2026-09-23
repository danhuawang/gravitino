/*
 * Copyright 2026 Datastrato Inc.
 */
package com.datastrato.gravitino.metrics;

import java.util.ArrayList;
import java.util.BitSet;
import java.util.Collections;
import java.util.Comparator;
import java.util.EnumMap;
import java.util.EnumSet;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import org.apache.gravitino.MetadataObject;
import org.apache.gravitino.NameIdentifier;
import org.apache.gravitino.authorization.Owner;
import org.apache.gravitino.authorization.Privilege;
import org.apache.gravitino.authorization.SecurableObject;
import org.apache.gravitino.utils.MetadataObjectUtil;
import org.apache.gravitino.utils.NameIdentifierUtil;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/** Projects snapshot role policies and direct user ownership into reusable visibility bit sets. */
final class MetricsAuthorizationIndex {
  private static final Logger LOG = LoggerFactory.getLogger(MetricsAuthorizationIndex.class);
  private static final Set<MetadataObject.Type> CATALOG_SCOPES =
      EnumSet.of(MetadataObject.Type.METALAKE, MetadataObject.Type.CATALOG);
  private static final Set<MetadataObject.Type> SCHEMA_SCOPES =
      EnumSet.of(
          MetadataObject.Type.METALAKE, MetadataObject.Type.CATALOG, MetadataObject.Type.SCHEMA);

  private final MetalakeSnapshot snapshot;
  private final String metalakeName;
  private final List<AssetNode> nodes;
  private final Map<AssetNode, Integer> ordinals;
  private final Map<AssetNode, BitSet> descendantsByNode;
  private final Map<MetadataObject.Type, BitSet> nodesByType;
  private final BitSet authorizableNodes;
  private final Map<Long, RolePermissions> permissionsByRole;
  private final Map<String, Set<Long>> ownedObjectIdsByUser;
  private int missingRolePolicyObjects;
  private int invalidRolePolicyObjects;

  MetricsAuthorizationIndex(MetalakeSnapshot snapshot) {
    this.snapshot = snapshot;
    this.metalakeName = snapshot.getAssetTreeRoot().getName();
    this.nodes = indexNodes(snapshot);
    this.ordinals = new HashMap<>();
    this.nodesByType = new EnumMap<>(MetadataObject.Type.class);
    for (int index = 0; index < nodes.size(); index++) {
      AssetNode node = nodes.get(index);
      ordinals.put(node, index);
      nodesByType.computeIfAbsent(node.getType(), ignored -> new BitSet()).set(index);
    }
    this.descendantsByNode = new HashMap<>();
    buildDescendantMask(snapshot.getAssetTreeRoot(), new HashSet<>());
    for (AssetNode node : nodes) {
      descendantsByNode.computeIfAbsent(node, this::singletonMask);
    }
    this.authorizableNodes = buildAuthorizableNodes();
    this.permissionsByRole = buildRolePermissions();
    if (missingRolePolicyObjects > 0 || invalidRolePolicyObjects > 0) {
      LOG.warn(
          "Dashboard metric authorization index skipped role policy objects: metalake={}, "
              + "missingObjects={}, invalidObjects={}",
          metalakeName,
          missingRolePolicyObjects,
          invalidRolePolicyObjects);
    }
    this.ownedObjectIdsByUser = buildOwnedObjectIdsByUser();
  }

  AccessProfile profile(String username, long userId) {
    List<Long> roleIds =
        new ArrayList<>(snapshot.getUserIdToRoleIds().getOrDefault(userId, Collections.emptySet()));
    roleIds.sort(Comparator.naturalOrder());
    List<Long> ownedObjectIds =
        new ArrayList<>(ownedObjectIdsByUser.getOrDefault(username, Collections.emptySet()));
    ownedObjectIds.sort(Comparator.naturalOrder());
    return new AccessProfile(roleIds, ownedObjectIds);
  }

  MetricsVisibility visibility(AccessProfile profile) {
    BitSet useCatalogCoverage =
        effectivePrivilegeCoverage(profile, Privilege.Name.USE_CATALOG, CATALOG_SCOPES);
    BitSet useSchemaCoverage =
        effectivePrivilegeCoverage(profile, Privilege.Name.USE_SCHEMA, SCHEMA_SCOPES);
    BitSet metalakeOrCatalogOwnership = ownershipCoverage(profile, CATALOG_SCOPES);
    BitSet schemaOwnership = ownershipCoverage(profile, EnumSet.of(MetadataObject.Type.SCHEMA));

    BitSet visibleCatalogs = union(useCatalogCoverage, metalakeOrCatalogOwnership);
    visibleCatalogs.and(typeMask(MetadataObject.Type.CATALOG));

    Map<MetadataObject.Type, Set<AssetNode>> visibleAssetsByType =
        new EnumMap<>(MetadataObject.Type.class);
    BitSet visibleAssetsForDirectCounts = new BitSet(nodes.size());
    for (MetricsAssetAuthorizationRule rule : MetricsAssetAuthorizationRule.rules()) {
      BitSet visibleForLoad =
          loadExpressionVisibility(
              profile,
              rule,
              useCatalogCoverage,
              useSchemaCoverage,
              metalakeOrCatalogOwnership,
              schemaOwnership);
      visibleAssetsByType.put(rule.assetType(), toNodes(visibleForLoad));
      visibleAssetsForDirectCounts.or(filterExpressionVisibility(profile, rule));
    }

    BitSet visibleSchemasForDirectCounts =
        union(ownershipCoverage(profile, SCHEMA_SCOPES), useSchemaCoverage);
    visibleSchemasForDirectCounts.and(typeMask(MetadataObject.Type.SCHEMA));

    return new MetricsVisibility(
        toNodes(visibleCatalogs),
        visibleAssetsByType,
        toNodes(visibleSchemasForDirectCounts),
        toNodes(visibleAssetsForDirectCounts));
  }

  private BitSet loadExpressionVisibility(
      AccessProfile profile,
      MetricsAssetAuthorizationRule rule,
      BitSet useCatalogCoverage,
      BitSet useSchemaCoverage,
      BitSet metalakeOrCatalogOwnership,
      BitSet schemaOwnership) {
    // Every LOAD_* expression has the same three branches: ancestor ownership, schema ownership
    // with catalog navigation, or catalog + schema navigation with object-level access.
    BitSet visible = copy(metalakeOrCatalogOwnership);
    BitSet visibleAsSchemaOwner = copy(schemaOwnership);
    visibleAsSchemaOwner.and(useCatalogCoverage);
    visible.or(visibleAsSchemaOwner);

    Set<MetadataObject.Type> scopes = metadataScopes(rule.assetType());
    BitSet objectAccess = ownershipCoverage(profile, EnumSet.of(rule.assetType()));
    for (Privilege.Name privilege : rule.privileges()) {
      objectAccess.or(effectivePrivilegeCoverage(profile, privilege, scopes));
    }
    BitSet visibleWithPrivileges = copy(useCatalogCoverage);
    visibleWithPrivileges.and(useSchemaCoverage);
    visibleWithPrivileges.and(objectAccess);
    visible.or(visibleWithPrivileges);
    visible.and(typeMask(rule.assetType()));
    return visible;
  }

  private BitSet filterExpressionVisibility(
      AccessProfile profile, MetricsAssetAuthorizationRule rule) {
    // FILTER_* expressions allow ownership or a matching privilege at any applicable scope.
    Set<MetadataObject.Type> scopes = metadataScopes(rule.assetType());
    BitSet visible = ownershipCoverage(profile, scopes);
    for (Privilege.Name privilege : rule.privileges()) {
      visible.or(effectivePrivilegeCoverage(profile, privilege, scopes));
    }
    visible.and(typeMask(rule.assetType()));
    return visible;
  }

  private BitSet effectivePrivilegeCoverage(
      AccessProfile profile, Privilege.Name privilege, Set<MetadataObject.Type> sourceTypes) {
    BitSet allowed = new BitSet(nodes.size());
    BitSet denied = new BitSet(nodes.size());
    for (Long roleId : profile.roleIds) {
      RolePermissions permissions = permissionsByRole.get(roleId);
      if (permissions != null) {
        permissions.orInto(privilege, sourceTypes, allowed, denied);
      }
    }
    allowed.andNot(denied);
    return allowed;
  }

  /**
   * Expands directly owned objects to descendant assets only to model the parent-scope owner
   * branches in load and filter authorization expressions.
   *
   * <p>This grants visibility through ancestor ownership; it does not assign ownership to
   * descendants. Ownership metrics still inspect each asset's direct owners.
   */
  private BitSet ownershipCoverage(AccessProfile profile, Set<MetadataObject.Type> sourceTypes) {
    BitSet owners = new BitSet(nodes.size());
    for (Long objectId : profile.ownedObjectIds) {
      AssetNode node = snapshot.getAssetNodeById().get(objectId);
      if (node != null && sourceTypes.contains(node.getType())) {
        owners.or(descendantsByNode.get(node));
      }
    }
    return owners;
  }

  private Map<Long, RolePermissions> buildRolePermissions() {
    Map<Long, RolePermissions> result = new HashMap<>();
    snapshot
        .getRoleIdToSecurableObjects()
        .forEach(
            (roleId, securableObjects) ->
                result.put(roleId, buildRolePermissions(roleId, securableObjects)));
    return result;
  }

  private RolePermissions buildRolePermissions(
      long roleId, List<SecurableObject> securableObjects) {
    RolePermissions permissions = new RolePermissions();
    for (SecurableObject securableObject : securableObjects) {
      NameIdentifier identifier;
      try {
        identifier = MetadataObjectUtil.toEntityIdent(metalakeName, securableObject);
      } catch (IllegalArgumentException e) {
        invalidRolePolicyObjects++;
        LOG.debug(
            "Could not index dashboard metric visibility policy for role {} and object {}",
            roleId,
            securableObject.fullName(),
            e);
        continue;
      }
      AssetNode node = snapshot.getAssetNodeByIdent().get(identifier);
      if (node == null || node.getId() == -1L) {
        missingRolePolicyObjects++;
        LOG.debug(
            "Skipping dashboard metric visibility policy for missing object {}",
            securableObject.fullName());
        continue;
      }
      BitSet coverage = descendantsByNode.get(node);
      for (Privilege privilege : securableObject.privileges()) {
        permissions.add(privilege.name(), node.getType(), privilege.condition(), coverage);
      }
    }
    return permissions;
  }

  private Map<String, Set<Long>> buildOwnedObjectIdsByUser() {
    Map<String, Set<Long>> result = new HashMap<>();
    for (AssetNode node : nodes) {
      if (node.getId() == -1L) {
        continue;
      }
      for (Owner owner : node.getOwners()) {
        if (owner.type() == Owner.Type.USER
            && snapshot.getUserNameToUserId().containsKey(owner.name())) {
          result.computeIfAbsent(owner.name(), ignored -> new HashSet<>()).add(node.getId());
        }
      }
    }
    return result;
  }

  private BitSet buildDescendantMask(AssetNode node, Set<AssetNode> visiting) {
    BitSet existing = descendantsByNode.get(node);
    if (existing != null) {
      return existing;
    }
    if (!visiting.add(node)) {
      LOG.warn("Cycle detected in dashboard metric asset tree at {}", node.getNameIdent());
      return singletonMask(node);
    }
    BitSet descendants = singletonMask(node);
    for (AssetNode child : node.getChildren()) {
      descendants.or(buildDescendantMask(child, visiting));
    }
    visiting.remove(node);
    descendantsByNode.put(node, descendants);
    return descendants;
  }

  private BitSet singletonMask(AssetNode node) {
    BitSet mask = new BitSet(nodes.size());
    Integer ordinal = ordinals.get(node);
    if (ordinal != null) {
      mask.set(ordinal);
    }
    return mask;
  }

  private BitSet typeMask(MetadataObject.Type type) {
    return copy(nodesByType.getOrDefault(type, new BitSet()));
  }

  private Set<AssetNode> toNodes(BitSet visible) {
    visible = copy(visible);
    visible.and(authorizableNodes);
    Set<AssetNode> result = new HashSet<>();
    for (int index = visible.nextSetBit(0); index >= 0; index = visible.nextSetBit(index + 1)) {
      result.add(nodes.get(index));
    }
    return result;
  }

  private BitSet buildAuthorizableNodes() {
    BitSet result = new BitSet(nodes.size());
    for (int index = 0; index < nodes.size(); index++) {
      AssetNode node = nodes.get(index);
      try {
        NameIdentifierUtil.splitNameIdentifier(
                metalakeName, MetadataObjectUtil.toEntityType(node.getType()), node.getNameIdent())
            .forEach(
                (entityType, identifier) ->
                    NameIdentifierUtil.toMetadataObject(identifier, entityType));
        result.set(index);
      } catch (RuntimeException e) {
        LOG.debug(
            "Skipping dashboard metric authorization for unsupported identifier {}",
            node.getNameIdent(),
            e);
      }
    }
    return result;
  }

  private static List<AssetNode> indexNodes(MetalakeSnapshot snapshot) {
    Set<AssetNode> indexed = new LinkedHashSet<>();
    collectNodes(snapshot.getAssetTreeRoot(), indexed);
    indexed.addAll(snapshot.getCatalogNodes());
    indexed.addAll(snapshot.getSchemaNodes());
    indexed.addAll(snapshot.getTableNodes());
    indexed.addAll(snapshot.getViewNodes());
    indexed.addAll(snapshot.getFunctionNodes());
    indexed.addAll(snapshot.getFilesetNodes());
    indexed.addAll(snapshot.getTopicNodes());
    indexed.addAll(snapshot.getModelNodes());
    return new ArrayList<>(indexed);
  }

  private static void collectNodes(AssetNode node, Set<AssetNode> indexed) {
    if (!indexed.add(node)) {
      return;
    }
    node.getChildren().forEach(child -> collectNodes(child, indexed));
  }

  private static Set<MetadataObject.Type> metadataScopes(MetadataObject.Type targetType) {
    return EnumSet.of(
        MetadataObject.Type.METALAKE,
        MetadataObject.Type.CATALOG,
        MetadataObject.Type.SCHEMA,
        targetType);
  }

  private static BitSet union(BitSet first, BitSet second) {
    BitSet result = copy(first);
    result.or(second);
    return result;
  }

  private static BitSet copy(BitSet source) {
    return (BitSet) source.clone();
  }

  /** Role IDs and direct user-owned object IDs that completely determine metric visibility. */
  static final class AccessProfile {
    private final List<Long> roleIds;
    private final List<Long> ownedObjectIds;

    private AccessProfile(List<Long> roleIds, List<Long> ownedObjectIds) {
      this.roleIds = Collections.unmodifiableList(new ArrayList<>(roleIds));
      this.ownedObjectIds = Collections.unmodifiableList(new ArrayList<>(ownedObjectIds));
    }

    @Override
    public boolean equals(Object other) {
      if (this == other) {
        return true;
      }
      if (!(other instanceof AccessProfile)) {
        return false;
      }
      AccessProfile that = (AccessProfile) other;
      return roleIds.equals(that.roleIds) && ownedObjectIds.equals(that.ownedObjectIds);
    }

    @Override
    public int hashCode() {
      return Objects.hash(roleIds, ownedObjectIds);
    }
  }

  private static final class RolePermissions {
    private final Map<Privilege.Name, Map<MetadataObject.Type, BitSet>> allowed =
        new EnumMap<>(Privilege.Name.class);
    private final Map<Privilege.Name, Map<MetadataObject.Type, BitSet>> denied =
        new EnumMap<>(Privilege.Name.class);

    private void add(
        Privilege.Name privilege,
        MetadataObject.Type sourceType,
        Privilege.Condition condition,
        BitSet coverage) {
      Map<Privilege.Name, Map<MetadataObject.Type, BitSet>> target =
          condition == Privilege.Condition.DENY ? denied : allowed;
      target
          .computeIfAbsent(privilege, ignored -> new EnumMap<>(MetadataObject.Type.class))
          .computeIfAbsent(sourceType, ignored -> new BitSet())
          .or(coverage);
    }

    private void orInto(
        Privilege.Name privilege,
        Set<MetadataObject.Type> sourceTypes,
        BitSet allowedTarget,
        BitSet deniedTarget) {
      orInto(allowed.get(privilege), sourceTypes, allowedTarget);
      orInto(denied.get(privilege), sourceTypes, deniedTarget);
    }

    private static void orInto(
        Map<MetadataObject.Type, BitSet> source,
        Set<MetadataObject.Type> sourceTypes,
        BitSet target) {
      if (source == null) {
        return;
      }
      for (MetadataObject.Type sourceType : sourceTypes) {
        BitSet coverage = source.get(sourceType);
        if (coverage != null) {
          target.or(coverage);
        }
      }
    }
  }
}
