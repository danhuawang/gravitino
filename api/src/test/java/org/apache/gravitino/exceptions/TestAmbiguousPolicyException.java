/*
 * Copyright 2026 Datastrato Pvt Ltd.
 */
package org.apache.gravitino.exceptions;

import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

/**
 * Pins the caller-visible guarantees: matched policy names are sorted so error text is
 * deterministic, and defensively copied on both input and output.
 */
public class TestAmbiguousPolicyException {

  @Test
  void testLegacyConstructorPreservesMessage() {
    AmbiguousPolicyException exception =
        new AmbiguousPolicyException("Policy %s is ambiguous", "example");

    Assertions.assertEquals("Policy example is ambiguous", exception.getMessage());
    Assertions.assertArrayEquals(new String[0], exception.matchedPolicyNames());
  }

  @Test
  void testMatchedPolicyNamesAreSortedAndDefensivelyCopied() {
    String[] matchedPolicyNames = {"z-policy", "a-policy"};
    AmbiguousPolicyException exception =
        new AmbiguousPolicyException(
            matchedPolicyNames, "Matched %s policies", matchedPolicyNames.length);

    matchedPolicyNames[0] = "changed-input";
    Assertions.assertArrayEquals(
        new String[] {"a-policy", "z-policy"}, exception.matchedPolicyNames());

    String[] returnedNames = exception.matchedPolicyNames();
    returnedNames[0] = "changed-output";
    Assertions.assertArrayEquals(
        new String[] {"a-policy", "z-policy"}, exception.matchedPolicyNames());
    Assertions.assertEquals("Matched 2 policies", exception.getMessage());
  }

  @Test
  void testNullMatchedPolicyNamesIsRejectedAsIllegalArgument() {
    IllegalArgumentException exception =
        Assertions.assertThrows(
            IllegalArgumentException.class,
            () -> new AmbiguousPolicyException((String[]) null, "Matched %s policies", 0));

    Assertions.assertEquals("matchedPolicyNames cannot be null", exception.getMessage());
  }
}
