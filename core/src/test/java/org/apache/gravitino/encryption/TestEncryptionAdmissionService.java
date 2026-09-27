/*
 * Copyright 2026 Datastrato Inc.
 */
package org.apache.gravitino.encryption;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.google.common.collect.ImmutableMap;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.stream.Stream;
import org.apache.gravitino.MetadataObject;
import org.apache.gravitino.MetadataObjects;
import org.apache.gravitino.NameIdentifier;
import org.apache.gravitino.catalog.lakehouse.iceberg.IcebergConstants;
import org.apache.gravitino.catalog.lakehouse.iceberg.IcebergFormatVersionResolution;
import org.apache.gravitino.encryption.EncryptionAdmissionService.AdmissionRequest;
import org.apache.gravitino.encryption.EncryptionAdmissionService.EncryptionAdmission;
import org.apache.gravitino.encryption.EncryptionAdmissionService.FailureKind;
import org.apache.gravitino.encryption.EncryptionAdmissionService.Operation;
import org.apache.gravitino.encryption.EncryptionAdmissionService.Outcome;
import org.apache.gravitino.encryption.kms.KmsReference;
import org.apache.gravitino.listener.api.event.IcebergEncryptionAuditInfos;
import org.apache.gravitino.meta.PolicyEntity;
import org.apache.gravitino.policy.IcebergEncryptionContent;
import org.apache.gravitino.policy.IcebergEncryptionContent.Enforcement;
import org.apache.gravitino.policy.Policy;
import org.apache.gravitino.policy.PolicyContents;
import org.apache.gravitino.policy.PolicyDispatcher;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.EnumSource;
import org.junit.jupiter.params.provider.MethodSource;

/**
 * Pins the one admission decision both APIs share: the merge rule and its precedence, the single
 * KMS call, the backend capability gate, and the exact failure vocabulary each outcome carries.
 *
 * <p>Every case here is a decision an API used to make for itself, so a divergence between the
 * Gravitino API and the Iceberg REST catalog can only come from an edge translation, never from the
 * decision.
 */
public class TestEncryptionAdmissionService {

  private static final String METALAKE = "metalake";
  private static final String SOURCE = "openbao";
  private static final String KEY_ID = "customer-pii-v1";
  private static final NameIdentifier TABLE =
      NameIdentifier.of(METALAKE, "catalog", "schema", "table");
  private static final MetadataObject SCHEMA =
      MetadataObjects.of("catalog", "schema", MetadataObject.Type.SCHEMA);
  private static final String UNPREFIXED_DEFAULT_KEY_ID_PROPERTY =
      "table-default." + IcebergConstants.ENCRYPTION_KEY_ID;
  private static final String UNPREFIXED_OVERRIDE_KEY_ID_PROPERTY =
      "table-override." + IcebergConstants.ENCRYPTION_KEY_ID;
  private static final String DEFAULT_KEY_ID_PROPERTY =
      "gravitino.bypass." + UNPREFIXED_DEFAULT_KEY_ID_PROPERTY;
  private static final String OVERRIDE_KEY_ID_PROPERTY =
      "gravitino.bypass." + UNPREFIXED_OVERRIDE_KEY_ID_PROPERTY;

  private PolicyDispatcher policyDispatcher;
  private Map<String, String> catalogProperties;
  private AtomicReference<IcebergEncryptionDecision.KmsValidationStatus> validationStatus;
  private AtomicReference<KmsReference> validatedKey;
  private AtomicInteger kmsCalls;
  private AtomicInteger capabilityCalls;
  private AtomicReference<AtomicIntegrityCapability> capability;
  private EncryptionAdmissionService service;

  @BeforeEach
  void setUp() {
    policyDispatcher = mock(PolicyDispatcher.class);
    catalogProperties = new HashMap<>();
    catalogProperties.put(IcebergConstants.ENCRYPTION_KMS_SOURCE, SOURCE);
    catalogProperties.put(IcebergConstants.CATALOG_BACKEND, "jdbc");
    // R3 and R9: a keyed create on a catalog that declares nothing resolves to Iceberg's default
    // of 2 and is refused, so every keyed fixture here declares the version an operator would set.
    catalogProperties.put(IcebergFormatVersionResolution.TABLE_DEFAULT_FORMAT_VERSION, "3");
    validationStatus = new AtomicReference<>(IcebergEncryptionDecision.KmsValidationStatus.VALID);
    validatedKey = new AtomicReference<>();
    kmsCalls = new AtomicInteger();
    capabilityCalls = new AtomicInteger();
    capability = new AtomicReference<>(AtomicIntegrityCapability.SUPPORTED);
    service =
        new EncryptionAdmissionService(
            table -> catalogProperties,
            () -> new IcebergEncryptionPolicyResolver(policyDispatcher, new SinglePolicyChecker()),
            new IcebergEncryptionPolicyEvaluator(
                key -> {
                  validatedKey.set(key);
                  kmsCalls.incrementAndGet();
                  return validationStatus.get();
                }),
            table -> {
              capabilityCalls.incrementAndGet();
              return capability.get();
            },
            true);
  }

  @Test
  void testNullConstructorAndRequestArgumentsAreRejected() {
    Assertions.assertThrows(
        IllegalArgumentException.class,
        () ->
            new EncryptionAdmissionService(
                null, () -> null, evaluator(), table -> capability.get(), true));
    Assertions.assertThrows(
        IllegalArgumentException.class,
        () ->
            new EncryptionAdmissionService(
                table -> catalogProperties, null, evaluator(), table -> capability.get(), true));
    Assertions.assertThrows(
        IllegalArgumentException.class,
        () ->
            new EncryptionAdmissionService(
                table -> catalogProperties, () -> null, null, table -> capability.get(), true));
    Assertions.assertThrows(
        IllegalArgumentException.class,
        () ->
            new EncryptionAdmissionService(
                table -> catalogProperties, () -> null, evaluator(), null, true));
    Assertions.assertThrows(IllegalArgumentException.class, () -> service.admit(null));
    Assertions.assertThrows(
        IllegalArgumentException.class,
        () -> new AdmissionRequest(TABLE, null, Collections.emptyMap()));
  }

  @Test
  void testNoPolicyAndNoEncryptionInputIsAdmittedUngovernedWithoutKmsOrCapability() {
    Map<String, String> requested = ImmutableMap.of("owner-property", "value");

    EncryptionAdmission admission = admit(Operation.CREATE, requested);

    Assertions.assertEquals(Outcome.ADMITTED_UNGOVERNED, admission.outcome());
    Assertions.assertTrue(admission.admitted());
    Assertions.assertFalse(admission.evaluated());
    Assertions.assertSame(requested, admission.admittedProperties());
    Assertions.assertTrue(admission.auditExtras().isEmpty());
    Assertions.assertEquals(0, kmsCalls.get());
    Assertions.assertEquals(0, capabilityCalls.get());
  }

  @Test
  void testNoPolicyWithKeyIsValidatedOnceAndAdmittedUngoverned() {
    EncryptionAdmission admission = admit(Operation.CREATE, keyProperties(KEY_ID));

    Assertions.assertEquals(Outcome.ADMITTED_UNGOVERNED, admission.outcome());
    Assertions.assertEquals(1, kmsCalls.get());
    Assertions.assertEquals(new KmsReference(SOURCE, KEY_ID), validatedKey.get());
    Assertions.assertEquals(
        IcebergEncryptionAuditInfos.Reason.NO_POLICY.code(),
        admission.auditExtras().get(IcebergEncryptionAuditInfos.REASON));
    Assertions.assertEquals(
        IcebergEncryptionAuditInfos.KmsValidationStatus.VALID.name(),
        admission.auditExtras().get(IcebergEncryptionAuditInfos.KMS_VALIDATION));
  }

  @Test
  void testTerminalOperationsAdmitWithoutTheKmsWhenValidationIsOff() {
    service = serviceWithValidationOff();

    for (Operation operation :
        List.of(
            Operation.CREATE, Operation.STAGED_COMMIT, Operation.REGISTER, Operation.OVERWRITE)) {
      // R7: a register or an overwrite takes the version of the metadata file it was handed. The
      // catalog resolves nothing for it, but the file's version still travels with the request, so
      // that a keyed file below version 3 is refused (R3) -- Iceberg registers such a file without
      // complaint. It is forwarded unchanged, never rewritten.
      boolean resolvesAVersion =
          operation == Operation.CREATE || operation == Operation.STAGED_COMMIT;
      Map<String, String> requested = new HashMap<>(keyProperties(KEY_ID));
      if (!resolvesAVersion) {
        requested.put(IcebergConstants.FORMAT_VERSION, "4");
      }
      EncryptionAdmission admission = admit(operation, requested);
      Assertions.assertTrue(admission.admitted(), operation.name());
      Assertions.assertEquals(
          SOURCE, admission.admittedProperties().get(IcebergConstants.ENCRYPTION_KEY_PROVIDER));
      Assertions.assertEquals(
          KEY_ID, admission.admittedProperties().get(IcebergConstants.ENCRYPTION_KEY_ID));
      Assertions.assertEquals(
          resolvesAVersion ? "3" : "4",
          admission.admittedProperties().get(IcebergConstants.FORMAT_VERSION),
          operation.name());
      Assertions.assertFalse(
          admission.auditExtras().containsKey(IcebergEncryptionAuditInfos.KMS_VALIDATION),
          operation.name());
    }

    EncryptionAdmission wrongSource =
        admit(
            Operation.CREATE,
            Map.of(
                IcebergConstants.ENCRYPTION_KEY_PROVIDER,
                "other",
                IcebergConstants.ENCRYPTION_KEY_ID,
                KEY_ID));
    Assertions.assertEquals(Outcome.DENIED, wrongSource.outcome());
    Assertions.assertEquals(FailureKind.INVALID_REFERENCE, wrongSource.failure().kind());

    associate(policy("deny", true, Enforcement.DENY_CREATE, "allowed-key"));
    EncryptionAdmission denied = admit(Operation.CREATE, keyProperties(KEY_ID));
    Assertions.assertEquals(FailureKind.POLICY_DENIED, denied.failure().kind());

    associate(policy("report", true, Enforcement.REPORT, "allowed-key"));
    EncryptionAdmission reported = admit(Operation.CREATE, keyProperties(KEY_ID));
    Assertions.assertTrue(reported.admitted());
    Assertions.assertEquals(
        IcebergEncryptionAuditInfos.Compliance.VIOLATION.name(),
        reported.auditExtras().get(IcebergEncryptionAuditInfos.COMPLIANCE));
    Assertions.assertEquals(
        KEY_ID, reported.admittedProperties().get(IcebergConstants.ENCRYPTION_KEY_ID));
  }

