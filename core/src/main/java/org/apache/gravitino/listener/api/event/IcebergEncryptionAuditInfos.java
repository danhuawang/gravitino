/*
 * Copyright 2026 Datastrato Inc.
 */
package org.apache.gravitino.listener.api.event;

import com.google.common.base.Preconditions;
import com.google.common.collect.ImmutableMap;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import javax.annotation.Nullable;
import org.apache.commons.lang3.StringUtils;
import org.apache.gravitino.annotation.DeveloperApi;
import org.apache.gravitino.encryption.kms.KmsReference;
import org.apache.gravitino.policy.IcebergEncryptionContent;

/**
 * {@code customInfo} keys and builder for Iceberg encryption facts on existing table events.
 *
 * <p>SPIP B4 hangs A4 evidence on {@link OperationType#CREATE_TABLE}, {@link
 * OperationType#ALTER_TABLE}, and {@link OperationType#LOAD_TABLE}. This helper produces extras
 * only: policy name, compliance, enforcement, reason, and key identity as {@code {provider,
 * keyId}}. It never carries key material or KMS credentials.
 */
@DeveloperApi
public final class IcebergEncryptionAuditInfos {

  /** Prefix shared by every Iceberg encryption extra key. */
  public static final String PREFIX = "icebergEncryption.";

  /** Matched policy name or comma-separated names. */
  public static final String POLICY_NAME = PREFIX + "policyName";

  /** Policy compliance result. */
  public static final String COMPLIANCE = PREFIX + "compliance";

  /** Policy enforcement mode. */
  public static final String ENFORCEMENT = PREFIX + "enforcement";

  /** Stable reason code for the encryption outcome. */
  public static final String REASON = PREFIX + "reason";

  /** Named KMS provider for the supplied or observed key. */
  public static final String PROVIDER = PREFIX + "provider";

  /** Provider-native key identifier for the supplied or observed key. */
  public static final String KEY_ID = PREFIX + "keyId";

  /** Named KMS provider for the previous key binding. */
  public static final String PREVIOUS_PROVIDER = PREFIX + "previousProvider";

  /** Provider-native key identifier for the previous key binding. */
  public static final String PREVIOUS_KEY_ID = PREFIX + "previousKeyId";

  /** Named KMS provider for a blocked key-binding attempt. */
  public static final String ATTEMPTED_PROVIDER = PREFIX + "attemptedProvider";

  /** Provider-native key identifier for a blocked key-binding attempt. */
  public static final String ATTEMPTED_KEY_ID = PREFIX + "attemptedKeyId";

  /** Iceberg {@code metadata.json} location for an integrity check. Never a digest. */
  public static final String METADATA_LOCATION = PREFIX + "metadataLocation";

  /** Whether trusted metadata verification succeeded. */
  public static final String VERIFIED = PREFIX + "verified";

  /** KMS validation status when a key was checked. */
  public static final String KMS_VALIDATION = PREFIX + "kmsValidation";

  /** Exception class name for a failed action. Never the exception message. */
  public static final String ERROR_TYPE = PREFIX + "errorType";

  private IcebergEncryptionAuditInfos() {}

  /**
   * Creates a builder for encryption extras.
   *
   * @return extras builder
   */
  public static Builder builder() {
    return new Builder();
  }

  /** Policy compliance result of an applicable evaluation. */
  public enum Compliance {
    /** The request satisfies the effective policy. */
    COMPLIANT,

    /** The request violates the effective policy. */
    VIOLATION
  }

  /** Status of KMS key validation for the action. */
  public enum KmsValidationStatus {
    /** No KMS validation was attempted. */
    NOT_ATTEMPTED,

    /** The configured KMS confirmed the key is usable for encryption. */
    VALID,

    /** The configured KMS rejected the key as missing or unusable for encryption. */
    INVALID,

    /** The configured KMS could not be reached to validate the key. */
    UNAVAILABLE
  }

  /** Stable reason codes for Iceberg encryption audit extras. */
  public enum Reason {
    /** No encryption policy applied to the table request. */
    NO_POLICY("NO_POLICY"),

    /** The table request satisfies the effective encryption policy. */
    COMPLIANT("COMPLIANT"),

    /** A required encryption key was not supplied. */
    KEY_REQUIRED("KEY_REQUIRED"),

    /** The supplied key identity is incomplete or malformed. */
    KEY_REFERENCE_INVALID("KEY_REFERENCE_INVALID"),

    /** The supplied key provider does not match the catalog binding. */
    KEY_SOURCE_MISMATCH("KEY_SOURCE_MISMATCH"),

