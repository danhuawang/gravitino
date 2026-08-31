/*
 * Copyright 2026 Datastrato Inc.
 */
package org.apache.gravitino.encryption;

import com.google.common.base.Preconditions;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import org.apache.gravitino.Entity;
import org.apache.gravitino.MetadataObject;
import org.apache.gravitino.NameIdentifier;
import org.apache.gravitino.exceptions.NoSuchMetadataObjectException;
import org.apache.gravitino.meta.PolicyEntity;
import org.apache.gravitino.policy.Policy;
import org.apache.gravitino.policy.PolicyDispatcher;
import org.apache.gravitino.utils.MetadataObjectUtil;
import org.apache.gravitino.utils.NameIdentifierUtil;

/**
 * Resolves the enabled Iceberg encryption policy associated with a candidate table, including
 * policies inherited from catalog and schema ancestors.
 */
public final class IcebergEncryptionPolicyResolver {

  private final PolicyDispatcher policyDispatcher;
  private final SinglePolicyChecker singlePolicyChecker;

  /**
   * Creates an Iceberg encryption policy resolver.
   *
   * @param policyDispatcher dispatcher used to list policies associated with metadata objects
   * @param singlePolicyChecker checker for the prototype's single-policy invariant
   */
  public IcebergEncryptionPolicyResolver(
      PolicyDispatcher policyDispatcher, SinglePolicyChecker singlePolicyChecker) {
    Preconditions.checkArgument(policyDispatcher != null, "policyDispatcher cannot be null");
    Preconditions.checkArgument(singlePolicyChecker != null, "singlePolicyChecker cannot be null");

    this.policyDispatcher = policyDispatcher;
    this.singlePolicyChecker = singlePolicyChecker;
  }

  /**
   * Resolves an enabled Iceberg encryption policy from association and inheritance.
   *
   * <p>The candidate table is not queried because it does not exist during table creation. A
   * missing parent metadata object is treated as having no associated policies so that {@code
   * createTable} on a dropped schema can fall through to the catalog dispatcher and surface {@code
   * NoSuchSchemaException}.
   *
   * @param tableIdentifier candidate table identifier
   * @return the sole matching policy, or empty when no policy is in scope
   */
  public Optional<PolicyEntity> resolve(NameIdentifier tableIdentifier) {
    NameIdentifierUtil.checkTable(tableIdentifier);

    String metalake = tableIdentifier.namespace().level(0);
    MetadataObject table =
        NameIdentifierUtil.toMetadataObject(tableIdentifier, Entity.EntityType.TABLE);
    Map<String, PolicyEntity> matches = new LinkedHashMap<>();

    // TODO(#1414): Refactor after policy-on-tag lands.
    for (MetadataObject parent : MetadataObjectUtil.getParentMetadataObjects(table)) {
      if (parent.type() == MetadataObject.Type.METALAKE) {
        continue;
      }

      PolicyEntity[] associated;
      try {
        associated = policyDispatcher.listPolicyInfosForMetadataObject(metalake, parent);
      } catch (NoSuchMetadataObjectException e) {
        // Dropped or never-created ancestors inherit nothing; keep walking other parents.
        continue;
      }
      if (associated == null) {
        continue;
      }
      for (PolicyEntity policy : associated) {
        if (!policy.enabled() || policy.policyType() != Policy.BuiltInType.ICEBERG_ENCRYPTION) {
          continue;
        }
        matches.putIfAbsent(policy.name(), policy);
      }
    }

    return singlePolicyChecker.check(
        tableIdentifier, Policy.BuiltInType.ICEBERG_ENCRYPTION, new ArrayList<>(matches.values()));
  }
}
