/*
 * Copyright 2026 Datastrato Pvt Ltd.
 */
package com.datastrato.gravitino.encryption;

import com.google.common.base.Preconditions;
import javax.annotation.Nullable;
import org.apache.commons.lang3.StringUtils;
import org.apache.gravitino.encryption.kms.KmsReference;
import org.apache.gravitino.meta.PolicyEntity;
import org.apache.gravitino.policy.IcebergEncryptionContent;
import org.apache.gravitino.policy.Policy;

/** Immutable result of evaluating an Iceberg table-create request for governed encryption. */
public final class IcebergEncryptionDecision {

  private final String decisionId;
  private final Compliance compliance;
  private final Outcome outcome;
  private final Reason reason;
  @Nullable private final PolicyReference policy;
  @Nullable private final IcebergEncryptionContent.Enforcement enforcement;
  private final KmsValidationStatus kmsValidationStatus;
  @Nullable private final KmsReference validatedKey;

  IcebergEncryptionDecision(
      String decisionId,
      Compliance compliance,
      Outcome outcome,
      Reason reason,
      @Nullable PolicyReference policy,
      @Nullable IcebergEncryptionContent.Enforcement enforcement,
      KmsValidationStatus kmsValidationStatus,
      @Nullable KmsReference validatedKey) {
    Preconditions.checkArgument(StringUtils.isNotBlank(decisionId), "decisionId cannot be blank");
    Preconditions.checkArgument(compliance != null, "compliance cannot be null");
    Preconditions.checkArgument(outcome != null, "outcome cannot be null");
    Preconditions.checkArgument(reason != null, "reason cannot be null");
    Preconditions.checkArgument(kmsValidationStatus != null, "kmsValidationStatus cannot be null");
    this.decisionId = decisionId;
    this.compliance = compliance;
    this.outcome = outcome;
    this.reason = reason;
    this.policy = policy;
    this.enforcement = enforcement;
    this.kmsValidationStatus = kmsValidationStatus;
    this.validatedKey = validatedKey;

    Preconditions.checkArgument(
        (policy == null) == (enforcement == null),
        "policy and enforcement must either both be present or both be absent");
    Preconditions.checkArgument(
        validatedKey == null || kmsValidationStatus == KmsValidationStatus.VALID,
        "a validated key must have valid KMS status");
    Preconditions.checkArgument(
        kmsValidationStatus != KmsValidationStatus.VALID || validatedKey != null,
        "valid KMS status requires a validated key");
    Preconditions.checkArgument(
        validatedKey == null || outcome == Outcome.SUCCEEDED,
        "a denied or failed decision cannot contain a validated key");
  }

  /**
   * Returns the stable identifier used to correlate this decision with audit events and errors.
   *
   * @return decision identifier
   */
  public String decisionId() {
    return decisionId;
  }

  /**
   * Returns whether the request satisfies the effective policy.
   *
   * @return compliance result
   */
  public Compliance compliance() {
    return compliance;
  }

  /**
   * Returns the terminal evaluation outcome.
   *
   * @return terminal outcome
   */
  public Outcome outcome() {
    return outcome;
  }

  /**
   * Returns the stable reason for the outcome.
   *
   * @return decision reason
   */
  public Reason reason() {
    return reason;
  }

  /**
   * Returns a snapshot of the evaluated policy's identity, if a policy applied.
   *
   * @return policy reference, or {@code null} when no policy applied
   */
  @Nullable
  public PolicyReference policy() {
    return policy;
  }

  /**
   * Returns the applied enforcement mode, if a policy applied.
   *
   * @return enforcement mode, or {@code null} when no policy applied
   */
  @Nullable
  public IcebergEncryptionContent.Enforcement enforcement() {
    return enforcement;
  }

  /**
   * Returns the KMS validation status.
   *
   * @return KMS validation status
   */
  public KmsValidationStatus kmsValidationStatus() {
    return kmsValidationStatus;
  }

  /**
   * Returns the exact key identity validated for Iceberg.
   *
   * <p>Callers must forward only this validated key, never the raw table properties. A report-mode
   * policy violation may contain a validated key that is not on the policy allowlist.
   *
   * @return validated {@code {provider, keyId}}, or {@code null} when no key was validated
   */
  @Nullable
  public KmsReference validatedKey() {
    return validatedKey;
  }

