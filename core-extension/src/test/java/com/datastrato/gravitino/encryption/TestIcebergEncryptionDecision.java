/*
 * Copyright 2026 Datastrato Pvt Ltd.
 */
package com.datastrato.gravitino.encryption;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.util.Collections;
import org.apache.gravitino.encryption.kms.KmsReference;
import org.apache.gravitino.meta.PolicyEntity;
import org.apache.gravitino.policy.IcebergEncryptionContent;
import org.apache.gravitino.policy.IcebergEncryptionContent.Enforcement;
import org.apache.gravitino.policy.Policy;
import org.apache.gravitino.policy.PolicyContent;
import org.apache.gravitino.policy.PolicyContents;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

/**
 * Pins the constructor guards: a decision rejects a missing component as an illegal argument up
 * front, before the both-or-neither and validated-key constraints that assume those components are
 * present.
 */
public class TestIcebergEncryptionDecision {

  @Test
  void testNullDecisionComponentsAreRejectedAsIllegalArguments() {
    Assertions.assertEquals(
        "compliance cannot be null",
        Assertions.assertThrows(
                IllegalArgumentException.class,
                () ->
                    new IcebergEncryptionDecision(
                        "decision-1",
                        null,
                        IcebergEncryptionDecision.Outcome.SUCCEEDED,
                        IcebergEncryptionDecision.Reason.NO_POLICY,
                        null,
                        null,
                        IcebergEncryptionDecision.KmsValidationStatus.NOT_ATTEMPTED,
                        null))
            .getMessage());

    Assertions.assertEquals(
        "outcome cannot be null",
        Assertions.assertThrows(
                IllegalArgumentException.class,
                () ->
                    new IcebergEncryptionDecision(
                        "decision-1",
                        IcebergEncryptionDecision.Compliance.NOT_APPLICABLE,
                        null,
                        IcebergEncryptionDecision.Reason.NO_POLICY,
                        null,
                        null,
                        IcebergEncryptionDecision.KmsValidationStatus.NOT_ATTEMPTED,
                        null))
            .getMessage());

    Assertions.assertEquals(
        "reason cannot be null",
        Assertions.assertThrows(
                IllegalArgumentException.class,
                () ->
                    new IcebergEncryptionDecision(
                        "decision-1",
                        IcebergEncryptionDecision.Compliance.NOT_APPLICABLE,
                        IcebergEncryptionDecision.Outcome.SUCCEEDED,
                        null,
                        null,
                        null,
                        IcebergEncryptionDecision.KmsValidationStatus.NOT_ATTEMPTED,
                        null))
            .getMessage());

    Assertions.assertEquals(
        "kmsValidationStatus cannot be null",
        Assertions.assertThrows(
                IllegalArgumentException.class,
                () ->
                    new IcebergEncryptionDecision(
                        "decision-1",
                        IcebergEncryptionDecision.Compliance.NOT_APPLICABLE,
                        IcebergEncryptionDecision.Outcome.SUCCEEDED,
                        IcebergEncryptionDecision.Reason.NO_POLICY,
                        null,
                        null,
                        null,
                        null))
            .getMessage());
  }

  @Test
  void testNullPolicyReferenceComponentsAreRejectedAsIllegalArguments() {
    Assertions.assertEquals(
        "policy name cannot be null",
        Assertions.assertThrows(
                IllegalArgumentException.class,
                () ->
                    IcebergEncryptionDecision.PolicyReference.from(
                        policy(null, Policy.BuiltInType.ICEBERG_ENCRYPTION)))
            .getMessage());

    Assertions.assertEquals(
        "policy type cannot be null",
        Assertions.assertThrows(
                IllegalArgumentException.class,
                () -> IcebergEncryptionDecision.PolicyReference.from(policy("policy", null)))
            .getMessage());
  }

  private static PolicyEntity policy(String name, Policy.BuiltInType type) {
    PolicyContent content =
        PolicyContents.icebergEncryption(
            IcebergEncryptionContent.CURRENT_SCHEMA_VERSION,
            true,
            Collections.singletonList(new KmsReference("openbao", "customer-pii-v1")),
            Enforcement.DENY_CREATE);
    PolicyEntity policy = mock(PolicyEntity.class);
    when(policy.id()).thenReturn(101L);
    when(policy.name()).thenReturn(name);
    when(policy.policyType()).thenReturn(type);
    when(policy.content()).thenReturn(content);
    return policy;
  }
}