    /** The supplied key is not allowed by the effective policy. */
    KEY_NOT_ALLOWED("KEY_NOT_ALLOWED"),

    /** The configured KMS reported that the supplied key is invalid. */
    KMS_KEY_INVALID("KMS_KEY_INVALID"),

    /** KMS validation failed without a more specific provider response. */
    KMS_KEY_VALIDATION_FAILED("KMS_KEY_VALIDATION_FAILED"),

    /** The configured KMS was unavailable for validation. */
    KMS_SERVICE_UNAVAILABLE("KMS_SERVICE_UNAVAILABLE"),

    /** More than one policy applied where the prototype requires one. */
    AMBIGUOUS_POLICY("AMBIGUOUS_POLICY"),

    /** Encryption properties were successfully updated. */
    ENCRYPTION_PROPERTIES_UPDATED("ENCRYPTION_PROPERTIES_UPDATED"),

    /** A prohibited encryption-key change was denied. */
    ENCRYPTION_KEY_CHANGE_DENIED("ENCRYPTION_KEY_CHANGE_DENIED"),

    /** Physical Iceberg table creation failed after encryption checks completed. */
    TABLE_CREATE_FAILED("TABLE_CREATE_FAILED"),

    /** An encryption-key transition was observed in an Iceberg commit. */
    ENCRYPTION_KEY_UPDATE_OBSERVED("ENCRYPTION_KEY_UPDATE_OBSERVED"),

    /** Iceberg metadata passed integrity verification. */
    METADATA_INTEGRITY_VERIFIED("METADATA_INTEGRITY_VERIFIED"),

    /** Iceberg metadata failed integrity verification. */
    METADATA_INTEGRITY_FAILED("METADATA_INTEGRITY_FAILED"),

    /** The encryption action failed for a reason not represented by a narrower code. */
    OPERATION_FAILED("OPERATION_FAILED");

    private final String code;

    Reason(String code) {
      this.code = code;
    }

    /**
     * Returns the stable external code for this reason.
     *
     * @return stable reason code
     */
    public String code() {
      return code;
    }
  }

  /** Builder for immutable encryption {@code customInfo} extras. */
  public static final class Builder {

    private final List<String> policyNames = new ArrayList<>();
    @Nullable private Compliance compliance;
    @Nullable private IcebergEncryptionContent.Enforcement enforcement;
    @Nullable private Reason reason;
    @Nullable private KmsReference providerKey;
    @Nullable private KmsReference previousProviderKey;
    @Nullable private KmsReference attemptedProviderKey;
    @Nullable private String metadataLocation;
    @Nullable private Boolean verified;
    @Nullable private KmsValidationStatus kmsValidation;
    @Nullable private Class<? extends Exception> errorType;

    private Builder() {}

    /**
     * Sets a single matched policy name.
     *
     * @param policyName policy name
     * @return this builder
     */
    public Builder withPolicyName(String policyName) {
      Preconditions.checkArgument(StringUtils.isNotBlank(policyName), "policyName cannot be blank");
      this.policyNames.clear();
      this.policyNames.add(policyName);
      return this;
    }

    /**
     * Sets matched policy names. Multiple names are joined with commas.
     *
     * @param names matched policy names
     * @return this builder
     */
    public Builder withPolicyNames(String... names) {
      Preconditions.checkArgument(names != null, "policy names cannot be null");
      this.policyNames.clear();
      for (String name : names) {
        Preconditions.checkArgument(StringUtils.isNotBlank(name), "policy name cannot be blank");
        this.policyNames.add(name);
      }
      return this;
    }

    /**
     * Sets both dimensions of an applicable policy evaluation.
     *
     * @param compliance compliance result
     * @param enforcement enforcement behavior
     * @return this builder
     */
    public Builder withPolicyEvaluation(
        Compliance compliance, IcebergEncryptionContent.Enforcement enforcement) {
      Preconditions.checkArgument(compliance != null, "compliance cannot be null");
      Preconditions.checkArgument(enforcement != null, "enforcement cannot be null");
      this.compliance = compliance;
      this.enforcement = enforcement;
      return this;
    }

    /**
     * Sets the stable reason code.
     *
     * @param reason reason code
     * @return this builder
     */
    public Builder withReason(Reason reason) {
      Preconditions.checkArgument(reason != null, "reason cannot be null");
      this.reason = reason;
      return this;
    }

    /**
     * Sets the supplied or observed key identity.
     *
     * @param providerKey provider plus key id
     * @return this builder
     */
    public Builder withProviderKey(KmsReference providerKey) {
      this.providerKey = snapshot(providerKey, "providerKey");
      return this;
    }