  @Test
  void testRefusalsRecordNoKmsValidationWhenValidationIsOff() {
    // Without the optional server-side check, "not attempted" on every refusal would read as a
    // check that failed to run, so the field is left out; the rest of the record names the refusal.
    service = serviceWithValidationOff();
    Map<String, String> versionTwo = new HashMap<>(keyProperties(KEY_ID));
    versionTwo.put(IcebergConstants.FORMAT_VERSION, "2");
    EncryptionAdmission refusedOnVersion = admit(Operation.CREATE, versionTwo);

    associate(policy("deny", true, Enforcement.DENY_CREATE, "allowed-key"));
    EncryptionAdmission refusedByPolicy = admit(Operation.CREATE, keyProperties(KEY_ID));

    capability.set(AtomicIntegrityCapability.UNSUPPORTED);
    EncryptionAdmission refusedOnBackend = admit(Operation.CREATE, keyProperties(KEY_ID));

    Assertions.assertEquals(
        FailureKind.UNSUPPORTED_FORMAT_VERSION, refusedOnVersion.failure().kind());
    Assertions.assertEquals(FailureKind.POLICY_DENIED, refusedByPolicy.failure().kind());
    Assertions.assertEquals(FailureKind.UNSUPPORTED_BACKEND, refusedOnBackend.failure().kind());
    for (EncryptionAdmission refusal :
        List.of(refusedOnVersion, refusedByPolicy, refusedOnBackend)) {
      assertOneDecisionId(refusal);
      Assertions.assertFalse(
          refusal.auditExtras().containsKey(IcebergEncryptionAuditInfos.KMS_VALIDATION),
          refusal.failure().kind().name());
    }
  }

  @Test
  void testCompliantKeyUnderAPolicyIsAdmittedGovernedAndSanitized() {
    associate(policy("valid", true, Enforcement.DENY_CREATE, KEY_ID));
    Map<String, String> requested = new HashMap<>(keyProperties(KEY_ID));
    requested.put("unrelated", "preserved");

    EncryptionAdmission admission = admit(Operation.CREATE, requested);

    Assertions.assertEquals(Outcome.ADMITTED_GOVERNED, admission.outcome());
    Map<String, String> admitted = admission.admittedProperties();
    Assertions.assertEquals("3", admitted.get("format-version"));
    Assertions.assertEquals(SOURCE, admitted.get(IcebergConstants.ENCRYPTION_KEY_PROVIDER));
    Assertions.assertEquals(KEY_ID, admitted.get(IcebergConstants.ENCRYPTION_KEY_ID));
    Assertions.assertEquals("preserved", admitted.get("unrelated"));
    Assertions.assertEquals(1, kmsCalls.get());
    Assertions.assertEquals(
        "valid", admission.auditExtras().get(IcebergEncryptionAuditInfos.POLICY_NAME));
    Assertions.assertEquals(
        "COMPLIANT", admission.auditExtras().get(IcebergEncryptionAuditInfos.COMPLIANCE));
  }

  @Test
  void testLeftoverProtocolPropertyIsAnInvalidReferenceRatherThanStrippedSilently() {
    Map<String, String> requested = new HashMap<>(keyProperties(KEY_ID));
    requested.put(IcebergConstants.ENCRYPTION_KMS_API, "leftover");

    EncryptionAdmission admission = admit(Operation.CREATE, requested);

    Assertions.assertEquals(FailureKind.INVALID_REFERENCE, admission.failure().kind());
    Assertions.assertEquals(0, kmsCalls.get());
  }

  @Test
  void testKeyIdOnlyCreateIsCompletedFromTheCatalogKmsSourceAndAdmitted() {
    associate(policy("create", true, Enforcement.DENY_CREATE, KEY_ID));

    EncryptionAdmission admission =
        admit(
            Operation.CREATE, Collections.singletonMap(IcebergConstants.ENCRYPTION_KEY_ID, KEY_ID));

    // Q12 under a policy: the completed provider is what the allowlist and the KMS are asked about,
    // so the key-id-only create is governed rather than merely tolerated.
    Assertions.assertEquals(Outcome.ADMITTED_GOVERNED, admission.outcome());
    Assertions.assertEquals(
        SOURCE, admission.admittedProperties().get(IcebergConstants.ENCRYPTION_KEY_PROVIDER));
    Assertions.assertEquals(new KmsReference(SOURCE, KEY_ID), validatedKey.get());
  }

  @Test
  void testKeyIdOnlyRegisterIsCompletedFromTheCatalogKmsSourceAndAdmitted() {
    associate(policy("register", true, Enforcement.DENY_CREATE, KEY_ID));

    EncryptionAdmission admission =
        admit(
            Operation.REGISTER,
            Map.of(
                IcebergConstants.ENCRYPTION_KEY_ID, KEY_ID, IcebergConstants.FORMAT_VERSION, "3"));

    Assertions.assertEquals(Outcome.ADMITTED_GOVERNED, admission.outcome());
    Assertions.assertEquals(
        KEY_ID, admission.admittedProperties().get(IcebergConstants.ENCRYPTION_KEY_ID));
    Assertions.assertEquals(1, kmsCalls.get());
  }

  @Test
  void testOverwriteNeverInheritsTheCatalogDefaultOrOverrideKey() {
    catalogProperties.put(DEFAULT_KEY_ID_PROPERTY, "catalog-default-key");
    catalogProperties.put(OVERRIDE_KEY_ID_PROPERTY, "catalog-override-key");

    // An overwrite re-admits the key the trusted metadata already carries; injecting a catalog key
    // here would silently rebind an existing table.
    EncryptionAdmission unencrypted = admit(Operation.OVERWRITE, Collections.emptyMap());
    EncryptionAdmission encrypted =
        admit(
            Operation.OVERWRITE,
            Map.of(
                IcebergConstants.ENCRYPTION_KEY_ID, KEY_ID, IcebergConstants.FORMAT_VERSION, "3"));

    Assertions.assertEquals(Outcome.ADMITTED_UNGOVERNED, unencrypted.outcome());
    Assertions.assertFalse(unencrypted.evaluated());
    Assertions.assertEquals(
        KEY_ID, encrypted.admittedProperties().get(IcebergConstants.ENCRYPTION_KEY_ID));
    Assertions.assertEquals(new KmsReference(SOURCE, KEY_ID), validatedKey.get());
    Assertions.assertEquals(1, kmsCalls.get());
  }

  @Test
  void testCatalogDefaultKeyAppliesWhenTheRequestNamesNone() {
    catalogProperties.put(DEFAULT_KEY_ID_PROPERTY, "catalog-default-key");

    EncryptionAdmission admission = admit(Operation.CREATE, Collections.emptyMap());

    Assertions.assertTrue(admission.admitted());
    Assertions.assertEquals(new KmsReference(SOURCE, "catalog-default-key"), validatedKey.get());
    Assertions.assertEquals(
        "catalog-default-key",
        admission.admittedProperties().get(IcebergConstants.ENCRYPTION_KEY_ID));
  }

  /**
   * Matrix row 2: an unkeyed catalog and a request with no key is not an encryption decision at
   * all. The version the request asked for is the version it gets, so version 2 lives on catalogs
   * without a bound key (R6).
   */
  @Test
  void testPlainVersionTwoCreateOnAnUnkeyedCatalogIsForwardedUnchanged() {
    Map<String, String> requested = ImmutableMap.of(IcebergConstants.FORMAT_VERSION, "2");

    EncryptionAdmission admission = admit(Operation.CREATE, requested);

    Assertions.assertEquals(Outcome.ADMITTED_UNGOVERNED, admission.outcome());
    Assertions.assertSame(requested, admission.admittedProperties());
    Assertions.assertEquals(0, kmsCalls.get());
  }

