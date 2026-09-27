/*
 * Copyright 2026 Datastrato Inc.
 */
package org.apache.gravitino.encryption;

import com.google.common.base.Preconditions;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.function.Function;
import java.util.function.Supplier;
import javax.annotation.Nullable;
import org.apache.commons.lang3.StringUtils;
import org.apache.gravitino.NameIdentifier;
import org.apache.gravitino.catalog.lakehouse.iceberg.IcebergConstants;
import org.apache.gravitino.encryption.EncryptionPropertyRules.FormatVersionOutcome;
import org.apache.gravitino.encryption.kms.KmsReference;
import org.apache.gravitino.exceptions.AmbiguousPolicyException;
import org.apache.gravitino.listener.api.event.IcebergEncryptionAuditInfos;
import org.apache.gravitino.meta.PolicyEntity;
import org.apache.gravitino.policy.IcebergEncryptionContent;

/**
 * Single admission decision for every governed Iceberg encryption request, whichever API received
 * it.
 *
 * <p>The service owns the whole decision: the effective encryption input, KMS-provider completion,
 * the backend's atomic-integrity capability, policy resolution including ambiguity, and, when
 * {@code gravitino.encryption.validateKeys} is true, the one metadata-only KMS key validation. The
 * format version a new encrypted table would land at is resolved and checked before any of them
 * (R2, R3, R8). It returns a typed {@link EncryptionAdmission} and never throws for a policy or KMS
 * outcome, so a caller only translates its request shape into an {@link AdmissionRequest}, forwards
 * {@link EncryptionAdmission#admittedProperties()} on success, and maps {@link
 * AdmissionFailure#kind()} to its own exception type. No policy branch belongs outside this class;
 * the property arithmetic around the decision lives in {@link EncryptionPropertyRules}.
 *
 * <p><b>A staged create: stage prepares, commit decides.</b> The stage step stores nothing, so
 * {@link Operation#STAGE} is only prepared; a refused stage is still audited, as the one record of
 * that attempt. {@link Operation#STAGED_COMMIT} is the authoritative admission, evaluated,
 * validated with the KMS at most once (only when validation is on) and audited once, because the
 * server keeps no state between the two, a client may commit {@code assert-create} without staging
 * and the policy may change in between.
 */
public final class EncryptionAdmissionService {

  private final Function<NameIdentifier, Map<String, String>> catalogProperties;
  private final Supplier<IcebergEncryptionPolicyResolver> policyResolverSupplier;
  private final IcebergEncryptionPolicyEvaluator policyEvaluator;
  private final Function<NameIdentifier, AtomicIntegrityCapability> capabilityFunction;
  private final boolean validateKeys;

  /**
   * Creates the admission service, saying whether key references are validated against the KMS.
   *
   * @param catalogProperties properties of the catalog owning the table named by a request
   * @param policyResolverSupplier lazily initialized effective-policy resolver
   * @param policyEvaluator pure decision engine owning the single KMS key validator
   * @param capabilityFunction atomic-integrity capability reported by the loaded catalog
   * @param validateKeys whether key references are inspected by the KMS before admission
   */
  public EncryptionAdmissionService(
      Function<NameIdentifier, Map<String, String>> catalogProperties,
      Supplier<IcebergEncryptionPolicyResolver> policyResolverSupplier,
      IcebergEncryptionPolicyEvaluator policyEvaluator,
      Function<NameIdentifier, AtomicIntegrityCapability> capabilityFunction,
      boolean validateKeys) {
    Preconditions.checkArgument(catalogProperties != null, "catalogProperties cannot be null");
    Preconditions.checkArgument(
        policyResolverSupplier != null, "policyResolverSupplier cannot be null");
    Preconditions.checkArgument(policyEvaluator != null, "policyEvaluator cannot be null");
    Preconditions.checkArgument(capabilityFunction != null, "capabilityFunction cannot be null");
    this.catalogProperties = catalogProperties;
    this.policyResolverSupplier = policyResolverSupplier;
    this.policyEvaluator = policyEvaluator;
    this.capabilityFunction = capabilityFunction;
    this.validateKeys = validateKeys;
  }

