/*
 * Copyright 2026 Datastrato Pvt Ltd.
 */
package com.datastrato.gravitino.encryption;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.google.common.collect.ImmutableMap;
import java.util.Arrays;
import java.util.Collections;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import org.apache.gravitino.encryption.kms.KmsReference;
import org.apache.gravitino.meta.PolicyEntity;
import org.apache.gravitino.policy.IcebergEncryptionContent;
import org.apache.gravitino.policy.IcebergEncryptionContent.Enforcement;
import org.apache.gravitino.policy.Policy;
import org.apache.gravitino.policy.PolicyContent;
import org.apache.gravitino.policy.PolicyContents;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

public class TestIcebergEncryptionPolicyEvaluator {

  private static final String DECISION_ID = "decision-1";
  private static final String API = "openbao-transit";
  private static final String PROVIDER = "openbao";
  private static final String KEY_ID = "customer-pii-v1";

  @Test
  void testNoPolicyWithoutKeySucceedsWithoutValidation() {
    IcebergEncryptionPolicyEvaluator evaluator = evaluatorThatMustNotValidate();

    IcebergEncryptionDecision decision =
        evaluator.evaluate(Optional.empty(), null, Collections.emptyMap());

    Assertions.assertEquals(DECISION_ID, decision.decisionId());
    Assertions.assertEquals(
        IcebergEncryptionDecision.Compliance.NOT_APPLICABLE, decision.compliance());
    Assertions.assertEquals(IcebergEncryptionDecision.Outcome.SUCCEEDED, decision.outcome());
    Assertions.assertEquals(IcebergEncryptionDecision.Reason.NO_POLICY, decision.reason());
    Assertions.assertNull(decision.policy());
    Assertions.assertNull(decision.enforcement());
    Assertions.assertEquals(
        IcebergEncryptionDecision.KmsValidationStatus.NOT_ATTEMPTED,
        decision.kmsValidationStatus());
    Assertions.assertNull(decision.validatedKey());
    Assertions.assertTrue(decision.admitted());
  }

  @Test
  void testNoPolicyValidKeyIsValidatedAndReturned() {
    AtomicReference<KmsReference> validatedKey = new AtomicReference<>();
    IcebergEncryptionPolicyEvaluator evaluator =
        evaluator(
            key -> {
              validatedKey.set(key);
              return IcebergEncryptionDecision.KmsValidationStatus.VALID;
            });

    IcebergEncryptionDecision decision =
        evaluator.evaluate(Optional.empty(), PROVIDER, keyProperties(PROVIDER, KEY_ID));

    Assertions.assertEquals(IcebergEncryptionDecision.Outcome.SUCCEEDED, decision.outcome());
    Assertions.assertEquals(IcebergEncryptionDecision.Reason.NO_POLICY, decision.reason());
    Assertions.assertEquals(
        IcebergEncryptionDecision.KmsValidationStatus.VALID, decision.kmsValidationStatus());
    Assertions.assertEquals(validatedKey.get(), decision.validatedKey());
    Assertions.assertEquals(PROVIDER, decision.validatedKey().provider());
    Assertions.assertEquals(KEY_ID, decision.validatedKey().keyId());
    Assertions.assertEquals(new KmsReference(PROVIDER, KEY_ID), decision.validatedKey());
  }

  @Test
  void testMissingRequiredKeyFollowsEnforcementMode() {
    assertMissingRequiredKey(Enforcement.REPORT, IcebergEncryptionDecision.Outcome.SUCCEEDED, true);
    assertMissingRequiredKey(
        Enforcement.DENY_CREATE, IcebergEncryptionDecision.Outcome.DENIED, false);
  }

  @Test
  void testMissingOptionalKeyIsCompliant() {
    PolicyEntity policy = policy(false, Enforcement.DENY_CREATE, PROVIDER, KEY_ID);

    IcebergEncryptionDecision decision =
        evaluatorThatMustNotValidate()
            .evaluate(Optional.of(policy), PROVIDER, Collections.emptyMap());

    Assertions.assertEquals(IcebergEncryptionDecision.Compliance.COMPLIANT, decision.compliance());
    Assertions.assertEquals(IcebergEncryptionDecision.Outcome.SUCCEEDED, decision.outcome());
    Assertions.assertEquals(IcebergEncryptionDecision.Reason.COMPLIANT, decision.reason());
    Assertions.assertTrue(decision.admitted());
  }