  /**
   * Matrix row 4 (V2): a client key with an explicit version 2 is refused, not raised, before any
   * policy lookup or KMS call, and the refusal is audited under its own decision id.
   */
  @Test
  void testClientKeyWithExplicitVersionTwoIsRefusedBeforePolicyAndKms() {
    associate(policy("deny", true, Enforcement.DENY_CREATE, KEY_ID));
    Map<String, String> requested = new HashMap<>(keyProperties(KEY_ID));
    requested.put(IcebergConstants.FORMAT_VERSION, "2");

    EncryptionAdmission admission = admit(Operation.CREATE, requested);

    assertUnsupportedFormatVersion(admission, IcebergFormatVersionResolution.Source.REQUEST);
    Assertions.assertEquals(
        KEY_ID, admission.auditExtras().get(IcebergEncryptionAuditInfos.KEY_ID));
  }

  /**
   * R7: a register or an overwrite of a keyed file at version 2 is refused on the file's own
   * version before the policy, the backend capability or the KMS is consulted, with key validation
   * on and a policy that would admit the key. The record names no policy and no verdict because
   * none took part.
   */
  @ParameterizedTest(name = "{0}")
  @EnumSource(
      value = Operation.class,
      names = {"REGISTER", "OVERWRITE"})
  void testAKeyedFileBelowVersionThreeIsRefusedBeforePolicyAndKms(Operation operation) {
    associate(policy("deny", true, Enforcement.DENY_CREATE, KEY_ID));
    Map<String, String> requested = new HashMap<>(keyProperties(KEY_ID));
    requested.put(IcebergConstants.FORMAT_VERSION, "2");

    EncryptionAdmission admission = admit(operation, requested);

    assertUnsupportedFormatVersion(admission, IcebergFormatVersionResolution.Source.FILE);
    Map<String, String> extras = admission.auditExtras();
    Assertions.assertFalse(
        extras.containsKey(IcebergEncryptionAuditInfos.POLICY_NAME), extras.toString());
    Assertions.assertFalse(
        extras.containsKey(IcebergEncryptionAuditInfos.COMPLIANCE), extras.toString());
  }

  /**
   * Matrix row 10 (V2): a catalog key and an explicit version 2 is refused the same way, whether
   * the catalog binds the key as a default or as an override, and the refusal names the catalog's
   * key.
   */
  @ParameterizedTest(name = "{0}")
  @MethodSource("catalogKeyProperties")
  void testCatalogKeyWithExplicitVersionTwoIsRefused(String catalogKeyProperty) {
    catalogProperties.put(catalogKeyProperty, "catalog-key");

    EncryptionAdmission admission =
        admit(Operation.CREATE, Collections.singletonMap(IcebergConstants.FORMAT_VERSION, "2"));

    assertUnsupportedFormatVersion(admission, IcebergFormatVersionResolution.Source.REQUEST);
    Assertions.assertEquals(
        "catalog-key", admission.auditExtras().get(IcebergEncryptionAuditInfos.KEY_ID));
  }

  private static Stream<String> catalogKeyProperties() {
    return Stream.of(DEFAULT_KEY_ID_PROPERTY, OVERRIDE_KEY_ID_PROPERTY);
  }

  /**
   * R2, R3 and R6: a catalog-bound key with no version in the request lands at the version the
   * catalog declares, and is refused when that resolves below 3 — including the catalog that
   * declares nothing and falls back to Iceberg's own default of 2. The refusal names the setting
   * that decided the version, so an operator is told which property to set.
   */
  @ParameterizedTest(name = "{0}={1}")
  @MethodSource("catalogDeclaredDefaultRows")
  void testCatalogKeyWithoutAVersionIsWrittenTheDeclaredDefaultOrRefused(
      String declaration, String declared, String written) {
    catalogProperties.remove(IcebergFormatVersionResolution.TABLE_DEFAULT_FORMAT_VERSION);
    catalogProperties.put(DEFAULT_KEY_ID_PROPERTY, "catalog-default-key");
    if (declared != null) {
      catalogProperties.put(declaration, declared);
    }

    EncryptionAdmission admission = admit(Operation.CREATE, Collections.emptyMap());

    if (written == null) {
      assertUnsupportedFormatVersion(
          admission,
          declared == null
              ? IcebergFormatVersionResolution.Source.ICEBERG_DEFAULT
              : IcebergFormatVersionResolution.Source.TABLE_DEFAULT);
    } else {
      Assertions.assertEquals(
          written, admission.admittedProperties().get(IcebergConstants.FORMAT_VERSION));
    }
  }

  /** Rows of (declaring property, declared value, version written or null for a refusal). */
  private static Stream<Arguments> catalogDeclaredDefaultRows() {
    String plain = IcebergFormatVersionResolution.TABLE_DEFAULT_FORMAT_VERSION;
    String bypass = "gravitino.bypass." + plain;
    return Stream.of(
        Arguments.of(plain, null, null),
        Arguments.of(plain, "1", null),
        Arguments.of(plain, "2", null),
        Arguments.of(plain, "3", "3"),
        Arguments.of(plain, "4", "4"),
        Arguments.of(bypass, "3", "3"));
  }

  /**
   * Matrix row 15: a {@code report} policy that requires a key and gets none records the violation
   * and admits a plain table; no key was validated, so no version is written and the catalog's own
   * {@code table-default.format-version} applies.
   */
  @Test
  void testReportPolicyWithoutAKeyAdmitsAPlainTableAtTheCatalogDefault() {
    catalogProperties.put(IcebergFormatVersionResolution.TABLE_DEFAULT_FORMAT_VERSION, "2");
    associate(policy("report", true, Enforcement.REPORT, KEY_ID));

    EncryptionAdmission admission = admit(Operation.CREATE, Collections.emptyMap());

    Assertions.assertEquals(Outcome.ADMITTED_GOVERNED, admission.outcome());
    Assertions.assertFalse(
        admission.admittedProperties().containsKey(IcebergConstants.FORMAT_VERSION));
    Assertions.assertFalse(
        admission.admittedProperties().containsKey(IcebergConstants.ENCRYPTION_KEY_ID));
    Assertions.assertEquals(
        "VIOLATION", admission.auditExtras().get(IcebergEncryptionAuditInfos.COMPLIANCE));
    Assertions.assertEquals(0, kmsCalls.get());
  }

  /**
   * The SPIP's create-time grids: no policy, Audit ({@code report}) and Enforce ({@code
   * deny-create}), each with no key, an allow-listed key and a key off the list, against no version
   * and explicit versions 2, 3 and 4, on a catalog that declares no default format version and on
   * one that declares 3. A refusal on version grounds is always made before any KMS call.
   */
  @ParameterizedTest(name = "D={0} policy={1} key={2} version={3}")
  @MethodSource("spipCreateGridCells")
  void testSpipCreateGrids(
      String declaredDefault, String policyKind, String keyKind, String version) {
    catalogProperties.remove(IcebergFormatVersionResolution.TABLE_DEFAULT_FORMAT_VERSION);
    if (declaredDefault != null) {
      catalogProperties.put(
          IcebergFormatVersionResolution.TABLE_DEFAULT_FORMAT_VERSION, declaredDefault);
    }
    if (!policyKind.equals("none")) {
      associate(
          policy(
              policyKind,
              true,
              policyKind.equals("audit") ? Enforcement.REPORT : Enforcement.DENY_CREATE,
              "allowed-key"));
    }
    Map<String, String> requested = new HashMap<>();
    if (!keyKind.equals("none")) {
      requested.putAll(keyProperties(keyKind.equals("allowed") ? "allowed-key" : KEY_ID));
    }
    if (version != null) {
      requested.put(IcebergConstants.FORMAT_VERSION, version);
    }

    EncryptionAdmission admission = admit(Operation.CREATE, requested);

    String expected = expectedGridCell(declaredDefault, policyKind, keyKind, version);
    Assertions.assertEquals(expected, gridCell(admission));
    if (expected.equals("UFV")) {
      Assertions.assertEquals(0, kmsCalls.get());
      Assertions.assertEquals(0, capabilityCalls.get());
    }
    if (policyKind.equals("audit") && expected.startsWith("admitted")) {
      boolean compliant = keyKind.equals("allowed");
      Assertions.assertEquals(
          compliant ? "COMPLIANT" : "VIOLATION",
          admission.auditExtras().get(IcebergEncryptionAuditInfos.COMPLIANCE));
    }
  }

  private static Stream<Arguments> spipCreateGridCells() {
    return Stream.of(null, "3")
        .flatMap(
            declaredDefault ->
                Stream.of("none", "audit", "enforce")
                    .flatMap(
                        policyKind ->
                            Stream.of("none", "allowed", "unlisted")
                                .flatMap(
                                    keyKind ->
                                        Stream.of(null, "2", "3", "4")
                                            .map(
                                                version ->
                                                    Arguments.of(
                                                        declaredDefault,
                                                        policyKind,
                                                        keyKind,
                                                        version)))));
  }

  /**
   * The SPIP grids, cell by cell. With a key the effective version decides first (R8): the
   * request's version, else the catalog's declared default, else Iceberg's own 2, and below 3 it is
   * UFV whatever the policy says — an omitted version on an undeclared catalog included (R3).
   * Without a key there is no version rule and nothing is written. Nothing is ever raised: the
   * version admitted is the effective one.
   */
  private static String expectedGridCell(
      String declaredDefault, String policyKind, String keyKind, String version) {
    boolean keyed = !keyKind.equals("none");
    int effective =
        version != null
            ? Integer.parseInt(version)
            : declaredDefault != null ? Integer.parseInt(declaredDefault) : 2;
    if (keyed && effective < 3) {
      return "UFV";
    }
    if (policyKind.equals("enforce") && !keyKind.equals("allowed")) {
      return "POLICY_DENIED";
    }
    if (!keyed) {
      return "admitted plain " + (version == null ? "default" : version);
    }
    return "admitted encrypted " + effective;
  }

