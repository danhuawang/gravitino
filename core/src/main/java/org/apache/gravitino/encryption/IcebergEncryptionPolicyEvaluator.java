/*
 * Copyright 2026 Datastrato Pvt Ltd.
 */
package org.apache.gravitino.encryption;

import com.google.common.base.Preconditions;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Supplier;
import javax.annotation.Nullable;
import org.apache.commons.lang3.StringUtils;
import org.apache.gravitino.encryption.kms.KmsReference;
import org.apache.gravitino.meta.PolicyEntity;
import org.apache.gravitino.policy.IcebergEncryptionContent;
import org.apache.gravitino.policy.IcebergEncryptionContent.Enforcement;
import org.apache.gravitino.policy.Policy;

/** Pure create-time evaluator for an already resolved Iceberg encryption policy. */
public final class IcebergEncryptionPolicyEvaluator {

  /** User table property containing the configured KMS provider name. */
  public static final String ENCRYPTION_KEY_PROVIDER = "encryption.key-provider";

  /** User table property containing the provider-native KMS key identifier. */
  public static final String ENCRYPTION_KEY_ID = "encryption.key-id";

  /** Rejected leftover Apache table property; {@code api} is resolved from provider config. */
  public static final String ENCRYPTION_KMS_API = "encryption.kms-api";

  /** Rejected leftover Apache table property; use {@link #ENCRYPTION_KEY_PROVIDER}. */
  public static final String ENCRYPTION_KEY_SOURCE = "encryption.key-source";

  private final KmsKeyValidator keyValidator;
  private final Supplier<String> decisionIdSupplier;

  /**
   * Creates an evaluator that assigns UUID decision identifiers.
   *
   * @param keyValidator metadata-only validator for same-catalog-provider keys
   */
  public IcebergEncryptionPolicyEvaluator(KmsKeyValidator keyValidator) {
    this(keyValidator, () -> UUID.randomUUID().toString());
  }

  IcebergEncryptionPolicyEvaluator(
      KmsKeyValidator keyValidator, Supplier<String> decisionIdSupplier) {
    Preconditions.checkArgument(keyValidator != null, "keyValidator cannot be null");
    Preconditions.checkArgument(decisionIdSupplier != null, "decisionIdSupplier cannot be null");
    this.keyValidator = keyValidator;
    this.decisionIdSupplier = decisionIdSupplier;
  }

