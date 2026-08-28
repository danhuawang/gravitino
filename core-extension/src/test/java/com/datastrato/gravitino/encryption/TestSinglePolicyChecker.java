/*
 * Copyright 2026 Datastrato Pvt Ltd.
 */
package com.datastrato.gravitino.encryption;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.util.Arrays;
import java.util.Collections;
import java.util.Optional;
import org.apache.gravitino.NameIdentifier;
import org.apache.gravitino.exceptions.AmbiguousPolicyException;
import org.apache.gravitino.meta.PolicyEntity;
import org.apache.gravitino.policy.Policy;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

/**
 * Pins the zero-or-one contract: empty when nothing matches, the sole policy when exactly one does,
 * and {@link AmbiguousPolicyException} with deterministically sorted names when more than one does.
 */
public class TestSinglePolicyChecker {

  private static final NameIdentifier TABLE =
      NameIdentifier.of("metalake", "catalog", "schema", "table");
  private static final Policy.BuiltInType TYPE = Policy.BuiltInType.ICEBERG_ENCRYPTION;

  private final SinglePolicyChecker checker = new SinglePolicyChecker();

  @Test
  void testZeroPoliciesReturnsEmpty() {
    Assertions.assertEquals(Optional.empty(), checker.check(TABLE, TYPE, Collections.emptyList()));
  }

  @Test
  void testOnePolicyReturnsMatch() {
    PolicyEntity policy = policy("only-policy");
    Optional<PolicyEntity> selected = checker.check(TABLE, TYPE, Collections.singletonList(policy));
    Assertions.assertTrue(selected.isPresent());
    Assertions.assertSame(policy, selected.get());
  }

  @Test
  void testAmbiguousPoliciesHaveDeterministicUserVisibleError() {
    AmbiguousPolicyException exception =
        Assertions.assertThrows(
            AmbiguousPolicyException.class,
            () ->
                checker.check(TABLE, TYPE, Arrays.asList(policy("z-policy"), policy("a-policy"))));

    Assertions.assertEquals(
        "Only one system_iceberg_encryption policy may apply to table "
            + "metalake.catalog.schema.table; matched policies: [a-policy, z-policy]",
        exception.getMessage());
    Assertions.assertArrayEquals(
        new String[] {"a-policy", "z-policy"}, exception.matchedPolicyNames());
  }

  @Test
  void testNullArgumentsAreRejectedAsIllegalArguments() {
    Assertions.assertEquals(
        "tableIdentifier cannot be null",
        Assertions.assertThrows(
                IllegalArgumentException.class,
                () -> checker.check(null, TYPE, Collections.emptyList()))
            .getMessage());
    Assertions.assertEquals(
        "policyType cannot be null",
        Assertions.assertThrows(
                IllegalArgumentException.class,
                () -> checker.check(TABLE, null, Collections.emptyList()))
            .getMessage());
    Assertions.assertEquals(
        "matchingPolicies cannot be null",
        Assertions.assertThrows(
                IllegalArgumentException.class, () -> checker.check(TABLE, TYPE, null))
            .getMessage());
  }

  private static PolicyEntity policy(String name) {
    PolicyEntity policy = mock(PolicyEntity.class);
    when(policy.name()).thenReturn(name);
    return policy;
  }
}