  /**
   * Admits or refuses one terminal mutation, making at most one KMS call: none for {@link
   * Operation#STAGE}, or when server-side key validation is off.
   *
   * @param request the translated request
   * @return the admission, with admitted properties and audit facts built once
   */
  public EncryptionAdmission admit(AdmissionRequest request) {
    Preconditions.checkArgument(request != null, "request cannot be null");

    Map<String, String> catalogProps = catalogProperties.apply(request.table());
    Map<String, String> properties = catalogProps == null ? Collections.emptyMap() : catalogProps;
    String kmsSource = properties.get(IcebergConstants.ENCRYPTION_KMS_SOURCE);
    Map<String, String> effective =
        EncryptionPropertyRules.effectiveProperties(
            request.operation(), request.requestedProperties(), properties, kmsSource);

    // R8: the version rule depends on neither the policy nor the KMS, so it is answered first.
    FormatVersionOutcome versionOutcome =
        EncryptionPropertyRules.formatVersionOutcome(request.operation(), effective, properties);
    if (versionOutcome instanceof FormatVersionOutcome.Deny deny) {
      return deniedBeforeEvaluation(
          request,
          effective,
          FailureKind.UNSUPPORTED_FORMAT_VERSION,
          IcebergEncryptionAuditInfos.Reason.UNSUPPORTED_FORMAT_VERSION,
          deny.message());
    }

    Optional<String> keyViolation =
        EncryptionPropertyRules.stagedCommitKeyViolation(
            request.operation(), properties, effective);
    if (keyViolation.isPresent()) {
      return deniedBeforeEvaluation(
          request,
          effective,
          FailureKind.INVALID_REFERENCE,
          IcebergEncryptionAuditInfos.Reason.KEY_REFERENCE_INVALID,
          keyViolation.get());
    }

    // R6: written only where the request named none, and only once a key survives evaluation.
    Integer resolvedFormatVersion =
        versionOutcome instanceof FormatVersionOutcome.ForwardWriting write
            ? write.resolved()
            : null;

    boolean encryptionInput = EncryptionPropertyRules.hasEncryptionInput(effective);

    Optional<PolicyEntity> policy;
    try {
      policy = resolvePolicy(request.table());
    } catch (AmbiguousPolicyException e) {
      return ambiguousPolicy(e, effective);
    }

    if (!policy.isPresent() && !encryptionInput) {
      // Nothing governs this request: it is forwarded exactly as it arrived, with no KMS call and
      // no audit facts, so an ungoverned catalog behaves as it did before encryption governance.
      return new EncryptionAdmission(
          Outcome.ADMITTED_UNGOVERNED,
          request.requestedProperties(),
          null,
          Collections.emptyMap(),
          null);
    }

    AtomicIntegrityCapability capability = capabilityFunction.apply(request.table());
    if (!capability.supported() && (encryptionInput || policyRequiresEncryption(policy))) {
      return unsupportedBackend(request, properties, policy, effective);
    }

    // Preflight runs every check except the KMS lookup. A stage publishes nothing yet, and by
    // default the key is the engine's KMS client's to check, at first write.
    Map<String, String> input = Collections.unmodifiableMap(effective);
    IcebergEncryptionDecision decision =
        request.operation() == Operation.STAGE || !validateKeys
            ? policyEvaluator.preflight(policy, kmsSource, input)
            : policyEvaluator.evaluate(policy, kmsSource, input);
    return decide(
        request, policy, decision, effective, resolvedFormatVersion, capability, validateKeys);
  }

  /**
   * Mints a decision identifier from the generator every admission this service uses, so an API
   * edge that refuses a request on its own is correlated with its audit record the same way.
   *
   * @return a fresh decision identifier
   */
  public String nextDecisionId() {
    return policyEvaluator.nextDecisionId();
  }

  /**
   * Mints a decision identifier where no admission service exists, from the evaluator's default
   * generator; being static, it ignores any decision-id supplier injected into an evaluator.
   *
   * @return a fresh decision identifier
   */
  public static String newDecisionId() {
    return IcebergEncryptionPolicyEvaluator.newDecisionId();
  }