  private static String gridCell(EncryptionAdmission admission) {
    if (!admission.admitted()) {
      return admission.failure().kind() == FailureKind.UNSUPPORTED_FORMAT_VERSION
          ? "UFV"
          : admission.failure().kind().name();
    }
    Map<String, String> admitted = admission.admittedProperties();
    String version = admitted.get(IcebergConstants.FORMAT_VERSION);
    return String.format(
        "admitted %s %s",
        admitted.containsKey(IcebergConstants.ENCRYPTION_KEY_ID) ? "encrypted" : "plain",
        version == null ? "default" : version);
  }

  /**
   * R9: a catalog that binds an encryption key has to declare version 3 itself, whichever key the
   * merge put on the table, because the publishing commit rebuilds the table from the catalog's
   * properties alone. That includes the publishing commit, which inherits no catalog key but is
   * still rebuilt from the catalog. A catalog that binds no key is exempt: the client's key reaches
   * the table through the request's own updates instead (row 13c).
   */
  @ParameterizedTest(name = "{0} {1}")
  @MethodSource("catalogBoundKeyRows")
  void testACatalogThatBindsAKeyMustDeclareVersionThreeWhicheverKeyWins(
      Operation operation, String row, String catalogKeyProperty, boolean clientKey) {
    catalogProperties.remove(IcebergFormatVersionResolution.TABLE_DEFAULT_FORMAT_VERSION);
    if (catalogKeyProperty != null) {
      catalogProperties.put(catalogKeyProperty, "catalog-bound-key");
    }
    // A staged commit replays the staged metadata, into which the stage put the catalog's key.
    boolean replaysCatalogKey =
        operation == Operation.STAGED_COMMIT && !clientKey && catalogKeyProperty != null;
    Map<String, String> requested =
        new HashMap<>(
            clientKey
                ? keyProperties(KEY_ID)
                : replaysCatalogKey ? keyProperties("catalog-bound-key") : Collections.emptyMap());
    requested.put(IcebergConstants.FORMAT_VERSION, "3");

    EncryptionAdmission admission = admit(operation, requested);

    if (catalogKeyProperty == null) {
      Assertions.assertTrue(admission.admitted(), row);
      Assertions.assertEquals(
          "3", admission.admittedProperties().get(IcebergConstants.FORMAT_VERSION), row);
      Assertions.assertEquals(operation == Operation.STAGE ? 0 : 1, kmsCalls.get(), row);
      return;
    }
    assertCatalogMustDeclareVersionThree(admission);
    String message = admission.failure().message();
    Assertions.assertTrue(message.contains("catalog-bound-key"), message);
    Assertions.assertTrue(message.contains("declares no format version"), message);
  }

  private static Stream<Arguments> catalogBoundKeyRows() {
    return Stream.of(Operation.CREATE, Operation.STAGE, Operation.STAGED_COMMIT)
        .flatMap(
            operation ->
                Stream.of(
                    Arguments.of(operation, "13b default key", DEFAULT_KEY_ID_PROPERTY, false),
                    Arguments.of(
                        operation,
                        "13b override key, plain spelling",
                        UNPREFIXED_OVERRIDE_KEY_ID_PROPERTY,
                        false),
                    Arguments.of(
                        operation, "13d another client key", DEFAULT_KEY_ID_PROPERTY, true),
                    Arguments.of(operation, "13c no catalog key", null, true)));
  }

  /**
   * R10: a catalog whose {@code table-override.format-version} pins below 3 cannot host an
   * encrypted table on the paths Iceberg builds — a create, a stage and the commit that publishes a
   * staged create — even when the client explicitly asks for version 3. On a create Iceberg applies
   * the override last, and on the staged paths the same catalog would refuse the identical table on
   * create, so admitting there would let a client have by staging what it cannot have directly.
   *
   * <p>A register and an overwrite are outside it, deliberately: they publish a metadata file that
   * already exists at its own version and that the override never touched, so refusing on the
   * override's strength would deny a well-formed version 3 table.
   */
  @ParameterizedTest(name = "{0}")
  @EnumSource(Operation.class)
  void testACatalogPinnedBelowVersionThreeRefusesOnlyThePathsIcebergBuilds(Operation operation) {
    associate(policy("deny", true, Enforcement.DENY_CREATE, KEY_ID));
    catalogProperties.put(IcebergFormatVersionResolution.TABLE_OVERRIDE_FORMAT_VERSION, "2");
    Map<String, String> requested = new HashMap<>(keyProperties(KEY_ID));
    requested.put(IcebergConstants.FORMAT_VERSION, "3");

    EncryptionAdmission admission = admit(operation, requested);

    if (operation == Operation.REGISTER || operation == Operation.OVERWRITE) {
      Assertions.assertTrue(admission.admitted(), "" + operation);
      Assertions.assertEquals(
          "3", admission.admittedProperties().get(IcebergConstants.FORMAT_VERSION), "" + operation);
      Assertions.assertEquals(1, kmsCalls.get(), "" + operation);
      return;
    }
    // The refusal reports the pin, not the 3 the client asked for and not the version that would
    // otherwise have landed, and it happens before the policy, the backend and the KMS (R8).
    assertCatalogPinnedBelowVersionThree(admission, 2);
  }

  /**
   * A below-3 refusal (R3), told apart from R9 and R10 by the parenthesised source that decided the
   * version: the bare token and the property name both appear in every version refusal, so only
   * {@code (from <token>)} distinguishes them. The wording and remedies are pinned in {@code
   * TestEncryptionPropertyRules}.
   */
  private void assertUnsupportedFormatVersion(
      EncryptionAdmission admission, IcebergFormatVersionResolution.Source source) {
    String message = admission.failure().message();
    Assertions.assertTrue(message.contains("(from " + source.token() + ")"), message);
    assertRefusedOnTheVersionRule(admission);
  }

  /** The R9 refusal, told apart by the bound key it names rather than a resolution source. */
  private void assertCatalogMustDeclareVersionThree(EncryptionAdmission admission) {
    String message = admission.failure().message();
    Assertions.assertTrue(message.contains("binds encryption key"), message);
    assertRefusedOnTheVersionRule(admission);
  }

  /** The R10 refusal, told apart by the pin it reports: {@code table-override.format-version}. */
  private void assertCatalogPinnedBelowVersionThree(EncryptionAdmission admission, int pinned) {
    String message = admission.failure().message();
    Assertions.assertTrue(
        message.contains(
            IcebergFormatVersionResolution.TABLE_OVERRIDE_FORMAT_VERSION + "=" + pinned),
        message);
    assertRefusedOnTheVersionRule(admission);
  }

  /**
   * What every version refusal shares: the new kind, one decision id in the message and the audit
   * record, and nothing consulted — no policy, no backend capability, no KMS.
   */
  private void assertRefusedOnTheVersionRule(EncryptionAdmission admission) {
    Assertions.assertEquals(Outcome.DENIED, admission.outcome());
    Assertions.assertEquals(FailureKind.UNSUPPORTED_FORMAT_VERSION, admission.failure().kind());
    Assertions.assertTrue(admission.admittedProperties().isEmpty());
    String message = admission.failure().message();
    Assertions.assertTrue(message.contains("table=" + TABLE), message);
    assertOneDecisionId(admission);
    Assertions.assertEquals(
        IcebergEncryptionAuditInfos.Reason.UNSUPPORTED_FORMAT_VERSION.code(),
        admission.auditExtras().get(IcebergEncryptionAuditInfos.REASON));
    Assertions.assertEquals(
        IcebergEncryptionAuditInfos.KmsValidationStatus.NOT_ATTEMPTED.name(),
        admission.auditExtras().get(IcebergEncryptionAuditInfos.KMS_VALIDATION));
    Assertions.assertEquals(0, kmsCalls.get());
    Assertions.assertEquals(0, capabilityCalls.get());
    verifyNoInteractions(policyDispatcher);
  }

  /**
   * Matrix row 25 and R7: a register takes the metadata file it was handed. The catalog's default
   * and override key are not inherited, so a plain version 2 file stays plain at version 2 on a
   * keyed catalog, there is no key to need version 3 for, and the request that names no key at all
   * is not an encryption decision.
   */
  @Test
  void testRegisterInheritsNoCatalogKeyAndTakesTheVersionOfItsFile() {
    catalogProperties.put(DEFAULT_KEY_ID_PROPERTY, "catalog-default-key");
    catalogProperties.put(OVERRIDE_KEY_ID_PROPERTY, "catalog-override-key");
    Map<String, String> requested = ImmutableMap.of(IcebergConstants.FORMAT_VERSION, "2");

    EncryptionAdmission admission = admit(Operation.REGISTER, requested);
    EncryptionAdmission keyless = admit(Operation.REGISTER, Collections.emptyMap());

    Assertions.assertEquals(Outcome.ADMITTED_UNGOVERNED, admission.outcome());
    Assertions.assertSame(requested, admission.admittedProperties());
    Assertions.assertFalse(
        admission.admittedProperties().containsKey(IcebergConstants.ENCRYPTION_KEY_ID));
    Assertions.assertEquals(Outcome.ADMITTED_UNGOVERNED, keyless.outcome());
    Assertions.assertFalse(keyless.evaluated());
    Assertions.assertEquals(0, kmsCalls.get());
  }

