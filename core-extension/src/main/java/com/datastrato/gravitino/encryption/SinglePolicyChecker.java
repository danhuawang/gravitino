/*
 * Copyright 2026 Datastrato Pvt Ltd.
 */
package com.datastrato.gravitino.encryption;

import com.google.common.base.Preconditions;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import org.apache.gravitino.NameIdentifier;
import org.apache.gravitino.exceptions.AmbiguousPolicyException;
import org.apache.gravitino.meta.PolicyEntity;
import org.apache.gravitino.policy.Policy;

/** Enforces the prototype invariant that at most one policy of a type applies to a table. */
public final class SinglePolicyChecker {

  /**
   * Returns the sole matching policy or rejects an ambiguous configuration.
   *
   * @param tableIdentifier target table identifier
   * @param policyType type of the matching policies
   * @param matchingPolicies matching policies
   * @return the sole matching policy, or empty when no policy matches
   * @throws AmbiguousPolicyException when more than one policy matches
   */
  public Optional<PolicyEntity> check(
      NameIdentifier tableIdentifier,
      Policy.BuiltInType policyType,
      Collection<PolicyEntity> matchingPolicies) {
    Preconditions.checkArgument(tableIdentifier != null, "tableIdentifier cannot be null");
    Preconditions.checkArgument(policyType != null, "policyType cannot be null");
    Preconditions.checkArgument(matchingPolicies != null, "matchingPolicies cannot be null");

    List<PolicyEntity> policies = List.copyOf(matchingPolicies);
    if (policies.isEmpty()) {
      return Optional.empty();
    }
    if (policies.size() == 1) {
      return Optional.of(policies.get(0));
    }

    String[] policyNames =
        policies.stream().map(PolicyEntity::name).sorted().toArray(String[]::new);
    throw new AmbiguousPolicyException(
        policyNames,
        "Only one %s policy may apply to table %s; matched policies: [%s]",
        policyType.policyType(),
        tableIdentifier,
        String.join(", ", policyNames));
  }
}