  /**
   * Returns audit extras that also name the exception an API raised for a refusal.
   *
   * @param auditExtras extras built by {@link #admit(AdmissionRequest)}
   * @param error the exception the API is about to throw
   * @return extras carrying the error type
   */
  public static Map<String, String> auditExtrasWithError(
      Map<String, String> auditExtras, Exception error) {
    Preconditions.checkArgument(auditExtras != null, "auditExtras cannot be null");
    Preconditions.checkArgument(error != null, "error cannot be null");
    Map<String, String> extras = new LinkedHashMap<>(auditExtras);
    extras.put(IcebergEncryptionAuditInfos.ERROR_TYPE, error.getClass().getName());
    return Collections.unmodifiableMap(extras);
  }

  /**
   * Returns audit extras for a physical create that failed after admission succeeded.
   *
   * @param auditExtras extras built by {@link #admit(AdmissionRequest)}
   * @param error the failure raised by the underlying create
   * @return extras reporting {@code TABLE_CREATE_FAILED}
   */
  public static Map<String, String> auditExtrasForCreateFailure(
      Map<String, String> auditExtras, Exception error) {
    Map<String, String> extras = new LinkedHashMap<>(auditExtrasWithError(auditExtras, error));
    extras.put(
        IcebergEncryptionAuditInfos.REASON,
        IcebergEncryptionAuditInfos.Reason.TABLE_CREATE_FAILED.code());
    return Collections.unmodifiableMap(extras);
  }

  private Optional<PolicyEntity> resolvePolicy(NameIdentifier table) {
    IcebergEncryptionPolicyResolver resolver =
        Preconditions.checkNotNull(
            policyResolverSupplier.get(), "policyResolverSupplier returned null");
    return resolver.resolve(table);
  }

  /**
   * Refuses a request on a rule answered before the evaluator runs: the format version (R2, R3, R9)
   * or the key a staged commit publishes. The identifier still comes from the evaluator's
   * generator, so message, log and audit record name the same string wherever the refusal was
   * raised; the record carries no policy fact because no policy took part.
   */
  private EncryptionAdmission deniedBeforeEvaluation(
      AdmissionRequest request,
      Map<String, String> effective,
      FailureKind kind,
      IcebergEncryptionAuditInfos.Reason reason,
      String explanation) {
    String decisionId = nextDecisionId();
    IcebergEncryptionAuditInfos.Builder extras =
        IcebergEncryptionAuditInfos.builder().withDecisionId(decisionId).withReason(reason);
    if (validateKeys) {
      extras.withKmsValidation(IcebergEncryptionAuditInfos.KmsValidationStatus.NOT_ATTEMPTED);
    }
    KmsReference key = EncryptionPropertyRules.providerKey(effective);
    if (key != null) {
      extras.withProviderKey(key);
    }
    // The reason code travels in the client-visible message, not only in the audit record: it is
    // what lets an operator join the error an engine reported to the line the audit log wrote.
    String message =
        String.format(
            "%s. table=%s, decisionId=%s, reason=%s",
            explanation, request.table(), decisionId, reason.code());
    return new EncryptionAdmission(
        Outcome.DENIED,
        Collections.emptyMap(),
        null,
        extras.build(),
        new AdmissionFailure(kind, decisionId, message, Collections.emptyList()));
  }

  private static boolean policyRequiresEncryption(Optional<PolicyEntity> policy) {
    return policy
        .map(PolicyEntity::content)
        .filter(IcebergEncryptionContent.class::isInstance)
        .map(content -> ((IcebergEncryptionContent) content).required())
        .orElse(false);
  }

  private EncryptionAdmission ambiguousPolicy(
      AmbiguousPolicyException e, Map<String, String> effective) {
    String decisionId = policyEvaluator.nextDecisionId();
    IcebergEncryptionAuditInfos.Builder extras =
        IcebergEncryptionAuditInfos.builder()
            .withDecisionId(decisionId)
            .withPolicyNames(e.matchedPolicyNames())
            .withPolicyEvaluation(
                IcebergEncryptionAuditInfos.Compliance.VIOLATION,
                IcebergEncryptionContent.Enforcement.DENY_CREATE)
            .withReason(IcebergEncryptionAuditInfos.Reason.AMBIGUOUS_POLICY);
    KmsReference key = EncryptionPropertyRules.providerKey(effective);
    if (key != null) {
      extras.withProviderKey(key);
    }
    return new EncryptionAdmission(
        Outcome.DENIED,
        Collections.emptyMap(),
        null,
        extras.build(),
        new AdmissionFailure(
            FailureKind.AMBIGUOUS_POLICY,
            decisionId,
            String.format("%s decisionId=%s", e.getMessage(), decisionId),
            List.of(e.matchedPolicyNames())));
  }