  @Test
  void testCatalogOverrideBeatsTheRequestKey() {
    catalogProperties.put(OVERRIDE_KEY_ID_PROPERTY, "catalog-override-key");

    EncryptionAdmission admission = admit(Operation.CREATE, keyProperties(KEY_ID));

    Assertions.assertEquals(new KmsReference(SOURCE, "catalog-override-key"), validatedKey.get());
    Assertions.assertEquals(
        "catalog-override-key",
        admission.admittedProperties().get(IcebergConstants.ENCRYPTION_KEY_ID));
  }

  @Test
  void testStagedCommitIsAdmittedAsATerminalMutation() {
    associate(policy("staged", true, Enforcement.DENY_CREATE, KEY_ID));

    EncryptionAdmission admission =
        admit(
            Operation.STAGED_COMMIT,
            Collections.singletonMap(IcebergConstants.ENCRYPTION_KEY_ID, KEY_ID));

    Assertions.assertEquals(Outcome.ADMITTED_GOVERNED, admission.outcome());
    Assertions.assertEquals(1, kmsCalls.get());
    Assertions.assertEquals(
        IcebergEncryptionAuditInfos.Reason.COMPLIANT.code(),
        admission.auditExtras().get(IcebergEncryptionAuditInfos.REASON));
  }

  /**
   * A staged commit is not given the catalog's key, but it is checked against it. Stage admission
   * put the override into the staged metadata and the REST client replays that metadata's
   * properties in the commit; a commit that carries it is admitted with it, and the key that
   * reaches the KMS is that one.
   */
  @Test
  void testStagedCommitCarryingTheCatalogOverrideKeyIsAdmittedWithIt() {
    catalogProperties.put(UNPREFIXED_OVERRIDE_KEY_ID_PROPERTY, KEY_ID);

    EncryptionAdmission commit =
        admit(
            Operation.STAGED_COMMIT,
            Collections.singletonMap(IcebergConstants.ENCRYPTION_KEY_ID, KEY_ID));

    Assertions.assertTrue(commit.admitted());
    Assertions.assertEquals(
        KEY_ID, commit.admittedProperties().get(IcebergConstants.ENCRYPTION_KEY_ID));
    Assertions.assertEquals(new KmsReference(SOURCE, KEY_ID), validatedKey.get());
    Assertions.assertEquals(1, kmsCalls.get());
  }

  /**
   * The commit is re-admitted because the server keeps no state between stage and commit and a
   * client may commit without staging. One that lacks the override a create would have been given
   * is refused before any KMS call, rather than re-bound to a key its data may not be encrypted
   * under.
   */
  @Test
  void testStagedCommitMissingTheCatalogOverrideKeyIsInvalidReference() {
    catalogProperties.put(OVERRIDE_KEY_ID_PROPERTY, "catalog-override-key");

    EncryptionAdmission commit = admit(Operation.STAGED_COMMIT, Collections.emptyMap());

    Assertions.assertEquals(Outcome.DENIED, commit.outcome());
    Assertions.assertEquals(FailureKind.INVALID_REFERENCE, commit.failure().kind());
    String message = commit.failure().message();
    Assertions.assertTrue(message.contains("names no key"), message);
    Assertions.assertTrue(message.contains("catalog-override-key"), message);
    Assertions.assertEquals(0, kmsCalls.get(), "a lost binding is refused before the KMS is asked");
    assertOneDecisionId(commit);
    Assertions.assertEquals(
        IcebergEncryptionAuditInfos.Reason.KEY_REFERENCE_INVALID.code(),
        commit.auditExtras().get(IcebergEncryptionAuditInfos.REASON));
  }

  @Test
  void testStagedCommitCarryingAnotherKeyThanTheCatalogOverrideIsInvalidReference() {
    catalogProperties.put(OVERRIDE_KEY_ID_PROPERTY, "catalog-override-key");

    EncryptionAdmission commit =
        admit(
            Operation.STAGED_COMMIT,
            Collections.singletonMap(IcebergConstants.ENCRYPTION_KEY_ID, KEY_ID));

    Assertions.assertEquals(Outcome.DENIED, commit.outcome());
    Assertions.assertEquals(FailureKind.INVALID_REFERENCE, commit.failure().kind());
    // The message names both keys, because the operator has to see which one was published.
    Assertions.assertTrue(commit.failure().message().contains(KEY_ID), commit.failure().message());
    Assertions.assertTrue(
        commit.failure().message().contains("catalog-override-key"), commit.failure().message());
    Assertions.assertEquals(0, kmsCalls.get());
  }

  /**
   * A catalog default reaches the staged metadata through stage admission the same way an override
   * does. It does not say which key, so the commit is only required to carry one.
   */
  @Test
  void testKeylessStagedCommitOnACatalogWithADefaultKeyIsInvalidReference() {
    catalogProperties.put(DEFAULT_KEY_ID_PROPERTY, "catalog-default-key");

    EncryptionAdmission commit = admit(Operation.STAGED_COMMIT, Collections.emptyMap());

    Assertions.assertEquals(Outcome.DENIED, commit.outcome());
    Assertions.assertEquals(FailureKind.INVALID_REFERENCE, commit.failure().kind());
    Assertions.assertTrue(
        commit.failure().message().contains("staged metadata lost the catalog default key"),
        commit.failure().message());
    Assertions.assertEquals(0, kmsCalls.get());

    // A commit that carries some key satisfies a default, whichever key that is: the default names
    // a starting point, not a binding every table has to keep.
    EncryptionAdmission withOwnKey =
        admit(
            Operation.STAGED_COMMIT,
            Collections.singletonMap(IcebergConstants.ENCRYPTION_KEY_ID, KEY_ID));

    Assertions.assertTrue(withOwnKey.admitted());
    Assertions.assertEquals(
        KEY_ID, withOwnKey.admittedProperties().get(IcebergConstants.ENCRYPTION_KEY_ID));
  }

  /**
   * V2 on the commit route: an {@code assert-create} commit that carries a key and upgrades to
   * version 2 is refused as the create carrying that table would be, before the policy, the
   * capability or the KMS is consulted, rather than raised to the version encryption needs.
   */
  @Test
  void testKeyedStagedCommitAtVersionTwoIsRefusedBeforePolicyAndKms() {
    catalogProperties.put(DEFAULT_KEY_ID_PROPERTY, "catalog-default-key");
    associate(policy("deny", true, Enforcement.DENY_CREATE, KEY_ID));
    Map<String, String> committed = new HashMap<>(keyProperties(KEY_ID));
    committed.put(IcebergConstants.FORMAT_VERSION, "2");

    assertUnsupportedFormatVersion(
        admit(Operation.STAGED_COMMIT, committed), IcebergFormatVersionResolution.Source.REQUEST);
  }

  /**
   * R8: a staged commit is refused on its version before its key is checked. The commit carries
   * another key than the catalog's override and names version 2; only the version refusal may
   * answer, since the key check comes after every version rule.
   */
  @Test
  void testAStagedCommitIsRefusedOnItsVersionBeforeItsKey() {
    catalogProperties.put(OVERRIDE_KEY_ID_PROPERTY, "catalog-override-key");
    Map<String, String> committed = new HashMap<>(keyProperties(KEY_ID));
    committed.put(IcebergConstants.FORMAT_VERSION, "2");

    assertUnsupportedFormatVersion(
        admit(Operation.STAGED_COMMIT, committed), IcebergFormatVersionResolution.Source.REQUEST);
  }

  /**
   * A catalog version that is not a number is a deployment fault, not a decision about this
   * request: admission throws, naming the property as written, and consults nothing after it. A
   * malformed request version is refused instead ({@code TestEncryptionPropertyRules}).
   */
  @Test
  void testAMalformedCatalogVersionThrowsInsteadOfRefusing() {
    catalogProperties.put(IcebergFormatVersionResolution.TABLE_DEFAULT_FORMAT_VERSION, "three");

    IllegalArgumentException thrown =
        Assertions.assertThrows(
            IllegalArgumentException.class, () -> admit(Operation.CREATE, keyProperties(KEY_ID)));

    Assertions.assertTrue(
        thrown.getMessage().contains(IcebergFormatVersionResolution.TABLE_DEFAULT_FORMAT_VERSION),
        thrown.getMessage());
    Assertions.assertEquals(0, kmsCalls.get());
    Assertions.assertEquals(0, capabilityCalls.get());
    verifyNoInteractions(policyDispatcher);
  }

  /**
   * A keyed commit that names a version keeps it, even when the catalog's declared default is
   * higher: the resolved version is written only into a commit that names none (R6).
   */
  @Test
  void testKeyedStagedCommitKeepsTheVersionItNamesBelowTheCatalogDefault() {
    catalogProperties.put(IcebergFormatVersionResolution.TABLE_DEFAULT_FORMAT_VERSION, "4");
    catalogProperties.put(DEFAULT_KEY_ID_PROPERTY, "catalog-default-key");
    Map<String, String> committed = new HashMap<>(keyProperties(KEY_ID));
    committed.put(IcebergConstants.FORMAT_VERSION, "3");

    EncryptionAdmission commit = admit(Operation.STAGED_COMMIT, committed);

    Assertions.assertTrue(commit.admitted());
    Assertions.assertEquals("3", commit.admittedProperties().get(IcebergConstants.FORMAT_VERSION));
    Assertions.assertEquals(1, kmsCalls.get());

    EncryptionAdmission unnamed = admit(Operation.STAGED_COMMIT, keyProperties(KEY_ID));
    Assertions.assertEquals("4", unnamed.admittedProperties().get(IcebergConstants.FORMAT_VERSION));
  }