  @Test
  void testCatalogProviderMismatchIsAlwaysDenied() {
    assertPolicyViolation(
        policy(true, Enforcement.REPORT, PROVIDER, KEY_ID),
        "aws",
        keyProperties(PROVIDER, KEY_ID),
        IcebergEncryptionDecision.Reason.KEY_SOURCE_MISMATCH,
        IcebergEncryptionDecision.Outcome.DENIED);
    assertPolicyViolation(
        policy(true, Enforcement.DENY_CREATE, PROVIDER, KEY_ID),
        "aws",
        keyProperties(PROVIDER, KEY_ID),
        IcebergEncryptionDecision.Reason.KEY_SOURCE_MISMATCH,
        IcebergEncryptionDecision.Outcome.DENIED);
  }

  @Test
  void testPolicyProviderMatchingIsExactAndCaseSensitive() {
    IcebergEncryptionDecision decision =
        evaluator(key -> IcebergEncryptionDecision.KmsValidationStatus.VALID)
            .evaluate(
                Optional.of(policy(true, Enforcement.REPORT, PROVIDER, KEY_ID)),
                "OpenBao",
                keyProperties("OpenBao", KEY_ID));

    assertReportedAllowlistViolation(decision, "OpenBao", KEY_ID);
  }

  @Test
  void testDisallowedKeyFollowsEnforcementAndExactCase() {
    IcebergEncryptionDecision reportedDecision =
        evaluator(key -> IcebergEncryptionDecision.KmsValidationStatus.VALID)
            .evaluate(
                Optional.of(policy(true, Enforcement.REPORT, PROVIDER, "Key-A")),
                PROVIDER,
                keyProperties(PROVIDER, "key-a"));

    assertReportedAllowlistViolation(reportedDecision, PROVIDER, "key-a");
    assertPolicyViolation(
        policy(true, Enforcement.DENY_CREATE, PROVIDER, "Key-A"),
        PROVIDER,
        keyProperties(PROVIDER, "key-a"),
        IcebergEncryptionDecision.Reason.KEY_NOT_ALLOWED,
        IcebergEncryptionDecision.Outcome.DENIED);
  }

  @Test
  void testReportedDisallowedKeyStillMustBeKmsValid() {
    PolicyEntity reportPolicy = policy(true, Enforcement.REPORT, PROVIDER, "allowed-key");

    IcebergEncryptionDecision invalidDecision =
        evaluator(key -> IcebergEncryptionDecision.KmsValidationStatus.INVALID)
            .evaluate(
                Optional.of(reportPolicy), PROVIDER, keyProperties(PROVIDER, "different-key"));
    Assertions.assertEquals(IcebergEncryptionDecision.Outcome.DENIED, invalidDecision.outcome());
    Assertions.assertEquals(
        IcebergEncryptionDecision.Reason.KMS_KEY_INVALID, invalidDecision.reason());
    Assertions.assertNull(invalidDecision.validatedKey());

    IcebergEncryptionDecision unavailableDecision =
        evaluator(key -> IcebergEncryptionDecision.KmsValidationStatus.UNAVAILABLE)
            .evaluate(
                Optional.of(reportPolicy), PROVIDER, keyProperties(PROVIDER, "different-key"));
    Assertions.assertEquals(
        IcebergEncryptionDecision.Outcome.FAILED, unavailableDecision.outcome());
    Assertions.assertEquals(
        IcebergEncryptionDecision.Reason.KMS_SERVICE_UNAVAILABLE, unavailableDecision.reason());
    Assertions.assertNull(unavailableDecision.validatedKey());
  }