  /**
   * Returns whether table creation may continue.
   *
   * @return {@code true} only for a successful decision
   */
  public boolean admitted() {
    return outcome == Outcome.SUCCEEDED;
  }

  /** Compliance of the request with an applicable encryption policy. */
  public enum Compliance {
    /** No encryption policy applied to the request. */
    NOT_APPLICABLE,

    /** The request satisfies the effective encryption policy. */
    COMPLIANT,

    /** The request violates the effective encryption policy. */
    VIOLATION
  }

  /** Terminal outcomes of encryption-policy evaluation. */
  public enum Outcome {
    /** Evaluation completed and table creation may continue. */
    SUCCEEDED,

    /** Table creation was intentionally denied. */
    DENIED,

    /** Evaluation could not produce an admissible result because a dependency was unavailable. */
    FAILED
  }

  /** Status of metadata-only KMS key validation. */
  public enum KmsValidationStatus {
    /** No validation was needed or attempted. */
    NOT_ATTEMPTED,

    /** The KMS confirmed that the key is usable for encryption. */
    VALID,

    /** The KMS rejected the key as missing or unusable for encryption. */
    INVALID,

    /** The KMS could not return a definitive validation result. */
    UNAVAILABLE
  }

  /** Stable reason codes for Iceberg encryption decisions. */
  public enum Reason {
    /** No encryption policy applied and no invalid input prevented creation. */
    NO_POLICY("NO_POLICY"),

    /** The request satisfies the effective encryption policy. */
    COMPLIANT("COMPLIANT"),

    /** A required encryption key was not supplied. */
    KEY_REQUIRED("KEY_REQUIRED"),

    /** The request did not contain a complete, nonblank provider and key identifier. */
    KEY_REFERENCE_INVALID("KEY_REFERENCE_INVALID"),

    /** The requested provider does not match the provider bound to the catalog. */
    KEY_SOURCE_MISMATCH("KEY_SOURCE_MISMATCH"),

    /** The exact provider and key identifier are not allowed by the policy. */
    KEY_NOT_ALLOWED("KEY_NOT_ALLOWED"),

    /** The configured KMS reported that the supplied key is invalid. */
    KMS_KEY_INVALID("KMS_KEY_INVALID"),

    /** The configured KMS was unavailable for validation. */
    KMS_SERVICE_UNAVAILABLE("KMS_SERVICE_UNAVAILABLE");

    private final String code;

    Reason(String code) {
      this.code = code;
    }

    /**
     * Returns the stable external representation of this reason.
     *
     * @return stable reason code
     */
    public String code() {
      return code;
    }
  }

  /** Immutable snapshot of the policy identity used for a decision. */
  public static final class PolicyReference {

    @Nullable private final Long id;
    private final String name;
    private final Policy.BuiltInType type;
    private final int contentSchemaVersion;

    private PolicyReference(
        @Nullable Long id, String name, Policy.BuiltInType type, int contentSchemaVersion) {
      Preconditions.checkArgument(name != null, "policy name cannot be null");
      Preconditions.checkArgument(type != null, "policy type cannot be null");
      this.id = id;
      this.name = name;
      this.type = type;
      this.contentSchemaVersion = contentSchemaVersion;
    }

    static PolicyReference from(PolicyEntity policy) {
      IcebergEncryptionContent content = (IcebergEncryptionContent) policy.content();
      return new PolicyReference(
          policy.id(), policy.name(), policy.policyType(), content.schemaVersion());
    }

    /**
     * Returns the policy entity ID when available.
     *
     * @return policy ID, or {@code null} when unavailable
     */
    @Nullable
    public Long id() {
      return id;
    }

    /**
     * Returns the policy name.
     *
     * @return policy name
     */
    public String name() {
      return name;
    }

    /**
     * Returns the policy type.
     *
     * @return policy type
     */
    public Policy.BuiltInType type() {
      return type;
    }

    /**
     * Returns the evaluated policy-content schema version.
     *
     * @return content schema version
     */
    public int contentSchemaVersion() {
      return contentSchemaVersion;
    }
  }
}