    /**
     * Sets the previous key identity before a transition.
     *
     * @param previousProviderKey previous provider plus key id
     * @return this builder
     */
    public Builder withPreviousProviderKey(KmsReference previousProviderKey) {
      this.previousProviderKey = snapshot(previousProviderKey, "previousProviderKey");
      return this;
    }

    /**
     * Sets the attempted key identity for a blocked binding change.
     *
     * @param attemptedProviderKey attempted provider plus key id
     * @return this builder
     */
    public Builder withAttemptedProviderKey(KmsReference attemptedProviderKey) {
      this.attemptedProviderKey = snapshot(attemptedProviderKey, "attemptedProviderKey");
      return this;
    }

    /**
     * Sets the metadata location for an integrity check. Digests are not accepted here.
     *
     * @param metadataLocation Iceberg metadata location
     * @return this builder
     */
    public Builder withMetadataLocation(String metadataLocation) {
      Preconditions.checkArgument(
          StringUtils.isNotBlank(metadataLocation), "metadataLocation cannot be blank");
      this.metadataLocation = metadataLocation;
      return this;
    }

    /**
     * Sets whether trusted metadata verification succeeded.
     *
     * @param verified verification result
     * @return this builder
     */
    public Builder withVerified(boolean verified) {
      this.verified = verified;
      return this;
    }

    /**
     * Sets the KMS validation status. A status other than {@link KmsValidationStatus#NOT_ATTEMPTED}
     * requires a key identity.
     *
     * @param kmsValidation validation status
     * @return this builder
     */
    public Builder withKmsValidation(KmsValidationStatus kmsValidation) {
      Preconditions.checkArgument(kmsValidation != null, "kmsValidation cannot be null");
      this.kmsValidation = kmsValidation;
      return this;
    }

    /**
     * Records the exception class for a failed action. The message is discarded so credentials and
     * other secrets cannot leak into extras.
     *
     * @param error failure error
     * @return this builder
     */
    public Builder withError(Exception error) {
      Preconditions.checkArgument(error != null, "error cannot be null");
      this.errorType = error.getClass();
      return this;
    }

    /**
     * Builds immutable extras. Empty optional fields are omitted.
     *
     * @return encryption extras
     */
    public Map<String, String> build() {
      Preconditions.checkArgument(
          (compliance == null) == (enforcement == null),
          "compliance and enforcement must be provided together");
      Preconditions.checkArgument(
          kmsValidation == null
              || kmsValidation == KmsValidationStatus.NOT_ATTEMPTED
              || providerKey != null,
          "A KMS validation result requires a key identity");

      ImmutableMap.Builder<String, String> extras = ImmutableMap.builder();
      if (!policyNames.isEmpty()) {
        extras.put(POLICY_NAME, String.join(",", policyNames));
      }
      putIfPresent(extras, COMPLIANCE, compliance);
      if (enforcement != null) {
        extras.put(ENFORCEMENT, enforcement.name());
      }
      if (reason != null) {
        extras.put(REASON, reason.code());
      }
      putProviderKey(extras, PROVIDER, KEY_ID, providerKey);
      putProviderKey(extras, PREVIOUS_PROVIDER, PREVIOUS_KEY_ID, previousProviderKey);
      putProviderKey(extras, ATTEMPTED_PROVIDER, ATTEMPTED_KEY_ID, attemptedProviderKey);
      putIfPresent(extras, METADATA_LOCATION, metadataLocation);
      if (verified != null) {
        extras.put(VERIFIED, Boolean.toString(verified));
      }
      putIfPresent(extras, KMS_VALIDATION, kmsValidation);
      if (errorType != null) {
        extras.put(ERROR_TYPE, errorType.getName());
      }
      return extras.build();
    }

    private static KmsReference snapshot(KmsReference key, String argumentName) {
      Preconditions.checkArgument(key != null, argumentName + " cannot be null");
      return new KmsReference(key.provider(), key.keyId());
    }

    private static void putProviderKey(
        ImmutableMap.Builder<String, String> extras,
        String providerKeyName,
        String keyIdName,
        @Nullable KmsReference key) {
      if (key != null) {
        extras.put(providerKeyName, key.provider());
        extras.put(keyIdName, key.keyId());
      }
    }

    private static void putIfPresent(
        ImmutableMap.Builder<String, String> extras, String key, @Nullable Object value) {
      if (value != null) {
        extras.put(key, value.toString());
      }
    }
  }
}