  @Test
  void testAllowedKeyIsValidatedAndReturnedExactly() {
    AtomicInteger validationCalls = new AtomicInteger();
    IcebergEncryptionPolicyEvaluator evaluator =
        evaluator(
            key -> {
              validationCalls.incrementAndGet();
              Assertions.assertEquals(PROVIDER, key.provider());
              Assertions.assertEquals(KEY_ID, key.keyId());
              return IcebergEncryptionDecision.KmsValidationStatus.VALID;
            });

    IcebergEncryptionDecision decision =
        evaluator.evaluate(
            Optional.of(policy(true, Enforcement.DENY_CREATE, PROVIDER, KEY_ID)),
            PROVIDER,
            keyProperties(PROVIDER, KEY_ID));

    Assertions.assertEquals(1, validationCalls.get());
    Assertions.assertEquals(IcebergEncryptionDecision.Compliance.COMPLIANT, decision.compliance());
    Assertions.assertEquals(IcebergEncryptionDecision.Outcome.SUCCEEDED, decision.outcome());
    Assertions.assertEquals(IcebergEncryptionDecision.Reason.COMPLIANT, decision.reason());
    Assertions.assertEquals(
        IcebergEncryptionDecision.KmsValidationStatus.VALID, decision.kmsValidationStatus());
    Assertions.assertEquals(PROVIDER, decision.validatedKey().provider());
    Assertions.assertEquals(KEY_ID, decision.validatedKey().keyId());
    Assertions.assertEquals(new KmsReference(PROVIDER, KEY_ID), decision.validatedKey());
    Assertions.assertEquals("policy", decision.policy().name());
    Assertions.assertEquals(Long.valueOf(101L), decision.policy().id());
    Assertions.assertEquals(Policy.BuiltInType.ICEBERG_ENCRYPTION, decision.policy().type());
    Assertions.assertEquals(1, decision.policy().contentSchemaVersion());
  }

  @Test
  void testKmsInvalidKeyIsAlwaysDenied() {
    for (Enforcement enforcement : Enforcement.values()) {
      IcebergEncryptionDecision decision =
          evaluator(key -> IcebergEncryptionDecision.KmsValidationStatus.INVALID)
              .evaluate(
                  Optional.of(policy(true, enforcement, PROVIDER, KEY_ID)),
                  PROVIDER,
                  keyProperties(PROVIDER, KEY_ID));

      Assertions.assertEquals(
          IcebergEncryptionDecision.Compliance.VIOLATION, decision.compliance());
      Assertions.assertEquals(IcebergEncryptionDecision.Outcome.DENIED, decision.outcome());
      Assertions.assertEquals(IcebergEncryptionDecision.Reason.KMS_KEY_INVALID, decision.reason());
      Assertions.assertEquals(
          IcebergEncryptionDecision.KmsValidationStatus.INVALID, decision.kmsValidationStatus());
      Assertions.assertNull(decision.validatedKey());
      Assertions.assertFalse(decision.admitted());
    }
  }

  @Test
  void testKmsUnavailableKeyAlwaysFailsClosed() {
    for (Enforcement enforcement : Enforcement.values()) {
      IcebergEncryptionDecision decision =
          evaluator(key -> IcebergEncryptionDecision.KmsValidationStatus.UNAVAILABLE)
              .evaluate(
                  Optional.of(policy(true, enforcement, PROVIDER, KEY_ID)),
                  PROVIDER,
                  keyProperties(PROVIDER, KEY_ID));

      Assertions.assertEquals(
          IcebergEncryptionDecision.Compliance.VIOLATION, decision.compliance());
      Assertions.assertEquals(IcebergEncryptionDecision.Outcome.FAILED, decision.outcome());
      Assertions.assertEquals(
          IcebergEncryptionDecision.Reason.KMS_SERVICE_UNAVAILABLE, decision.reason());
      Assertions.assertEquals(
          IcebergEncryptionDecision.KmsValidationStatus.UNAVAILABLE,
          decision.kmsValidationStatus());
      Assertions.assertNull(decision.validatedKey());
      Assertions.assertFalse(decision.admitted());
    }
  }