  /**
   * Evaluates an optional resolved policy against Iceberg table-create properties.
   *
   * <p>User identity is {@link KmsReference} {@code {provider, keyId}}. Protocol {@code api} is not
   * a table property. The KMS is called only after the requested provider matches the catalog
   * binding. Raw table properties are never returned as validated input; callers must forward only
   * {@link IcebergEncryptionDecision#validatedKey()}.
   *
   * @param resolvedPolicy effective Iceberg encryption policy, if one applies
   * @param catalogKmsSource KMS provider bound to the Iceberg catalog, or {@code null} if unbound
   * @param tableProperties user-supplied table-create properties
   * @return immutable terminal decision
   */
  public IcebergEncryptionDecision evaluate(
      Optional<PolicyEntity> resolvedPolicy,
      @Nullable String catalogKmsSource,
      Map<String, String> tableProperties) {
    Preconditions.checkArgument(resolvedPolicy != null, "resolvedPolicy cannot be null");
    Preconditions.checkArgument(tableProperties != null, "tableProperties cannot be null");

    PolicyEntity policy = resolvedPolicy.orElse(null);
    IcebergEncryptionContent content = validatePolicy(policy);
    IcebergEncryptionDecision.PolicyReference policyReference =
        policy == null ? null : IcebergEncryptionDecision.PolicyReference.from(policy);
    Enforcement enforcement = content == null ? null : content.enforcement();
    String decisionId = nextDecisionId();

    boolean leftoverProtocolProperty =
        tableProperties.containsKey(ENCRYPTION_KMS_API)
            || tableProperties.containsKey(ENCRYPTION_KEY_SOURCE);
    boolean anyKeyProperty =
        leftoverProtocolProperty
            || tableProperties.containsKey(ENCRYPTION_KEY_PROVIDER)
            || tableProperties.containsKey(ENCRYPTION_KEY_ID);

    if (!anyKeyProperty) {
      return evaluateMissingKey(decisionId, policyReference, content, enforcement);
    }

    if (leftoverProtocolProperty) {
      return hardRejection(
          decisionId,
          policyReference,
          enforcement,
          policy == null
              ? IcebergEncryptionDecision.Compliance.NOT_APPLICABLE
              : IcebergEncryptionDecision.Compliance.VIOLATION,
          IcebergEncryptionDecision.Reason.KEY_REFERENCE_INVALID,
          IcebergEncryptionDecision.Outcome.DENIED,
          IcebergEncryptionDecision.KmsValidationStatus.NOT_ATTEMPTED);
    }

    String requestedProvider = tableProperties.get(ENCRYPTION_KEY_PROVIDER);
    String requestedId = tableProperties.get(ENCRYPTION_KEY_ID);
    if (StringUtils.isBlank(requestedProvider) || StringUtils.isBlank(requestedId)) {
      return hardRejection(
          decisionId,
          policyReference,
          enforcement,
          policy == null
              ? IcebergEncryptionDecision.Compliance.NOT_APPLICABLE
              : IcebergEncryptionDecision.Compliance.VIOLATION,
          IcebergEncryptionDecision.Reason.KEY_REFERENCE_INVALID,
          IcebergEncryptionDecision.Outcome.DENIED,
          IcebergEncryptionDecision.KmsValidationStatus.NOT_ATTEMPTED);
    }

    KmsReference requestedKey;
    try {
      requestedKey = new KmsReference(requestedProvider, requestedId);
    } catch (IllegalArgumentException e) {
      return hardRejection(
          decisionId,
          policyReference,
          enforcement,
          policy == null
              ? IcebergEncryptionDecision.Compliance.NOT_APPLICABLE
              : IcebergEncryptionDecision.Compliance.VIOLATION,
          IcebergEncryptionDecision.Reason.KEY_REFERENCE_INVALID,
          IcebergEncryptionDecision.Outcome.DENIED,
          IcebergEncryptionDecision.KmsValidationStatus.NOT_ATTEMPTED);
    }

    if (!requestedKey.provider().equals(catalogKmsSource)) {
      return hardRejection(
          decisionId,
          policyReference,
          enforcement,
          policy == null
              ? IcebergEncryptionDecision.Compliance.NOT_APPLICABLE
              : IcebergEncryptionDecision.Compliance.VIOLATION,
          IcebergEncryptionDecision.Reason.KEY_SOURCE_MISMATCH,
          IcebergEncryptionDecision.Outcome.DENIED,
          IcebergEncryptionDecision.KmsValidationStatus.NOT_ATTEMPTED);
    }

    boolean allowlistViolation = content != null && !content.allowedKeys().contains(requestedKey);
    if (allowlistViolation && enforcement == Enforcement.DENY_CREATE) {
      return policyViolation(
          decisionId,
          policyReference,
          enforcement,
          IcebergEncryptionDecision.Reason.KEY_NOT_ALLOWED);
    }

    IcebergEncryptionDecision.KmsValidationStatus validationStatus =
        keyValidator.validate(requestedKey);
    Preconditions.checkArgument(validationStatus != null, "keyValidator returned null");
    Preconditions.checkArgument(
        validationStatus != IcebergEncryptionDecision.KmsValidationStatus.NOT_ATTEMPTED,
        "keyValidator must return a terminal validation status");

    switch (validationStatus) {
      case VALID:
        return new IcebergEncryptionDecision(
            decisionId,
            policy == null
                ? IcebergEncryptionDecision.Compliance.NOT_APPLICABLE
                : allowlistViolation
                    ? IcebergEncryptionDecision.Compliance.VIOLATION
                    : IcebergEncryptionDecision.Compliance.COMPLIANT,
            IcebergEncryptionDecision.Outcome.SUCCEEDED,
            policy == null
                ? IcebergEncryptionDecision.Reason.NO_POLICY
                : allowlistViolation
                    ? IcebergEncryptionDecision.Reason.KEY_NOT_ALLOWED
                    : IcebergEncryptionDecision.Reason.COMPLIANT,
            policyReference,
            enforcement,
            validationStatus,
            requestedKey);
      case INVALID:
        return hardRejection(
            decisionId,
            policyReference,
            enforcement,
            policy == null
                ? IcebergEncryptionDecision.Compliance.NOT_APPLICABLE
                : IcebergEncryptionDecision.Compliance.VIOLATION,
            IcebergEncryptionDecision.Reason.KMS_KEY_INVALID,
            IcebergEncryptionDecision.Outcome.DENIED,
            validationStatus);
      case UNAVAILABLE:
        return hardRejection(
            decisionId,
            policyReference,
            enforcement,
            policy == null
                ? IcebergEncryptionDecision.Compliance.NOT_APPLICABLE
                : IcebergEncryptionDecision.Compliance.VIOLATION,
            IcebergEncryptionDecision.Reason.KMS_SERVICE_UNAVAILABLE,
            IcebergEncryptionDecision.Outcome.FAILED,
            validationStatus);
      default:
        throw new IllegalStateException("Unexpected KMS validation status: " + validationStatus);
    }
  }