  @Test
  void testKeylessStagedCommitOnACatalogWithoutCatalogKeysIsUnchanged() {
    EncryptionAdmission commit = admit(Operation.STAGED_COMMIT, Collections.emptyMap());

    Assertions.assertEquals(Outcome.ADMITTED_UNGOVERNED, commit.outcome());
    Assertions.assertFalse(commit.evaluated(), "no policy and no input: nothing to decide");
    Assertions.assertEquals(0, kmsCalls.get());
  }

  /**
   * Design 4.1 and R6: a stage fills in the catalog's override key, its provider and the version
   * the catalog declares, so the staged metadata names the key at a version Iceberg accepts for it
   * — Iceberg applies no catalog default to staged metadata — with no KMS call and no audit facts.
   */
  @Test
  void testStageFillsTheCatalogKeyWithoutKmsOrAuditFacts() {
    catalogProperties.put(OVERRIDE_KEY_ID_PROPERTY, "catalog-override-key");

    EncryptionAdmission stage = admit(Operation.STAGE, Collections.emptyMap());

    Assertions.assertTrue(stage.admitted());
    Assertions.assertTrue(stage.evaluated());
    Assertions.assertEquals(
        "catalog-override-key", stage.admittedProperties().get(IcebergConstants.ENCRYPTION_KEY_ID));
    Assertions.assertEquals(
        SOURCE, stage.admittedProperties().get(IcebergConstants.ENCRYPTION_KEY_PROVIDER));
    Assertions.assertEquals("3", stage.admittedProperties().get(IcebergConstants.FORMAT_VERSION));
    Assertions.assertEquals(
        IcebergEncryptionDecision.KmsValidationStatus.NOT_ATTEMPTED,
        stage.decision().kmsValidationStatus());
    Assertions.assertTrue(stage.auditExtras().isEmpty(), "a prepared stage records nothing");
    Assertions.assertEquals(0, kmsCalls.get());
  }

  /** Design 4.2: a default key does not displace the client's key, and no KMS call is made. */
  @Test
  void testStageKeepsTheClientKeyOverTheCatalogDefaultWithoutKms() {
    catalogProperties.put(DEFAULT_KEY_ID_PROPERTY, "catalog-default-key");

    EncryptionAdmission stage =
        admit(
            Operation.STAGE, Collections.singletonMap(IcebergConstants.ENCRYPTION_KEY_ID, KEY_ID));

    Assertions.assertEquals(
        KEY_ID, stage.admittedProperties().get(IcebergConstants.ENCRYPTION_KEY_ID));
    Assertions.assertEquals(
        SOURCE, stage.admittedProperties().get(IcebergConstants.ENCRYPTION_KEY_PROVIDER));
    Assertions.assertTrue(stage.auditExtras().isEmpty());
    Assertions.assertEquals(0, kmsCalls.get());
  }

  /** Design 4.3: an enforced policy refuses a key off the list at stage, with one denial record. */
  @Test
  void testStageRefusesAKeyOffTheAllowListEarlyWithADenialRecordAndNoKms() {
    associate(policy("deny", true, Enforcement.DENY_CREATE, "allowed-key"));

    EncryptionAdmission stage = admit(Operation.STAGE, keyProperties(KEY_ID));

    Assertions.assertEquals(Outcome.DENIED, stage.outcome());
    Assertions.assertEquals(FailureKind.POLICY_DENIED, stage.failure().kind());
    assertOneDecisionId(stage);
    Assertions.assertEquals(
        IcebergEncryptionAuditInfos.Reason.KEY_NOT_ALLOWED.code(),
        stage.auditExtras().get(IcebergEncryptionAuditInfos.REASON));
    Assertions.assertEquals(0, kmsCalls.get());
  }

  /** Design 4.4: a key with an explicit version 2 is refused at stage, before policy and KMS. */
  @Test
  void testStageRefusesAnUnusableFormatVersionEarly() {
    Map<String, String> requested = new HashMap<>(keyProperties(KEY_ID));
    requested.put(IcebergConstants.FORMAT_VERSION, "2");

    EncryptionAdmission stage = admit(Operation.STAGE, requested);

    Assertions.assertEquals(FailureKind.UNSUPPORTED_FORMAT_VERSION, stage.failure().kind());
    assertOneDecisionId(stage);
    Assertions.assertEquals(0, kmsCalls.get());
  }

  /** Design 4.5: under report a stage is never refused and records nothing. */
  @Test
  void testStageUnderReportPreparesAViolatingKeyAndRecordsNothing() {
    associate(policy("report", true, Enforcement.REPORT, "allowed-key"));

    EncryptionAdmission stage = admit(Operation.STAGE, keyProperties(KEY_ID));

    Assertions.assertEquals(Outcome.ADMITTED_GOVERNED, stage.outcome());
    Assertions.assertEquals(
        KEY_ID, stage.admittedProperties().get(IcebergConstants.ENCRYPTION_KEY_ID));
    Assertions.assertTrue(stage.auditExtras().isEmpty());
    Assertions.assertEquals(0, kmsCalls.get());
  }

  /**
   * Design 4.6 at the service: the stage prepares and the commit decides, so a CTAS makes exactly
   * one KMS call and yields one set of audit facts, both at commit, and a reported violation is
   * recorded once.
   */
  @Test
  void testStageThenCommitMakesOneKmsCallAndOneAuditRecord() {
    catalogProperties.put(OVERRIDE_KEY_ID_PROPERTY, "catalog-override-key");
    associate(policy("report", true, Enforcement.REPORT, "allowed-key"));

    EncryptionAdmission stage = admit(Operation.STAGE, Collections.emptyMap());
    EncryptionAdmission commit = admit(Operation.STAGED_COMMIT, stage.admittedProperties());

    Assertions.assertTrue(stage.auditExtras().isEmpty());
    Assertions.assertEquals(Outcome.ADMITTED_GOVERNED, commit.outcome());
    Assertions.assertEquals(1, kmsCalls.get());
    Assertions.assertEquals(new KmsReference(SOURCE, "catalog-override-key"), validatedKey.get());
    Assertions.assertEquals(
        "VIOLATION", commit.auditExtras().get(IcebergEncryptionAuditInfos.COMPLIANCE));
    Assertions.assertEquals(
        IcebergEncryptionAuditInfos.Reason.KEY_NOT_ALLOWED.code(),
        commit.auditExtras().get(IcebergEncryptionAuditInfos.REASON));
  }

  /** Design 4.9: a key the KMS rejects passes the stage and is refused at commit. */
  @Test
  void testKeyTheKmsRejectsPassesTheStageAndIsRefusedAtCommit() {
    validationStatus.set(IcebergEncryptionDecision.KmsValidationStatus.INVALID);

    EncryptionAdmission stage = admit(Operation.STAGE, keyProperties(KEY_ID));
    EncryptionAdmission commit = admit(Operation.STAGED_COMMIT, stage.admittedProperties());

    Assertions.assertTrue(stage.admitted());
    Assertions.assertEquals(FailureKind.KMS_KEY_INVALID, commit.failure().kind());
    assertOneDecisionId(commit);
    Assertions.assertEquals(1, kmsCalls.get());
  }

  /** Design 4.10: the commit decides, so a policy that changed after the stage refuses it. */
  @Test
  void testPolicyChangedBetweenStageAndCommitIsDecidedAtCommit() {
    associate(policy("deny", true, Enforcement.DENY_CREATE, KEY_ID));
    EncryptionAdmission stage = admit(Operation.STAGE, keyProperties(KEY_ID));
    associate(policy("deny", true, Enforcement.DENY_CREATE, "another-key"));

    EncryptionAdmission commit = admit(Operation.STAGED_COMMIT, stage.admittedProperties());

    Assertions.assertTrue(stage.admitted());
    Assertions.assertEquals(FailureKind.POLICY_DENIED, commit.failure().kind());
    Assertions.assertEquals(0, kmsCalls.get());
  }

  /** Design 4.11: with no key anywhere and no policy a stage is forwarded untouched. */
  @Test
  void testStageOnAnUnkeyedCatalogWithoutPolicyIsForwardedUntouched() {
    Map<String, String> requested = ImmutableMap.of("write.format.default", "parquet");

    EncryptionAdmission stage = admit(Operation.STAGE, requested);

    Assertions.assertFalse(stage.evaluated());
    Assertions.assertSame(requested, stage.admittedProperties());
    Assertions.assertEquals(0, kmsCalls.get());
  }

  /** Design 4.12: a backend that cannot publish atomically is refused at stage, without KMS. */
  @Test
  void testStageOnAnUnsupportedBackendIsRefusedWithoutKms() {
    capability.set(AtomicIntegrityCapability.UNSUPPORTED);

    EncryptionAdmission stage = admit(Operation.STAGE, keyProperties(KEY_ID));

    Assertions.assertEquals(FailureKind.UNSUPPORTED_BACKEND, stage.failure().kind());
    assertOneDecisionId(stage);
    Assertions.assertEquals(0, kmsCalls.get());
  }