  @Test
  void testIncompleteKeyReferenceIsAlwaysDenied() {
    PolicyEntity reportPolicy = policy(true, Enforcement.REPORT, PROVIDER, KEY_ID);

    for (Map<String, String> properties :
        Arrays.asList(
            ImmutableMap.of(IcebergEncryptionPolicyEvaluator.ENCRYPTION_KEY_PROVIDER, PROVIDER),
            ImmutableMap.of(IcebergEncryptionPolicyEvaluator.ENCRYPTION_KEY_ID, KEY_ID),
            keyProperties(" ", KEY_ID),
            keyProperties(PROVIDER, " "))) {
      IcebergEncryptionDecision decision =
          evaluatorThatMustNotValidate().evaluate(Optional.of(reportPolicy), PROVIDER, properties);

      Assertions.assertEquals(IcebergEncryptionDecision.Outcome.DENIED, decision.outcome());
      Assertions.assertEquals(
          IcebergEncryptionDecision.Reason.KEY_REFERENCE_INVALID, decision.reason());
      Assertions.assertFalse(decision.admitted());
    }
  }

  @Test
  void testLegacyTriplePropertiesAreRejectedWithoutValidation() {
    PolicyEntity reportPolicy = policy(true, Enforcement.REPORT, PROVIDER, KEY_ID);

    for (Map<String, String> properties :
        Arrays.asList(
            ImmutableMap.of(IcebergEncryptionPolicyEvaluator.ENCRYPTION_KMS_API, API),
            ImmutableMap.of(IcebergEncryptionPolicyEvaluator.ENCRYPTION_KEY_SOURCE, PROVIDER),
            ImmutableMap.of(
                IcebergEncryptionPolicyEvaluator.ENCRYPTION_KMS_API,
                API,
                IcebergEncryptionPolicyEvaluator.ENCRYPTION_KEY_ID,
                KEY_ID),
            ImmutableMap.of(
                IcebergEncryptionPolicyEvaluator.ENCRYPTION_KEY_SOURCE,
                PROVIDER,
                IcebergEncryptionPolicyEvaluator.ENCRYPTION_KEY_ID,
                KEY_ID),
            ImmutableMap.of(
                IcebergEncryptionPolicyEvaluator.ENCRYPTION_KEY_PROVIDER,
                PROVIDER,
                IcebergEncryptionPolicyEvaluator.ENCRYPTION_KEY_ID,
                KEY_ID,
                IcebergEncryptionPolicyEvaluator.ENCRYPTION_KMS_API,
                API))) {
      IcebergEncryptionDecision decision =
          evaluatorThatMustNotValidate().evaluate(Optional.of(reportPolicy), PROVIDER, properties);

      Assertions.assertEquals(IcebergEncryptionDecision.Outcome.DENIED, decision.outcome());
      Assertions.assertEquals(
          IcebergEncryptionDecision.Reason.KEY_REFERENCE_INVALID, decision.reason());
      Assertions.assertEquals(
          IcebergEncryptionDecision.KmsValidationStatus.NOT_ATTEMPTED,
          decision.kmsValidationStatus());
      Assertions.assertNull(decision.validatedKey());
    }
  }

  private static void assertMissingRequiredKey(
      Enforcement enforcement, IcebergEncryptionDecision.Outcome outcome, boolean admitted) {
    IcebergEncryptionDecision decision =
        evaluatorThatMustNotValidate()
            .evaluate(
                Optional.of(policy(true, enforcement, PROVIDER, KEY_ID)),
                PROVIDER,
                Collections.emptyMap());

    Assertions.assertEquals(IcebergEncryptionDecision.Compliance.VIOLATION, decision.compliance());
    Assertions.assertEquals(outcome, decision.outcome());
    Assertions.assertEquals(IcebergEncryptionDecision.Reason.KEY_REQUIRED, decision.reason());
    Assertions.assertEquals(enforcement, decision.enforcement());
    Assertions.assertEquals(admitted, decision.admitted());
    Assertions.assertNull(decision.validatedKey());
  }

  private static void assertReportedAllowlistViolation(
      IcebergEncryptionDecision decision, String provider, String id) {
    Assertions.assertEquals(IcebergEncryptionDecision.Compliance.VIOLATION, decision.compliance());
    Assertions.assertEquals(IcebergEncryptionDecision.Outcome.SUCCEEDED, decision.outcome());
    Assertions.assertEquals(IcebergEncryptionDecision.Reason.KEY_NOT_ALLOWED, decision.reason());
    Assertions.assertEquals(
        IcebergEncryptionDecision.KmsValidationStatus.VALID, decision.kmsValidationStatus());
    Assertions.assertEquals(provider, decision.validatedKey().provider());
    Assertions.assertEquals(id, decision.validatedKey().keyId());
    Assertions.assertTrue(decision.admitted());
  }