  private EncryptionAdmission unsupportedBackend(
      AdmissionRequest request,
      Map<String, String> catalogProperties,
      Optional<PolicyEntity> policy,
      Map<String, String> effective) {
    String decisionId = policyEvaluator.nextDecisionId();
    String backend = catalogProperties.get(IcebergConstants.CATALOG_BACKEND);
    IcebergEncryptionAuditInfos.Builder extras =
        IcebergEncryptionAuditInfos.builder()
            .withDecisionId(decisionId)
            .withReason(IcebergEncryptionAuditInfos.Reason.OPERATION_FAILED);
    if (validateKeys) {
      extras.withKmsValidation(IcebergEncryptionAuditInfos.KmsValidationStatus.NOT_ATTEMPTED);
    }
    // The backend is refused before the evaluator runs, so there is no policy verdict to record.
    policy.ifPresent(matched -> extras.withPolicyName(matched.name()));
    KmsReference key = EncryptionPropertyRules.providerKey(effective);
    if (key != null) {
      extras.withProviderKey(key);
    }
    // Backend-agnostic on purpose: which database products carry an integrity store is not a
    // governance fact, and a supported product whose store is missing reaches the same message.
    String message =
        String.format(
            "Governed Iceberg encryption requires a catalog that publishes the Iceberg metadata "
                + "pointer and its integrity hash atomically; integrity store unavailable: "
                + "table=%s, catalog-backend=%s, decisionId=%s",
            request.table(), backend == null ? "<unknown>" : backend, decisionId);
    return new EncryptionAdmission(
        Outcome.DENIED,
        Collections.emptyMap(),
        null,
        extras.build(),
        new AdmissionFailure(
            FailureKind.UNSUPPORTED_BACKEND, decisionId, message, Collections.emptyList()));
  }

  private static EncryptionAdmission decide(
      AdmissionRequest request,
      Optional<PolicyEntity> policy,
      IcebergEncryptionDecision decision,
      Map<String, String> effective,
      @Nullable Integer resolvedFormatVersion,
      AtomicIntegrityCapability capability,
      boolean validateKeys) {
    Map<String, String> auditExtras = auditExtras(policy, decision, effective, validateKeys);
    if (decision.admitted()) {
      Outcome outcome =
          policy.isPresent() && capability.supported()
              ? Outcome.ADMITTED_GOVERNED
              : Outcome.ADMITTED_UNGOVERNED;
      if (request.operation() == Operation.STAGE) {
        return new EncryptionAdmission(
            outcome,
            EncryptionPropertyRules.admittedProperties(
                effective, EncryptionPropertyRules.providerKey(effective), resolvedFormatVersion),
            decision,
            Collections.emptyMap(),
            null);
      }
      return new EncryptionAdmission(
          outcome,
          EncryptionPropertyRules.admittedProperties(
              effective,
              validateKeys
                  ? decision.validatedKey()
                  : EncryptionPropertyRules.providerKey(effective),
              resolvedFormatVersion),
          decision,
          auditExtras,
          null);
    }

    FailureKind kind = failureKind(decision.reason());
    String message = failureMessage(request.table(), decision, effective, kind);
    return new EncryptionAdmission(
        kind == FailureKind.KMS_UNAVAILABLE ? Outcome.UNAVAILABLE : Outcome.DENIED,
        Collections.emptyMap(),
        decision,
        auditExtras,
        new AdmissionFailure(kind, decision.decisionId(), message, Collections.emptyList()));
  }

  private static FailureKind failureKind(IcebergEncryptionDecision.Reason reason) {
    switch (reason) {
      case KEY_REFERENCE_INVALID:
      case KEY_SOURCE_MISMATCH:
        return FailureKind.INVALID_REFERENCE;
      case KEY_REQUIRED:
      case KEY_NOT_ALLOWED:
        return FailureKind.POLICY_DENIED;
      case KMS_KEY_INVALID:
        return FailureKind.KMS_KEY_INVALID;
      case KMS_SERVICE_UNAVAILABLE:
        return FailureKind.KMS_UNAVAILABLE;
      default:
        throw new IllegalStateException("Unadmitted decision has no failure reason: " + reason);
    }
  }