  /**
   * Design 4.12: a staged commit asks for the backend capability again rather than trusting the
   * stage. A backend that lost atomic integrity after the stage refuses the commit without a KMS
   * call.
   */
  @Test
  void testStagedCommitAfterTheBackendLosesAtomicIntegrityIsUnsupportedBackendWithoutKms() {
    EncryptionAdmission stage = admit(Operation.STAGE, keyProperties(KEY_ID));
    Assertions.assertTrue(stage.admitted());
    Assertions.assertEquals(0, kmsCalls.get());
    capability.set(AtomicIntegrityCapability.UNSUPPORTED);

    EncryptionAdmission commit = admit(Operation.STAGED_COMMIT, stage.admittedProperties());

    Assertions.assertEquals(Outcome.DENIED, commit.outcome());
    Assertions.assertEquals(FailureKind.UNSUPPORTED_BACKEND, commit.failure().kind());
    assertOneDecisionId(commit);
    Assertions.assertEquals(
        IcebergEncryptionAuditInfos.Reason.OPERATION_FAILED.code(),
        commit.auditExtras().get(IcebergEncryptionAuditInfos.REASON));
    Assertions.assertEquals(0, kmsCalls.get());
    Assertions.assertEquals(2, capabilityCalls.get(), "the commit asks for capability again");
  }

  @Test
  void testCapabilityFalseWithEncryptionInputIsUnsupportedBackend() {
    capability.set(AtomicIntegrityCapability.UNSUPPORTED);

    EncryptionAdmission admission = admit(Operation.CREATE, keyProperties(KEY_ID));

    Assertions.assertEquals(Outcome.DENIED, admission.outcome());
    Assertions.assertEquals(FailureKind.UNSUPPORTED_BACKEND, admission.failure().kind());
    Assertions.assertTrue(
        admission.failure().message().contains("integrity store unavailable"),
        admission.failure().message());
    Assertions.assertFalse(
        admission.failure().message().contains("PostgreSQL"), admission.failure().message());
    Assertions.assertTrue(admission.failure().message().contains("decisionId="));
    Assertions.assertEquals(0, kmsCalls.get());
    Assertions.assertEquals(
        IcebergEncryptionAuditInfos.Reason.OPERATION_FAILED.code(),
        admission.auditExtras().get(IcebergEncryptionAuditInfos.REASON));
  }

  @Test
  void testCapabilityFalseWithARequiredPolicyIsUnsupportedBackend() {
    capability.set(AtomicIntegrityCapability.UNSUPPORTED);
    associate(policy("required", true, Enforcement.REPORT, KEY_ID));

    EncryptionAdmission admission = admit(Operation.CREATE, Collections.emptyMap());

    Assertions.assertEquals(FailureKind.UNSUPPORTED_BACKEND, admission.failure().kind());
    Assertions.assertEquals(
        "required", admission.auditExtras().get(IcebergEncryptionAuditInfos.POLICY_NAME));
  }

  @ParameterizedTest(name = "{0}")
  @EnumSource(Enforcement.class)
  void testUnsupportedBackendNamesThePolicyButRecordsNoVerdict(Enforcement enforcement) {
    capability.set(AtomicIntegrityCapability.UNSUPPORTED);
    associate(policy("in-scope", true, enforcement, KEY_ID));

    EncryptionAdmission admission = admit(Operation.CREATE, keyProperties(KEY_ID));

    Assertions.assertEquals(FailureKind.UNSUPPORTED_BACKEND, admission.failure().kind());
    Map<String, String> extras = admission.auditExtras();
    Assertions.assertEquals("in-scope", extras.get(IcebergEncryptionAuditInfos.POLICY_NAME));
    Assertions.assertEquals(
        IcebergEncryptionAuditInfos.Reason.OPERATION_FAILED.code(),
        extras.get(IcebergEncryptionAuditInfos.REASON));
    Assertions.assertEquals(SOURCE, extras.get(IcebergEncryptionAuditInfos.PROVIDER));
    Assertions.assertEquals(KEY_ID, extras.get(IcebergEncryptionAuditInfos.KEY_ID));
    Assertions.assertFalse(
        extras.containsKey(IcebergEncryptionAuditInfos.COMPLIANCE), extras.toString());
    Assertions.assertFalse(
        extras.containsKey(IcebergEncryptionAuditInfos.ENFORCEMENT), extras.toString());
  }

  @Test
  void testCapabilityFalseWithAnOptionalPolicyAndNoInputIsAdmittedUngoverned() {
    capability.set(AtomicIntegrityCapability.UNSUPPORTED);
    associate(policy("optional", false, Enforcement.REPORT, KEY_ID));

    EncryptionAdmission admission = admit(Operation.CREATE, Collections.emptyMap());

    Assertions.assertEquals(Outcome.ADMITTED_UNGOVERNED, admission.outcome());
    Assertions.assertEquals(0, kmsCalls.get());
    Assertions.assertEquals(
        IcebergEncryptionAuditInfos.Reason.COMPLIANT.code(),
        admission.auditExtras().get(IcebergEncryptionAuditInfos.REASON));
  }

  @Test
  void testAmbiguousPolicyIsDeniedWithItsMatchedNames() {
    associate(
        policy("b-policy", true, Enforcement.DENY_CREATE, KEY_ID),
        policy("a-policy", true, Enforcement.DENY_CREATE, KEY_ID));

    EncryptionAdmission admission = admit(Operation.CREATE, keyProperties(KEY_ID));

    Assertions.assertEquals(Outcome.DENIED, admission.outcome());
    Assertions.assertEquals(FailureKind.AMBIGUOUS_POLICY, admission.failure().kind());
    Assertions.assertEquals(
        List.of("a-policy", "b-policy"), admission.failure().matchedPolicyNames());
    Assertions.assertTrue(admission.failure().message().contains("decisionId="));
    Assertions.assertEquals(
        "a-policy,b-policy", admission.auditExtras().get(IcebergEncryptionAuditInfos.POLICY_NAME));
    Assertions.assertEquals(
        SOURCE, admission.auditExtras().get(IcebergEncryptionAuditInfos.PROVIDER));
    Assertions.assertEquals(0, kmsCalls.get());
  }

  @Test
  void testMissingRequiredKeyUnderDenyCreateIsPolicyDenied() {
    associate(policy("deny", true, Enforcement.DENY_CREATE, KEY_ID));

    EncryptionAdmission admission = admit(Operation.CREATE, Collections.emptyMap());

    Assertions.assertEquals(Outcome.DENIED, admission.outcome());
    Assertions.assertEquals(FailureKind.POLICY_DENIED, admission.failure().kind());
    Assertions.assertTrue(admission.failure().message().contains("KEY_REQUIRED"));
    Assertions.assertEquals(admission.decision().decisionId(), admission.failure().decisionId());
    assertOneDecisionId(admission);
    Assertions.assertEquals(0, kmsCalls.get());
  }

  @Test
  void testAdmittedDecisionIdReachesTheAuditRecord() {
    associate(policy("audited", true, Enforcement.DENY_CREATE, KEY_ID));

    EncryptionAdmission admission = admit(Operation.CREATE, keyProperties(KEY_ID));

    Assertions.assertEquals(
        admission.decision().decisionId(),
        admission.auditExtras().get(IcebergEncryptionAuditInfos.DECISION_ID));
  }

  /**
   * The refusals raised before the evaluator runs used to mint their own identifier, leaving the
   * audit record with no way back to the response an operator saw. Every one of them carries one
   * identifier in the message and the audit record; the two the shared helper raises — an unusable
   * format version and a staged commit that lost the catalog's key — also report that no KMS call
   * was attempted, name the key the request carried, and record no policy fact, because no policy
   * took part.
   */
  @ParameterizedTest(name = "{0}")
  @MethodSource("failuresRaisedBeforeEvaluation")
  void testFailuresRaisedBeforeEvaluationCarryOneDecisionIdEverywhere(FailureKind kind) {
    Map<String, String> requested = new HashMap<>(keyProperties(KEY_ID));
    Operation operation = Operation.CREATE;
    switch (kind) {
      case AMBIGUOUS_POLICY:
        associate(
            policy("b-policy", true, Enforcement.DENY_CREATE, KEY_ID),
            policy("a-policy", true, Enforcement.DENY_CREATE, KEY_ID));
        break;
      case UNSUPPORTED_BACKEND:
        capability.set(AtomicIntegrityCapability.UNSUPPORTED);
        associate(policy("single", true, Enforcement.DENY_CREATE, KEY_ID));
        break;
      case UNSUPPORTED_FORMAT_VERSION:
        catalogProperties.remove(IcebergFormatVersionResolution.TABLE_DEFAULT_FORMAT_VERSION);
        requested.put(IcebergConstants.FORMAT_VERSION, "2");
        break;
      case INVALID_REFERENCE:
        catalogProperties.put(OVERRIDE_KEY_ID_PROPERTY, "catalog-override-key");
        requested.put(IcebergConstants.FORMAT_VERSION, "3");
        operation = Operation.STAGED_COMMIT;
        break;
      default:
        throw new IllegalArgumentException("not raised before evaluation: " + kind);
    }

    EncryptionAdmission admission = admit(operation, requested);

    Assertions.assertEquals(kind, admission.failure().kind());
    assertOneDecisionId(admission);
    if (kind == FailureKind.AMBIGUOUS_POLICY || kind == FailureKind.UNSUPPORTED_BACKEND) {
      return;
    }
    Assertions.assertEquals(
        IcebergEncryptionAuditInfos.KmsValidationStatus.NOT_ATTEMPTED.name(),
        admission.auditExtras().get(IcebergEncryptionAuditInfos.KMS_VALIDATION));
    Assertions.assertEquals(
        SOURCE, admission.auditExtras().get(IcebergEncryptionAuditInfos.PROVIDER));
    Assertions.assertEquals(
        KEY_ID, admission.auditExtras().get(IcebergEncryptionAuditInfos.KEY_ID));
    Assertions.assertFalse(
        admission.auditExtras().containsKey(IcebergEncryptionAuditInfos.POLICY_NAME));
  }