  @Nullable
  private static IcebergEncryptionContent validatePolicy(@Nullable PolicyEntity policy) {
    if (policy == null) {
      return null;
    }
    Preconditions.checkArgument(policy.enabled(), "resolved policy must be enabled");
    Preconditions.checkArgument(
        policy.policyType() == Policy.BuiltInType.ICEBERG_ENCRYPTION,
        "resolved policy must have type ICEBERG_ENCRYPTION");
    Preconditions.checkArgument(
        policy.content() instanceof IcebergEncryptionContent,
        "resolved policy must contain IcebergEncryptionContent");
    return (IcebergEncryptionContent) policy.content();
  }

  private static IcebergEncryptionDecision evaluateMissingKey(
      String decisionId,
      @Nullable IcebergEncryptionDecision.PolicyReference policy,
      @Nullable IcebergEncryptionContent content,
      @Nullable Enforcement enforcement) {
    if (content == null) {
      return new IcebergEncryptionDecision(
          decisionId,
          IcebergEncryptionDecision.Compliance.NOT_APPLICABLE,
          IcebergEncryptionDecision.Outcome.SUCCEEDED,
          IcebergEncryptionDecision.Reason.NO_POLICY,
          null,
          null,
          IcebergEncryptionDecision.KmsValidationStatus.NOT_ATTEMPTED,
          null);
    }
    if (!content.required()) {
      return new IcebergEncryptionDecision(
          decisionId,
          IcebergEncryptionDecision.Compliance.COMPLIANT,
          IcebergEncryptionDecision.Outcome.SUCCEEDED,
          IcebergEncryptionDecision.Reason.COMPLIANT,
          policy,
          enforcement,
          IcebergEncryptionDecision.KmsValidationStatus.NOT_ATTEMPTED,
          null);
    }
    return policyViolation(
        decisionId, policy, enforcement, IcebergEncryptionDecision.Reason.KEY_REQUIRED);
  }

  private static IcebergEncryptionDecision policyViolation(
      String decisionId,
      IcebergEncryptionDecision.PolicyReference policy,
      Enforcement enforcement,
      IcebergEncryptionDecision.Reason reason) {
    IcebergEncryptionDecision.Outcome outcome =
        enforcement == Enforcement.REPORT
            ? IcebergEncryptionDecision.Outcome.SUCCEEDED
            : IcebergEncryptionDecision.Outcome.DENIED;
    return new IcebergEncryptionDecision(
        decisionId,
        IcebergEncryptionDecision.Compliance.VIOLATION,
        outcome,
        reason,
        policy,
        enforcement,
        IcebergEncryptionDecision.KmsValidationStatus.NOT_ATTEMPTED,
        null);
  }

  private static IcebergEncryptionDecision hardRejection(
      String decisionId,
      @Nullable IcebergEncryptionDecision.PolicyReference policy,
      @Nullable Enforcement enforcement,
      IcebergEncryptionDecision.Compliance compliance,
      IcebergEncryptionDecision.Reason reason,
      IcebergEncryptionDecision.Outcome outcome,
      IcebergEncryptionDecision.KmsValidationStatus validationStatus) {
    return new IcebergEncryptionDecision(
        decisionId, compliance, outcome, reason, policy, enforcement, validationStatus, null);
  }

  private String nextDecisionId() {
    String decisionId = decisionIdSupplier.get();
    Preconditions.checkArgument(StringUtils.isNotBlank(decisionId), "decisionId cannot be blank");
    return decisionId;
  }

  /** Metadata-only KMS validation boundary injected into the pure evaluator. */
  @FunctionalInterface
  public interface KmsKeyValidator {

    /**
     * Validates that a same-catalog-provider key exists, is enabled, and supports encryption.
     *
     * @param key exact {@code {provider, keyId}} selected by the request
     * @return terminal validation status; never {@code NOT_ATTEMPTED}
     */
    IcebergEncryptionDecision.KmsValidationStatus validate(KmsReference key);
  }
}