  private static String failureMessage(
      NameIdentifier table,
      IcebergEncryptionDecision decision,
      Map<String, String> effective,
      FailureKind kind) {
    String provider = effective.get(IcebergConstants.ENCRYPTION_KEY_PROVIDER);
    String keyId = effective.get(IcebergConstants.ENCRYPTION_KEY_ID);
    switch (kind) {
      case KMS_KEY_INVALID:
        return String.format(
            "KMS key '%s:%s' is not valid for encryption. Verify that the key exists, is enabled, "
                + "and supports encryption. table=%s, decisionId=%s, reason=%s",
            provider, keyId, table, decision.decisionId(), decision.reason().code());
      case KMS_UNAVAILABLE:
        return String.format(
            "KMS provider '%s' is unavailable; KMS key '%s' could not be validated. Retry later. "
                + "table=%s, decisionId=%s, reason=%s",
            provider, keyId, table, decision.decisionId(), decision.reason().code());
      default:
        return String.format(
            "Iceberg encryption denied table creation: table=%s, decisionId=%s, reason=%s",
            table, decision.decisionId(), decision.reason().code());
    }
  }

  /**
   * Without the optional server-side check the record carries no KMS-validation field at all: "not
   * attempted" on every record would read as a check that failed to run.
   */
  private static Map<String, String> auditExtras(
      Optional<PolicyEntity> policy,
      IcebergEncryptionDecision decision,
      Map<String, String> effective,
      boolean validateKeys) {
    IcebergEncryptionAuditInfos.Builder extras =
        IcebergEncryptionAuditInfos.builder()
            .withDecisionId(decision.decisionId())
            .withReason(decision.reason());
    policy.ifPresent(matched -> extras.withPolicyName(matched.name()));
    if (decision.enforcement() != null
        && decision.compliance() != IcebergEncryptionDecision.Compliance.NOT_APPLICABLE) {
      extras.withPolicyEvaluation(decision.compliance(), decision.enforcement());
    }
    KmsReference key = EncryptionPropertyRules.providerKey(effective);
    if (key == null) {
      key = decision.validatedKey();
    }
    if (key != null) {
      extras.withProviderKey(key);
    }
    if (validateKeys) {
      extras.withKmsValidation(decision.kmsValidationStatus());
    }
    return extras.build();
  }

  /** The terminal mutation an admission decides. */
  public enum Operation {
    /** An immediate create. */
    CREATE,

    /**
     * The stage step of a staged create, which stores nothing and is prepared rather than admitted:
     * the same key merge, format version rule, capability and policy checks as {@link #CREATE}, but
     * no KMS call and, when prepared, no audit facts. Under {@code report} a policy violation never
     * refuses and records nothing here.
     */
    STAGE,

    /**
     * The {@code assert-create} commit that publishes a staged create: the authoritative admission,
     * with the policy, the KMS validation when {@code gravitino.encryption.validateKeys} is true,
     * and the one audit record. Its key is checked against the catalog's rather than merged ({@link
     * EncryptionPropertyRules#stagedCommitKeyViolation}), and the {@code format-version} it is held
     * to is the highest {@code upgrade-format-version} the commit carries, refused before the KMS
     * rather than raised.
     */
    STAGED_COMMIT,

    /** Registration of an existing Iceberg metadata file. */
    REGISTER,

    /** Re-registration of a table over an existing metadata pointer. */
    OVERWRITE
  }

  /** Terminal admission outcomes. */
  public enum Outcome {
    /** Admitted under an effective policy the backend can enforce. */
    ADMITTED_GOVERNED,

    /** Admitted with inherited ungoverned semantics. */
    ADMITTED_UNGOVERNED,

    /** Refused deliberately. */
    DENIED,

    /** Undecidable because a dependency could not be reached. */
    UNAVAILABLE
  }

  /** The whole failure vocabulary a response and an audit record share; no API edge adds to it. */
  public enum FailureKind {
    /** More than one encryption policy applied where exactly one is required. */
    AMBIGUOUS_POLICY,