  private static Stream<FailureKind> failuresRaisedBeforeEvaluation() {
    return Stream.of(
        FailureKind.AMBIGUOUS_POLICY,
        FailureKind.UNSUPPORTED_BACKEND,
        FailureKind.UNSUPPORTED_FORMAT_VERSION,
        FailureKind.INVALID_REFERENCE);
  }

  @Test
  void testKeyOutsideTheAllowlistUnderDenyCreateIsPolicyDenied() {
    associate(policy("deny", true, Enforcement.DENY_CREATE, "allowed-key"));

    EncryptionAdmission admission = admit(Operation.CREATE, keyProperties(KEY_ID));

    Assertions.assertEquals(FailureKind.POLICY_DENIED, admission.failure().kind());
    Assertions.assertTrue(admission.failure().message().contains("KEY_NOT_ALLOWED"));
    Assertions.assertEquals(0, kmsCalls.get());
  }

  @Test
  void testReportModeAdmitsAViolatingKeyAfterValidatingIt() {
    associate(policy("report", true, Enforcement.REPORT, "allowed-key"));

    EncryptionAdmission admission = admit(Operation.CREATE, keyProperties(KEY_ID));

    Assertions.assertEquals(Outcome.ADMITTED_GOVERNED, admission.outcome());
    Assertions.assertEquals(1, kmsCalls.get());
    Assertions.assertEquals(
        "VIOLATION", admission.auditExtras().get(IcebergEncryptionAuditInfos.COMPLIANCE));
    Assertions.assertEquals(
        IcebergEncryptionAuditInfos.Reason.KEY_NOT_ALLOWED.code(),
        admission.auditExtras().get(IcebergEncryptionAuditInfos.REASON));
  }

  @Test
  void testKeyProviderNamingAnotherKmsIsInvalidReference() {
    Map<String, String> requested = new HashMap<>(keyProperties(KEY_ID));
    requested.put(IcebergConstants.ENCRYPTION_KEY_PROVIDER, "aws");

    EncryptionAdmission admission = admit(Operation.CREATE, requested);

    Assertions.assertEquals(Outcome.DENIED, admission.outcome());
    Assertions.assertEquals(FailureKind.INVALID_REFERENCE, admission.failure().kind());
    Assertions.assertTrue(admission.failure().message().contains("KEY_SOURCE_MISMATCH"));
    Assertions.assertEquals(0, kmsCalls.get());
  }

  @Test
  void testKeyProviderWithoutAKeyIdIsInvalidReference() {
    EncryptionAdmission admission =
        admit(
            Operation.CREATE,
            Collections.singletonMap(IcebergConstants.ENCRYPTION_KEY_PROVIDER, SOURCE));

    Assertions.assertEquals(FailureKind.INVALID_REFERENCE, admission.failure().kind());
    Assertions.assertTrue(admission.failure().message().contains("KEY_REFERENCE_INVALID"));
    Assertions.assertEquals(0, kmsCalls.get());
  }

  @Test
  void testInvalidKmsKeyIsDeniedWithTheKmsKeyInvalidKind() {
    validationStatus.set(IcebergEncryptionDecision.KmsValidationStatus.INVALID);

    EncryptionAdmission admission = admit(Operation.CREATE, keyProperties(KEY_ID));

    Assertions.assertEquals(Outcome.DENIED, admission.outcome());
    Assertions.assertEquals(FailureKind.KMS_KEY_INVALID, admission.failure().kind());
    Assertions.assertTrue(
        admission
            .failure()
            .message()
            .contains("KMS key 'openbao:customer-pii-v1' is not valid for encryption."));
    Assertions.assertEquals(1, kmsCalls.get());
    Assertions.assertEquals(
        IcebergEncryptionAuditInfos.KmsValidationStatus.INVALID.name(),
        admission.auditExtras().get(IcebergEncryptionAuditInfos.KMS_VALIDATION));
  }

  @Test
  void testUnreachableKmsIsUnavailableRatherThanDenied() {
    validationStatus.set(IcebergEncryptionDecision.KmsValidationStatus.UNAVAILABLE);

    EncryptionAdmission admission = admit(Operation.CREATE, keyProperties(KEY_ID));

    Assertions.assertEquals(Outcome.UNAVAILABLE, admission.outcome());
    Assertions.assertEquals(FailureKind.KMS_UNAVAILABLE, admission.failure().kind());
    Assertions.assertTrue(
        admission.failure().message().contains("KMS provider 'openbao' is unavailable;"));
    Assertions.assertEquals(1, kmsCalls.get());
  }

  @Test
  void testAuditExtrasCarryTheErrorTypeAnApiRaises() {
    validationStatus.set(IcebergEncryptionDecision.KmsValidationStatus.INVALID);
    EncryptionAdmission admission = admit(Operation.CREATE, keyProperties(KEY_ID));

    Map<String, String> extras =
        EncryptionAdmissionService.auditExtrasWithError(
            admission.auditExtras(), new IllegalArgumentException("rejected"));

    Assertions.assertEquals(
        IllegalArgumentException.class.getName(),
        extras.get(IcebergEncryptionAuditInfos.ERROR_TYPE));
    Assertions.assertEquals(
        IcebergEncryptionAuditInfos.Reason.KMS_KEY_INVALID.code(),
        extras.get(IcebergEncryptionAuditInfos.REASON));
  }

  @Test
  void testCreateFailureExtrasReplaceTheAdmittedReason() {
    EncryptionAdmission admission = admit(Operation.CREATE, keyProperties(KEY_ID));

    Map<String, String> extras =
        EncryptionAdmissionService.auditExtrasForCreateFailure(
            admission.auditExtras(), new RuntimeException("physical create failed"));

    Assertions.assertEquals(
        IcebergEncryptionAuditInfos.Reason.TABLE_CREATE_FAILED.code(),
        extras.get(IcebergEncryptionAuditInfos.REASON));
    Assertions.assertEquals(
        RuntimeException.class.getName(), extras.get(IcebergEncryptionAuditInfos.ERROR_TYPE));
    Assertions.assertEquals(
        IcebergEncryptionAuditInfos.KmsValidationStatus.VALID.name(),
        extras.get(IcebergEncryptionAuditInfos.KMS_VALIDATION));
  }

  private IcebergEncryptionPolicyEvaluator evaluator() {
    return new IcebergEncryptionPolicyEvaluator(
        key -> IcebergEncryptionDecision.KmsValidationStatus.VALID);
  }

  private EncryptionAdmissionService serviceWithValidationOff() {
    return new EncryptionAdmissionService(
        table -> catalogProperties,
        () -> new IcebergEncryptionPolicyResolver(policyDispatcher, new SinglePolicyChecker()),
        new IcebergEncryptionPolicyEvaluator(
            key -> {
              throw new AssertionError("KMS validator must not be called");
            }),
        table -> capability.get(),
        false);
  }

  private EncryptionAdmission admit(Operation operation, Map<String, String> properties) {
    return service.admit(new AdmissionRequest(TABLE, operation, properties));
  }

  /** One identifier, in the response message and in the audit record, for a refused admission. */
  private static void assertOneDecisionId(EncryptionAdmission admission) {
    String decisionId = admission.failure().decisionId();
    Assertions.assertEquals(
        decisionId, admission.auditExtras().get(IcebergEncryptionAuditInfos.DECISION_ID));
    Assertions.assertTrue(
        admission.failure().message().contains("decisionId=" + decisionId),
        admission.failure().message());
  }

  private void associate(PolicyEntity... policies) {
    when(policyDispatcher.listPolicyInfosForMetadataObject(METALAKE, SCHEMA)).thenReturn(policies);
  }

  private static Map<String, String> keyProperties(String keyId) {
    return ImmutableMap.of(
        IcebergConstants.ENCRYPTION_KEY_PROVIDER,
        SOURCE,
        IcebergConstants.ENCRYPTION_KEY_ID,
        keyId);
  }

  private static PolicyEntity policy(
      String name, boolean required, Enforcement enforcement, String allowedKeyId) {
    PolicyEntity policy = mock(PolicyEntity.class);
    when(policy.name()).thenReturn(name);
    when(policy.enabled()).thenReturn(true);
    when(policy.policyType()).thenReturn(Policy.BuiltInType.ICEBERG_ENCRYPTION);
    when(policy.content())
        .thenReturn(
            PolicyContents.icebergEncryption(
                IcebergEncryptionContent.CURRENT_SCHEMA_VERSION,
                required,
                Collections.singletonList(new KmsReference(SOURCE, allowedKeyId)),
                enforcement));
    return policy;
  }
}