  private static void assertPolicyViolation(
      PolicyEntity policy,
      String catalogProvider,
      Map<String, String> properties,
      IcebergEncryptionDecision.Reason reason,
      IcebergEncryptionDecision.Outcome outcome) {
    IcebergEncryptionDecision decision =
        evaluatorThatMustNotValidate().evaluate(Optional.of(policy), catalogProvider, properties);

    Assertions.assertEquals(IcebergEncryptionDecision.Compliance.VIOLATION, decision.compliance());
    Assertions.assertEquals(outcome, decision.outcome());
    Assertions.assertEquals(reason, decision.reason());
    Assertions.assertEquals(
        IcebergEncryptionDecision.KmsValidationStatus.NOT_ATTEMPTED,
        decision.kmsValidationStatus());
    Assertions.assertNull(decision.validatedKey());
  }

  @Test
  void testNullArgumentsAreRejectedAsIllegalArguments() {
    Assertions.assertEquals(
        "keyValidator cannot be null",
        Assertions.assertThrows(
                IllegalArgumentException.class, () -> new IcebergEncryptionPolicyEvaluator(null))
            .getMessage());
    Assertions.assertEquals(
        "decisionIdSupplier cannot be null",
        Assertions.assertThrows(
                IllegalArgumentException.class,
                () ->
                    new IcebergEncryptionPolicyEvaluator(
                        key -> IcebergEncryptionDecision.KmsValidationStatus.VALID, null))
            .getMessage());

    IcebergEncryptionPolicyEvaluator evaluator = evaluatorThatMustNotValidate();
    Assertions.assertEquals(
        "resolvedPolicy cannot be null",
        Assertions.assertThrows(
                IllegalArgumentException.class,
                () -> evaluator.evaluate(null, null, Collections.emptyMap()))
            .getMessage());
    Assertions.assertEquals(
        "tableProperties cannot be null",
        Assertions.assertThrows(
                IllegalArgumentException.class,
                () -> evaluator.evaluate(Optional.empty(), null, null))
            .getMessage());
  }

  @Test
  void testValidatorReturningNullIsRejectedAsIllegalArgument() {
    IcebergEncryptionPolicyEvaluator evaluator = evaluator(key -> null);

    Assertions.assertEquals(
        "keyValidator returned null",
        Assertions.assertThrows(
                IllegalArgumentException.class,
                () ->
                    evaluator.evaluate(Optional.empty(), PROVIDER, keyProperties(PROVIDER, KEY_ID)))
            .getMessage());
  }

  private static IcebergEncryptionPolicyEvaluator evaluatorThatMustNotValidate() {
    return evaluator(
        key -> {
          throw new AssertionError("KMS validation was not expected");
        });
  }

  private static IcebergEncryptionPolicyEvaluator evaluator(
      IcebergEncryptionPolicyEvaluator.KmsKeyValidator keyValidator) {
    return new IcebergEncryptionPolicyEvaluator(keyValidator, () -> DECISION_ID);
  }

  private static Map<String, String> keyProperties(String provider, String id) {
    return ImmutableMap.of(
        IcebergEncryptionPolicyEvaluator.ENCRYPTION_KEY_PROVIDER,
        provider,
        IcebergEncryptionPolicyEvaluator.ENCRYPTION_KEY_ID,
        id);
  }

  private static PolicyEntity policy(
      boolean required, Enforcement enforcement, String allowedProvider, String allowedId) {
    PolicyContent content =
        PolicyContents.icebergEncryption(
            IcebergEncryptionContent.CURRENT_SCHEMA_VERSION,
            required,
            Collections.singletonList(new KmsReference(allowedProvider, allowedId)),
            enforcement);
    PolicyEntity policy = mock(PolicyEntity.class);
    when(policy.id()).thenReturn(101L);
    when(policy.name()).thenReturn("policy");
    when(policy.enabled()).thenReturn(true);
    when(policy.policyType()).thenReturn(Policy.BuiltInType.ICEBERG_ENCRYPTION);
    when(policy.content()).thenReturn(content);
    return policy;
  }
}