    /** The catalog cannot publish the metadata pointer and its integrity hash atomically. */
    UNSUPPORTED_BACKEND,

    /** The key reference is incomplete, malformed, or names another KMS than the catalog. */
    INVALID_REFERENCE,

    /** The effective policy refuses the request. */
    POLICY_DENIED,

    /** The configured KMS reported the key as missing or unusable. */
    KMS_KEY_INVALID,

    /** The configured KMS could not be reached to validate the key. */
    KMS_UNAVAILABLE,

    /**
     * A key applies to a new table and the version it would land at, the catalog's own declaration
     * or the catalog's override pin is below 3 (R3, R9, R10).
     */
    UNSUPPORTED_FORMAT_VERSION
  }

  /**
   * One terminal mutation, translated out of an API's own request shape.
   *
   * <p>{@code table} is the table the mutation targets and {@code requestedProperties} are the
   * properties exactly as the request carried them.
   *
   * <p>{@code operation} is load-bearing, not a label: it decides which key the request takes
   * ({@link EncryptionPropertyRules#inheritsCatalogKeys}), whether a KMS call is made (together
   * with the server's {@code validateKeys} setting) and whether a prepared request carries audit
   * facts. An API that names the wrong operation changes the decision, which is why the choice is
   * one enum on the request rather than a flag each caller has to get right.
   */
  public record AdmissionRequest(
      NameIdentifier table, Operation operation, Map<String, String> requestedProperties) {

    /** Validates the request. */
    public AdmissionRequest {
      Preconditions.checkArgument(table != null, "table cannot be null");
      Preconditions.checkArgument(operation != null, "operation cannot be null");
      Preconditions.checkArgument(
          requestedProperties != null, "requestedProperties cannot be null");
    }
  }

  /**
   * The admission decision, with everything an API edge needs and nothing it has to recompute:
   * {@code admittedProperties} is what to forward on success, empty on refusal; {@code decision} is
   * {@code null} when nothing was evaluated; {@code auditExtras} is empty when the request was
   * never governed; {@code failure} is {@code null} when the request was admitted.
   */
  public record EncryptionAdmission(
      Outcome outcome,
      Map<String, String> admittedProperties,
      IcebergEncryptionDecision decision,
      Map<String, String> auditExtras,
      AdmissionFailure failure) {

    /** Validates the admission. */
    public EncryptionAdmission {
      Preconditions.checkArgument(outcome != null, "outcome cannot be null");
      Preconditions.checkArgument(admittedProperties != null, "admittedProperties cannot be null");
      Preconditions.checkArgument(auditExtras != null, "auditExtras cannot be null");
      Preconditions.checkArgument(
          (failure == null)
              == (outcome == Outcome.ADMITTED_GOVERNED || outcome == Outcome.ADMITTED_UNGOVERNED),
          "a refused admission must carry a failure and an admitted one must not");
    }

    /**
     * Returns whether the mutation may proceed.
     *
     * @return {@code true} for either admitted outcome
     */
    public boolean admitted() {
      return failure == null;
    }

    /**
     * Returns whether anything was evaluated at all; when nothing was, an API must pass its own
     * request through rather than the admitted properties.
     *
     * @return {@code true} unless the request was forwarded untouched
     */
    public boolean evaluated() {
      return decision != null || failure != null;
    }
  }

  /**
   * A refusal, in the vocabulary every API and audit record shares. {@code decisionId} correlates
   * it with its audit record and is already in the operator-facing {@code message}; {@code
   * matchedPolicyNames} is empty for every kind but {@link FailureKind#AMBIGUOUS_POLICY}.
   */
  public record AdmissionFailure(
      FailureKind kind, String decisionId, String message, List<String> matchedPolicyNames) {

    /** Validates the failure. */
    public AdmissionFailure {
      Preconditions.checkArgument(kind != null, "kind cannot be null");
      Preconditions.checkArgument(StringUtils.isNotBlank(decisionId), "decisionId cannot be blank");
      Preconditions.checkArgument(StringUtils.isNotBlank(message), "message cannot be blank");
      Preconditions.checkArgument(matchedPolicyNames != null, "matchedPolicyNames cannot be null");
      matchedPolicyNames = List.copyOf(matchedPolicyNames);
    }
  }
}
