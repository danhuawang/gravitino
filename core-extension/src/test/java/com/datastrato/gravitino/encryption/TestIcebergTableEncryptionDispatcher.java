/*
 * Copyright 2026 Datastrato Inc.
 */
package com.datastrato.gravitino.encryption;

import static org.apache.gravitino.utils.NameIdentifierUtil.getCatalogIdentifier;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.same;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.datastrato.gravitino.catalog.DatastratoTableDispatcher;
import com.google.common.collect.ImmutableMap;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import org.apache.gravitino.Catalog;
import org.apache.gravitino.Config;
import org.apache.gravitino.Entity;
import org.apache.gravitino.MetadataObject;
import org.apache.gravitino.NameIdentifier;
import org.apache.gravitino.Namespace;
import org.apache.gravitino.authorization.Privilege;
import org.apache.gravitino.catalog.CatalogManager;
import org.apache.gravitino.catalog.lakehouse.iceberg.IcebergConstants;
import org.apache.gravitino.catalog.lakehouse.iceberg.IcebergFormatVersionResolution;
import org.apache.gravitino.encryption.AtomicIntegrityCapability;
import org.apache.gravitino.encryption.EncryptionAdmissionService;
import org.apache.gravitino.encryption.IcebergEncryptionDecision;
import org.apache.gravitino.encryption.IcebergEncryptionKmsKeyValidators;
import org.apache.gravitino.encryption.IcebergEncryptionPolicyEvaluator;
import org.apache.gravitino.encryption.IcebergEncryptionPolicyResolver;
import org.apache.gravitino.encryption.SinglePolicyChecker;
import org.apache.gravitino.encryption.kms.KmsClient;
import org.apache.gravitino.encryption.kms.KmsClientRegistry;
import org.apache.gravitino.encryption.kms.KmsKeyProperties;
import org.apache.gravitino.encryption.kms.KmsReference;
import org.apache.gravitino.exceptions.AmbiguousPolicyException;
import org.apache.gravitino.exceptions.ConnectionFailedException;
import org.apache.gravitino.exceptions.NoSuchMetadataObjectException;
import org.apache.gravitino.exceptions.NoSuchSchemaException;
import org.apache.gravitino.listener.api.event.IcebergEncryptionAuditInfos;
import org.apache.gravitino.listener.api.event.IcebergEncryptionAuditInfos.KmsValidationStatus;
import org.apache.gravitino.listener.api.event.IcebergEncryptionAuditInfos.Reason;
import org.apache.gravitino.meta.PolicyEntity;
import org.apache.gravitino.meta.TableEntity;
import org.apache.gravitino.policy.IcebergEncryptionContent;
import org.apache.gravitino.policy.IcebergEncryptionContent.Enforcement;
import org.apache.gravitino.policy.Policy;
import org.apache.gravitino.policy.PolicyContent;
import org.apache.gravitino.policy.PolicyContents;
import org.apache.gravitino.policy.PolicyDispatcher;
import org.apache.gravitino.rel.Column;
import org.apache.gravitino.rel.Table;
import org.apache.gravitino.rel.TableChange;
import org.apache.gravitino.rel.expressions.distributions.Distribution;
import org.apache.gravitino.rel.expressions.distributions.Distributions;
import org.apache.gravitino.rel.expressions.sorts.SortOrder;
import org.apache.gravitino.rel.expressions.transforms.Transform;
import org.apache.gravitino.rel.indexes.Index;
import org.apache.gravitino.utils.RequestContext;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

public class TestIcebergTableEncryptionDispatcher {

  private static final String METALAKE = "metalake";
  private static final String SOURCE = "openbao";
  private static final String KEY_ID = "customer-pii-v1";
  private static final NameIdentifier TABLE =
      NameIdentifier.of(METALAKE, "catalog", "schema", "table");
  private static final NameIdentifier CATALOG = NameIdentifier.of(METALAKE, "catalog");
  private static final Column[] COLUMNS = new Column[0];
  private static final Transform[] PARTITIONS = new Transform[0];
  private static final Distribution DISTRIBUTION = Distributions.NONE;
  private static final SortOrder[] SORT_ORDERS = new SortOrder[0];
  private static final Index[] INDEXES = new Index[0];

  private DatastratoTableDispatcher delegate;
  private CatalogManager catalogManager;
  private PolicyDispatcher policyDispatcher;
  private AtomicReference<KmsValidationStatus> validationStatus;
  private AtomicReference<KmsReference> validatedKey;
  private AtomicInteger validationCalls;
  private Catalog catalog;
  private Table createdTable;
  private AtomicReference<AtomicIntegrityCapability> capability;
  private IcebergTableEncryptionDispatcher dispatcher;

  @BeforeEach
  void setUp() {
    delegate = mock(DatastratoTableDispatcher.class);
    catalogManager = mock(CatalogManager.class);
    policyDispatcher = mock(PolicyDispatcher.class);
    validationStatus = new AtomicReference<>(KmsValidationStatus.VALID);
    validatedKey = new AtomicReference<>();
    validationCalls = new AtomicInteger();
    catalog = mock(Catalog.class);
    createdTable = mock(Table.class);
    capability = new AtomicReference<>(AtomicIntegrityCapability.SUPPORTED);

    when(catalogManager.loadCatalog(CATALOG)).thenReturn(catalog);
    when(catalog.provider()).thenReturn("lakehouse-iceberg");
    when(catalog.properties())
        .thenReturn(
            ImmutableMap.of(
                "encryption-kms-source",
                SOURCE,
                "catalog-backend",
                "jdbc",
                "jdbc-driver",
                "org.postgresql.Driver",
                "uri",
                "jdbc:postgresql://localhost/catalog",
                IcebergFormatVersionResolution.TABLE_DEFAULT_FORMAT_VERSION,
                "3"));
    when(delegate.createTable(any(), any(), any(), any(), any(), any(), any(), any()))
        .thenReturn(createdTable);

    dispatcher = new IcebergTableEncryptionDispatcher(delegate, catalogManager, admissionService());
  }

  /**
   * Builds the service exactly as the environment does, over the same catalog manager the
   * dispatcher pre-checks, so these tests exercise the real translation rather than a stub
   * decision.
   */
  private EncryptionAdmissionService admissionService() {
    return new EncryptionAdmissionService(
        table -> catalogManager.loadCatalog(getCatalogIdentifier(table)).properties(),
        () -> new IcebergEncryptionPolicyResolver(policyDispatcher, new SinglePolicyChecker()),
        new IcebergEncryptionPolicyEvaluator(
            key -> {
              validatedKey.set(key);
              validationCalls.incrementAndGet();
              return IcebergEncryptionDecision.KmsValidationStatus.valueOf(
                  validationStatus.get().name());
            }),
        table -> capability.get());
  }

  @Test
  void testNullConstructorAndFactoryArgumentsAreRejectedAsIllegalArguments() {
    EncryptionAdmissionService service = admissionService();

    Assertions.assertEquals(
        "dispatcher cannot be null",
        Assertions.assertThrows(
                IllegalArgumentException.class,
                () -> new IcebergTableEncryptionDispatcher(null, catalogManager, service))
            .getMessage());
    Assertions.assertEquals(
        "catalogManager cannot be null",
        Assertions.assertThrows(
                IllegalArgumentException.class,
                () -> new IcebergTableEncryptionDispatcher(delegate, null, service))
            .getMessage());
    Assertions.assertEquals(
        "admissionService cannot be null",
        Assertions.assertThrows(
                IllegalArgumentException.class,
                () -> new IcebergTableEncryptionDispatcher(delegate, catalogManager, null))
            .getMessage());

    Assertions.assertEquals(
        "registry cannot be null",
        Assertions.assertThrows(
                IllegalArgumentException.class,
                () -> IcebergEncryptionKmsKeyValidators.fromRegistry(null))
            .getMessage());
    Assertions.assertEquals(
        "clientResolver cannot be null",
        Assertions.assertThrows(
                IllegalArgumentException.class,
                () -> IcebergEncryptionKmsKeyValidators.fromClientResolver(null))
            .getMessage());
    Assertions.assertEquals(
        "client cannot be null",
        Assertions.assertThrows(
                IllegalArgumentException.class,
                () -> IcebergEncryptionKmsKeyValidators.fromClient(null))
            .getMessage());
  }

  @AfterEach
  void clearRequestContext() {
    RequestContext.clear();
  }

  @Test
  void testNonIcebergCreateIsUnchanged() {
    when(catalog.provider()).thenReturn("jdbc-postgresql");
    Map<String, String> properties = keyProperties(KEY_ID);

    Assertions.assertSame(createdTable, create(properties));

    verify(delegate)
        .createTable(
            same(TABLE),
            same(COLUMNS),
            eq("comment"),
            same(properties),
            same(PARTITIONS),
            same(DISTRIBUTION),
            same(SORT_ORDERS),
            same(INDEXES));
    verifyNoInteractions(policyDispatcher);
    Assertions.assertEquals(0, validationCalls.get());
  }

  @Test
  void testCreateOnMissingSchemaSurfacesDelegateNoSuchSchemaException() {
    when(policyDispatcher.listPolicyInfosForMetadataObject(
            eq(METALAKE), argThat(object -> object.type() == MetadataObject.Type.SCHEMA)))
        .thenThrow(
            new NoSuchMetadataObjectException(
                "Metadata object %s type SCHEMA doesn't exist", "catalog.schema"));
    NoSuchSchemaException missingSchema = new NoSuchSchemaException("Schema does not exist");
    when(delegate.createTable(any(), any(), any(), any(), any(), any(), any(), any()))
        .thenThrow(missingSchema);

    Assertions.assertSame(
        missingSchema,
        Assertions.assertThrows(NoSuchSchemaException.class, () -> create(Collections.emptyMap())));
    verify(delegate)
        .createTable(
            same(TABLE),
            same(COLUMNS),
            eq("comment"),
            any(),
            same(PARTITIONS),
            same(DISTRIBUTION),
            same(SORT_ORDERS),
            same(INDEXES));
    Assertions.assertEquals(0, validationCalls.get());
    Assertions.assertTrue(RequestContext.takeAuditExtras().isEmpty());
  }

  @Test
  void testIcebergCreateWithoutPolicyOrEncryptionIsUnchanged() {
    Map<String, String> properties = new HashMap<>();
    properties.put("format-version", "2");
    properties.put("owner-property", "value");

    Assertions.assertSame(createdTable, create(properties));

    verify(delegate)
        .createTable(
            same(TABLE),
            same(COLUMNS),
            eq("comment"),
            same(properties),
            same(PARTITIONS),
            same(DISTRIBUTION),
            same(SORT_ORDERS),
            same(INDEXES));
    Assertions.assertTrue(RequestContext.takeAuditExtras().isEmpty());
    Assertions.assertEquals(0, validationCalls.get());
  }

  @Test
  void testInheritedSchemaAssociationAppliesPolicyDuringCreate() {
    PolicyEntity policy = policy("inherited", false, Enforcement.REPORT, KEY_ID);
    associateOnSchema(policy);

    Assertions.assertSame(createdTable, create(Collections.emptyMap()));

    // V6: the policy applied but supplied no key, so the create stays plain at the version it
    // asked for. A policy never supplies a key, and only a key needs version 3.
    Assertions.assertFalse(forwardedProperties().containsKey("format-version"));
    Assertions.assertEquals("inherited", takeExtras().get(IcebergEncryptionAuditInfos.POLICY_NAME));
  }

  @Test
  void testInheritedSchemaAssociationAppliesPolicyDuringCreateWithNullProperties() {
    PolicyEntity policy = policy("inherited", false, Enforcement.REPORT, KEY_ID);
    associateOnSchema(policy);

    Assertions.assertSame(createdTable, create(null));

    Map<String, String> forwarded = forwardedProperties();
    Assertions.assertFalse(forwarded.containsKey("format-version"));
    Assertions.assertFalse(
        forwarded.containsKey(IcebergEncryptionPolicyEvaluator.ENCRYPTION_KEY_PROVIDER));
    Assertions.assertFalse(
        forwarded.containsKey(IcebergEncryptionPolicyEvaluator.ENCRYPTION_KEY_ID));
    Assertions.assertEquals(0, validationCalls.get());

    Map<String, String> extras = takeExtras();
    Assertions.assertEquals("inherited", extras.get(IcebergEncryptionAuditInfos.POLICY_NAME));
    Assertions.assertEquals("COMPLIANT", extras.get(IcebergEncryptionAuditInfos.COMPLIANCE));
    Assertions.assertEquals(
        Reason.COMPLIANT.code(), extras.get(IcebergEncryptionAuditInfos.REASON));
    Assertions.assertEquals(
        KmsValidationStatus.NOT_ATTEMPTED.name(),
        extras.get(IcebergEncryptionAuditInfos.KMS_VALIDATION));
  }

  /**
   * V6 and lifecycle row 15: a policy in {@code report} mode records the missing key as a violation
   * and lets the create through. The table is plain, so it is forwarded at the version it asked
   * for; stamping version 3 on it would make a permanent change to a table that never got a key.
   */
  @Test
  void testReportMissingRequiredKey() {
    PolicyEntity reportPolicy = policy("report", true, Enforcement.REPORT, KEY_ID);
    associateOnSchema(reportPolicy);

    Assertions.assertSame(createdTable, create(Collections.singletonMap("format-version", "2")));

    Map<String, String> reportEvent = takeExtras();
    Assertions.assertEquals("VIOLATION", reportEvent.get(IcebergEncryptionAuditInfos.COMPLIANCE));
    Assertions.assertEquals(
        Reason.KEY_REQUIRED.code(), reportEvent.get(IcebergEncryptionAuditInfos.REASON));
    Map<String, String> forwarded = forwardedProperties();
    Assertions.assertEquals("2", forwarded.get("format-version"));
    Assertions.assertFalse(forwarded.containsKey("encryption.key-id"));
  }

  @Test
  void testDenyMissingRequiredKey() {
    PolicyEntity denyPolicy = policy("deny", true, Enforcement.DENY_CREATE, KEY_ID);
    associateOnSchema(denyPolicy);

    IllegalArgumentException exception =
        Assertions.assertThrows(
            IllegalArgumentException.class, () -> create(Collections.emptyMap()));

    Map<String, String> denyEvent = takeExtras();
    Assertions.assertEquals(
        Reason.KEY_REQUIRED.code(), denyEvent.get(IcebergEncryptionAuditInfos.REASON));
    Assertions.assertTrue(exception.getMessage().contains("decisionId="));
    verify(delegate, never()).createTable(any(), any(), any(), any(), any(), any(), any(), any());
  }

  @Test
  void testValidKeyIsSanitizedAndForwardedAtTheCatalogDefault() {
    PolicyEntity policy = policy("valid", true, Enforcement.DENY_CREATE, KEY_ID);
    associateOnSchema(policy);
    Map<String, String> properties = keyProperties(KEY_ID);
    properties.put("unrelated", "preserved");

    Assertions.assertSame(createdTable, create(properties));

    Map<String, String> forwarded = forwardedProperties();
    Assertions.assertEquals("3", forwarded.get("format-version"));
    Assertions.assertEquals(SOURCE, forwarded.get("encryption.key-provider"));
    Assertions.assertEquals(KEY_ID, forwarded.get("encryption.key-id"));
    Assertions.assertFalse(forwarded.containsKey(IcebergConstants.ENCRYPTION_KMS_API));
    Assertions.assertFalse(forwarded.containsKey(IcebergConstants.ENCRYPTION_KEY_SOURCE));
    Assertions.assertEquals("preserved", forwarded.get("unrelated"));
    Assertions.assertEquals(1, validationCalls.get());
    Assertions.assertEquals(SOURCE, validatedKey.get().provider());
    Assertions.assertEquals(KEY_ID, validatedKey.get().keyId());

    Map<String, String> event = takeExtras();
    Assertions.assertEquals(Reason.COMPLIANT.code(), event.get(IcebergEncryptionAuditInfos.REASON));
    Assertions.assertEquals(
        KmsValidationStatus.VALID.name(), event.get(IcebergEncryptionAuditInfos.KMS_VALIDATION));
    Assertions.assertEquals("valid", event.get(IcebergEncryptionAuditInfos.POLICY_NAME));
  }

  @Test
  void testReportModeValidatesAndForwardsNonAllowlistedKey() {
    PolicyEntity policy = policy("report", true, Enforcement.REPORT, "allowed-key");
    associateOnSchema(policy);

    Assertions.assertSame(createdTable, create(keyProperties("reported-key")));

    Assertions.assertEquals("reported-key", forwardedProperties().get("encryption.key-id"));
    Assertions.assertEquals(1, validationCalls.get());
    Assertions.assertEquals(SOURCE, validatedKey.get().provider());
    Assertions.assertEquals("reported-key", validatedKey.get().keyId());
    Map<String, String> event = takeExtras();
    Assertions.assertEquals("VIOLATION", event.get(IcebergEncryptionAuditInfos.COMPLIANCE));
    Assertions.assertEquals(
        Reason.KEY_NOT_ALLOWED.code(), event.get(IcebergEncryptionAuditInfos.REASON));
    Assertions.assertEquals(
        KmsValidationStatus.VALID.name(), event.get(IcebergEncryptionAuditInfos.KMS_VALIDATION));
  }

  @Test
  void testNoPolicyEncryptionInputIsValidatedAndAudited() {
    when(delegate.createTable(any(), any(), any(), any(), any(), any(), any(), any()))
        .thenAnswer(
            invocation -> {
              Assertions.assertTrue(
                  RequestContext.takeAuditExtras().isEmpty(),
                  "No-policy create audit must wait for the physical create outcome");
              return createdTable;
            });

    Assertions.assertSame(createdTable, create(keyProperties(KEY_ID)));

    Assertions.assertEquals(KEY_ID, forwardedProperties().get("encryption.key-id"));
    Map<String, String> event = takeExtras();
    Assertions.assertEquals(Reason.NO_POLICY.code(), event.get(IcebergEncryptionAuditInfos.REASON));
    Assertions.assertFalse(event.containsKey(IcebergEncryptionAuditInfos.COMPLIANCE));
    Assertions.assertFalse(event.containsKey(IcebergEncryptionAuditInfos.POLICY_NAME));
    Assertions.assertEquals(
        KmsValidationStatus.VALID.name(), event.get(IcebergEncryptionAuditInfos.KMS_VALIDATION));
  }

  @Test
  void testCatalogProviderMismatchIsAuditedAndRejectedWithoutValidation() {
    Map<String, String> properties = keyProperties(KEY_ID);
    properties.put(IcebergEncryptionPolicyEvaluator.ENCRYPTION_KEY_PROVIDER, "aws");

    IllegalArgumentException exception =
        Assertions.assertThrows(IllegalArgumentException.class, () -> create(properties));

    Map<String, String> event = takeExtras();
    Assertions.assertEquals(
        Reason.KEY_SOURCE_MISMATCH.code(), event.get(IcebergEncryptionAuditInfos.REASON));
    Assertions.assertEquals(
        KmsValidationStatus.NOT_ATTEMPTED.name(),
        event.get(IcebergEncryptionAuditInfos.KMS_VALIDATION));
    Assertions.assertTrue(exception.getMessage().contains("decisionId="));
    Assertions.assertEquals(0, validationCalls.get());
    verify(delegate, never()).createTable(any(), any(), any(), any(), any(), any(), any(), any());
  }

  @Test
  void testIncompleteKeyReferenceIsAuditedAndRejectedWithoutValidation() {
    // A key id alone is completed from the catalog's KMS binding (Q12); a provider with no key id
    // still names nothing the KMS can be asked about.
    Map<String, String> properties =
        ImmutableMap.of(IcebergEncryptionPolicyEvaluator.ENCRYPTION_KEY_PROVIDER, SOURCE);

    IllegalArgumentException exception =
        Assertions.assertThrows(IllegalArgumentException.class, () -> create(properties));

    Map<String, String> event = takeExtras();
    Assertions.assertEquals(
        Reason.KEY_REFERENCE_INVALID.code(), event.get(IcebergEncryptionAuditInfos.REASON));
    Assertions.assertEquals(
        KmsValidationStatus.NOT_ATTEMPTED.name(),
        event.get(IcebergEncryptionAuditInfos.KMS_VALIDATION));
    Assertions.assertTrue(exception.getMessage().contains("decisionId="));
    Assertions.assertEquals(0, validationCalls.get());
    verify(delegate, never()).createTable(any(), any(), any(), any(), any(), any(), any(), any());
  }

  @Test
  void testInvalidKmsKeyIsAuditedAndRejectedBeforeCreate() {
    validationStatus.set(KmsValidationStatus.INVALID);

    IllegalArgumentException exception =
        Assertions.assertThrows(
            IllegalArgumentException.class, () -> create(keyProperties(KEY_ID)));

    Map<String, String> event = takeExtras();
    Assertions.assertEquals(
        Reason.KMS_KEY_INVALID.code(), event.get(IcebergEncryptionAuditInfos.REASON));
    Assertions.assertEquals(
        KmsValidationStatus.INVALID.name(), event.get(IcebergEncryptionAuditInfos.KMS_VALIDATION));
    Assertions.assertEquals(SOURCE, event.get(IcebergEncryptionAuditInfos.PROVIDER));
    Assertions.assertEquals(KEY_ID, event.get(IcebergEncryptionAuditInfos.KEY_ID));
    Assertions.assertTrue(
        exception
            .getMessage()
            .contains(
                "KMS key 'openbao:customer-pii-v1' is not valid for encryption. "
                    + "Verify that the key exists, is enabled, and supports encryption."));
    Assertions.assertTrue(exception.getMessage().contains("decisionId="));
    verify(delegate, never()).createTable(any(), any(), any(), any(), any(), any(), any(), any());
  }

  @Test
  void testUnavailableKmsIsAuditedAndFailsBeforeCreate() {
    validationStatus.set(KmsValidationStatus.UNAVAILABLE);

    ConnectionFailedException exception =
        Assertions.assertThrows(
            ConnectionFailedException.class, () -> create(keyProperties(KEY_ID)));

    Map<String, String> event = takeExtras();
    Assertions.assertEquals(
        Reason.KMS_SERVICE_UNAVAILABLE.code(), event.get(IcebergEncryptionAuditInfos.REASON));
    Assertions.assertEquals(
        ConnectionFailedException.class.getName(),
        event.get(IcebergEncryptionAuditInfos.ERROR_TYPE));
    Assertions.assertTrue(
        exception
            .getMessage()
            .contains(
                "KMS provider 'openbao' is unavailable; KMS key 'customer-pii-v1' could not be "
                    + "validated. Retry later."));
    Assertions.assertTrue(exception.getMessage().contains("decisionId="));
    verify(delegate, never()).createTable(any(), any(), any(), any(), any(), any(), any(), any());
  }

  @Test
  void testClientValidatorRequiresUsableWrappingKey() {
    KmsReference key = new KmsReference(SOURCE, KEY_ID);

    Assertions.assertEquals(
        IcebergEncryptionDecision.KmsValidationStatus.VALID,
        validateWithProperties(key, true, true));
    Assertions.assertEquals(
        IcebergEncryptionDecision.KmsValidationStatus.INVALID,
        IcebergEncryptionKmsKeyValidators.fromClient(ignored -> Optional.empty()).validate(key));
    Assertions.assertEquals(
        IcebergEncryptionDecision.KmsValidationStatus.INVALID,
        validateWithProperties(key, false, true));
    Assertions.assertEquals(
        IcebergEncryptionDecision.KmsValidationStatus.INVALID,
        validateWithProperties(key, true, false));
  }

  @Test
  void testClientValidatorMapsProviderFailures() {
    KmsReference key = new KmsReference(SOURCE, KEY_ID);
    KmsClient invalidClient =
        ignored -> {
          throw new IllegalArgumentException("invalid key");
        };
    KmsClient unavailableClient =
        ignored -> {
          throw new ConnectionFailedException("KMS unavailable");
        };
    KmsClient unexpectedFailureClient =
        ignored -> {
          throw new IllegalStateException("unexpected provider failure");
        };

    Assertions.assertEquals(
        IcebergEncryptionDecision.KmsValidationStatus.INVALID,
        IcebergEncryptionKmsKeyValidators.fromClient(invalidClient).validate(key));
    Assertions.assertEquals(
        IcebergEncryptionDecision.KmsValidationStatus.UNAVAILABLE,
        IcebergEncryptionKmsKeyValidators.fromClient(unavailableClient).validate(key));
    Assertions.assertEquals(
        IcebergEncryptionDecision.KmsValidationStatus.UNAVAILABLE,
        IcebergEncryptionKmsKeyValidators.fromClient(unexpectedFailureClient).validate(key));
  }

  @Test
  void testRegistryValidatorMapsGetClientFailures() {
    KmsReference key = new KmsReference(SOURCE, KEY_ID);
    KmsClientRegistry unknownProviderRegistry = new KmsClientRegistry(new Config(false) {});
    KmsClientRegistry closedRegistry = new KmsClientRegistry(new Config(false) {});
    closedRegistry.close();

    Assertions.assertEquals(
        IcebergEncryptionDecision.KmsValidationStatus.INVALID,
        IcebergEncryptionKmsKeyValidators.fromRegistry(unknownProviderRegistry).validate(key));
    Assertions.assertEquals(
        IcebergEncryptionDecision.KmsValidationStatus.UNAVAILABLE,
        IcebergEncryptionKmsKeyValidators.fromRegistry(closedRegistry).validate(key));
  }

  @Test
  void testAllowedCreateStashesExtrasWithoutPublishing() {
    PolicyEntity policy = policy("listener", false, Enforcement.REPORT, KEY_ID);
    associateOnSchema(policy);

    Assertions.assertSame(createdTable, create(Collections.emptyMap()));

    verify(delegate).createTable(any(), any(), any(), any(), any(), any(), any(), any());
    Assertions.assertEquals(
        Reason.COMPLIANT.code(), takeExtras().get(IcebergEncryptionAuditInfos.REASON));
  }

  @Test
  void testAmbiguousPolicyIsAuditedAndRejectedBeforeCreate() {
    PolicyEntity first = policy("a-policy", true, Enforcement.DENY_CREATE, KEY_ID);
    PolicyEntity second = policy("b-policy", true, Enforcement.DENY_CREATE, KEY_ID);
    associateOnSchema(first, second);

    AmbiguousPolicyException exception =
        Assertions.assertThrows(
            AmbiguousPolicyException.class, () -> create(Collections.emptyMap()));

    Map<String, String> event = takeExtras();
    Assertions.assertEquals(
        Reason.AMBIGUOUS_POLICY.code(), event.get(IcebergEncryptionAuditInfos.REASON));
    Assertions.assertEquals(
        "a-policy,b-policy", event.get(IcebergEncryptionAuditInfos.POLICY_NAME));
    Assertions.assertArrayEquals(
        new String[] {"a-policy", "b-policy"}, exception.matchedPolicyNames());
    Assertions.assertTrue(exception.getMessage().contains("decisionId="));
    Assertions.assertTrue(exception.getMessage().contains("[a-policy, b-policy]"));
    verify(delegate, never()).createTable(any(), any(), any(), any(), any(), any(), any(), any());
  }

  @Test
  void testAmbiguousPolicyAuditSnapshotsRequestedProviderKey() {
    PolicyEntity first = policy("a-policy", true, Enforcement.DENY_CREATE, KEY_ID);
    PolicyEntity second = policy("b-policy", true, Enforcement.DENY_CREATE, KEY_ID);
    associateOnSchema(first, second);

    Assertions.assertThrows(AmbiguousPolicyException.class, () -> create(keyProperties(KEY_ID)));

    Map<String, String> extras = takeExtras();
    Assertions.assertEquals(SOURCE, extras.get(IcebergEncryptionAuditInfos.PROVIDER));
    Assertions.assertEquals(KEY_ID, extras.get(IcebergEncryptionAuditInfos.KEY_ID));
    verify(delegate, never()).createTable(any(), any(), any(), any(), any(), any(), any(), any());
  }

  @Test
  void testPolicyDecisionSucceedsThenPhysicalCreateFailsWithCorrelatedAudit() {
    PolicyEntity policy = policy("create-failure", true, Enforcement.DENY_CREATE, KEY_ID);
    RuntimeException failure = new RuntimeException("physical create failed");
    associateOnSchema(policy);
    when(delegate.createTable(any(), any(), any(), any(), any(), any(), any(), any()))
        .thenThrow(failure);

    Assertions.assertSame(
        failure,
        Assertions.assertThrows(RuntimeException.class, () -> create(keyProperties(KEY_ID))));

    Map<String, String> event = takeExtras();
    Assertions.assertEquals(
        Reason.TABLE_CREATE_FAILED.code(), event.get(IcebergEncryptionAuditInfos.REASON));
  }

  @Test
  void testNoPolicyPhysicalCreateFailurePublishesOnlyFinalCreateAudit() {
    RuntimeException failure = new RuntimeException("physical create failed");
    when(delegate.createTable(any(), any(), any(), any(), any(), any(), any(), any()))
        .thenThrow(failure);

    Assertions.assertSame(
        failure,
        Assertions.assertThrows(RuntimeException.class, () -> create(keyProperties(KEY_ID))));

    Map<String, String> event = takeExtras();
    Assertions.assertEquals(
        Reason.TABLE_CREATE_FAILED.code(), event.get(IcebergEncryptionAuditInfos.REASON));
    Assertions.assertEquals(
        KmsValidationStatus.VALID.name(), event.get(IcebergEncryptionAuditInfos.KMS_VALIDATION));
  }

  @Test
  void testNonEncryptionAlterDoesNotStashExtras() {
    TableChange change = TableChange.setProperty("comment", "updated");
    Table altered = mock(Table.class);
    when(delegate.alterTable(TABLE, change)).thenReturn(altered);

    Assertions.assertSame(altered, dispatcher.alterTable(TABLE, change));

    verify(delegate).alterTable(TABLE, change);
    verifyNoInteractions(catalogManager);
    Assertions.assertTrue(RequestContext.takeAuditExtras().isEmpty());
    Assertions.assertEquals(0, validationCalls.get());
  }

  @Test
  void testNonIcebergEncryptionAlterDoesNotStashExtras() {
    when(catalog.provider()).thenReturn("jdbc-postgresql");
    TableChange change =
        TableChange.setProperty(IcebergEncryptionPolicyEvaluator.ENCRYPTION_KEY_ID, KEY_ID);
    Table altered = mock(Table.class);
    when(delegate.alterTable(TABLE, change)).thenReturn(altered);

    Assertions.assertSame(altered, dispatcher.alterTable(TABLE, change));

    verify(delegate).alterTable(TABLE, change);
    Assertions.assertTrue(RequestContext.takeAuditExtras().isEmpty());
    Assertions.assertEquals(0, validationCalls.get());
  }

  @Test
  void testSuccessfulEncryptionAlterStashesUpdatedAndAttemptedKey() {
    TableChange provider =
        TableChange.setProperty(IcebergEncryptionPolicyEvaluator.ENCRYPTION_KEY_PROVIDER, SOURCE);
    TableChange keyId =
        TableChange.setProperty(IcebergEncryptionPolicyEvaluator.ENCRYPTION_KEY_ID, KEY_ID);
    TableChange comment = TableChange.setProperty("comment", "kept");
    Table altered = mock(Table.class);
    when(delegate.alterTable(eq(TABLE), any(TableChange[].class))).thenReturn(altered);

    Assertions.assertSame(altered, dispatcher.alterTable(TABLE, provider, keyId, comment));

    verify(delegate).alterTable(TABLE, provider, keyId, comment);
    Map<String, String> extras = takeExtras();
    Assertions.assertEquals(
        Reason.ENCRYPTION_PROPERTIES_UPDATED.code(),
        extras.get(IcebergEncryptionAuditInfos.REASON));
    Assertions.assertEquals(SOURCE, extras.get(IcebergEncryptionAuditInfos.ATTEMPTED_PROVIDER));
    Assertions.assertEquals(KEY_ID, extras.get(IcebergEncryptionAuditInfos.ATTEMPTED_KEY_ID));
    Assertions.assertFalse(extras.containsKey(IcebergEncryptionAuditInfos.PREVIOUS_PROVIDER));
    Assertions.assertFalse(extras.containsKey(IcebergEncryptionAuditInfos.ERROR_TYPE));
    Assertions.assertEquals(0, validationCalls.get());
  }

  @Test
  void testKeyIdOnlyAlterStashesUpdatedWithoutAttemptedKey() {
    TableChange keyId =
        TableChange.setProperty(IcebergEncryptionPolicyEvaluator.ENCRYPTION_KEY_ID, KEY_ID);
    Table altered = mock(Table.class);
    when(delegate.alterTable(TABLE, keyId)).thenReturn(altered);

    Assertions.assertSame(altered, dispatcher.alterTable(TABLE, keyId));

    Map<String, String> extras = takeExtras();
    Assertions.assertEquals(
        Reason.ENCRYPTION_PROPERTIES_UPDATED.code(),
        extras.get(IcebergEncryptionAuditInfos.REASON));
    Assertions.assertFalse(extras.containsKey(IcebergEncryptionAuditInfos.ATTEMPTED_PROVIDER));
    Assertions.assertFalse(extras.containsKey(IcebergEncryptionAuditInfos.ATTEMPTED_KEY_ID));
  }

  @Test
  void testLeftoverProtocolPropAlterIsAudited() {
    TableChange leftover =
        TableChange.setProperty(IcebergConstants.ENCRYPTION_KMS_API, "openbao-transit");
    Table altered = mock(Table.class);
    when(delegate.alterTable(TABLE, leftover)).thenReturn(altered);

    Assertions.assertSame(altered, dispatcher.alterTable(TABLE, leftover));

    Map<String, String> extras = takeExtras();
    Assertions.assertEquals(
        Reason.ENCRYPTION_PROPERTIES_UPDATED.code(),
        extras.get(IcebergEncryptionAuditInfos.REASON));
    Assertions.assertFalse(extras.containsKey(IcebergEncryptionAuditInfos.ATTEMPTED_KEY_ID));
    Assertions.assertEquals(0, validationCalls.get());
  }

  @Test
  void testRemoveEncryptionPropAlterStashesUpdatedWithoutAttemptedKey() {
    TableChange remove =
        TableChange.removeProperty(IcebergEncryptionPolicyEvaluator.ENCRYPTION_KEY_ID);
    Table altered = mock(Table.class);
    when(delegate.alterTable(TABLE, remove)).thenReturn(altered);

    Assertions.assertSame(altered, dispatcher.alterTable(TABLE, remove));

    Map<String, String> extras = takeExtras();
    Assertions.assertEquals(
        Reason.ENCRYPTION_PROPERTIES_UPDATED.code(),
        extras.get(IcebergEncryptionAuditInfos.REASON));
    Assertions.assertFalse(extras.containsKey(IcebergEncryptionAuditInfos.ATTEMPTED_KEY_ID));
  }

  @Test
  void testIllegalArgumentOnEncryptionAlterIsKeyChangeDenied() {
    TableChange provider =
        TableChange.setProperty(IcebergEncryptionPolicyEvaluator.ENCRYPTION_KEY_PROVIDER, SOURCE);
    TableChange keyId =
        TableChange.setProperty(IcebergEncryptionPolicyEvaluator.ENCRYPTION_KEY_ID, "other-key");
    IllegalArgumentException failure =
        new IllegalArgumentException("encryption.key-id is immutable");
    when(delegate.alterTable(TABLE, provider, keyId)).thenThrow(failure);

    Assertions.assertSame(
        failure,
        Assertions.assertThrows(
            IllegalArgumentException.class, () -> dispatcher.alterTable(TABLE, provider, keyId)));

    Map<String, String> extras = takeExtras();
    Assertions.assertEquals(
        Reason.ENCRYPTION_KEY_CHANGE_DENIED.code(), extras.get(IcebergEncryptionAuditInfos.REASON));
    Assertions.assertEquals(SOURCE, extras.get(IcebergEncryptionAuditInfos.ATTEMPTED_PROVIDER));
    Assertions.assertEquals("other-key", extras.get(IcebergEncryptionAuditInfos.ATTEMPTED_KEY_ID));
    Assertions.assertFalse(extras.containsKey(IcebergEncryptionAuditInfos.ERROR_TYPE));
    Assertions.assertEquals(0, validationCalls.get());
  }

  @Test
  void testRuntimeFailureOnEncryptionAlterIsOperationFailed() {
    TableChange keyId =
        TableChange.setProperty(IcebergEncryptionPolicyEvaluator.ENCRYPTION_KEY_ID, KEY_ID);
    RuntimeException failure = new RuntimeException("catalog alter failed");
    when(delegate.alterTable(TABLE, keyId)).thenThrow(failure);

    Assertions.assertSame(
        failure,
        Assertions.assertThrows(RuntimeException.class, () -> dispatcher.alterTable(TABLE, keyId)));

    Map<String, String> extras = takeExtras();
    Assertions.assertEquals(
        Reason.OPERATION_FAILED.code(), extras.get(IcebergEncryptionAuditInfos.REASON));
    Assertions.assertEquals(
        RuntimeException.class.getName(), extras.get(IcebergEncryptionAuditInfos.ERROR_TYPE));
    Assertions.assertFalse(extras.containsKey(IcebergEncryptionAuditInfos.ATTEMPTED_KEY_ID));
  }

  @Test
  @SuppressWarnings("unchecked")
  void testNonCreateOperationsDelegateUnchanged() {
    Namespace namespace = TABLE.namespace();
    NameIdentifier[] identifiers = new NameIdentifier[] {TABLE};
    Table loaded = mock(Table.class);
    Set<Privilege.Name> privileges = Collections.singleton(Privilege.Name.SELECT_TABLE);
    TableChange change = TableChange.setProperty("key", "value");
    Table altered = mock(Table.class);
    List<TableEntity> entities = Collections.singletonList(mock(TableEntity.class));
    Map<String, Object>[] preview = new Map[] {Collections.singletonMap("column", "value")};

    when(delegate.listTables(namespace)).thenReturn(identifiers);
    when(delegate.loadTable(TABLE)).thenReturn(loaded);
    when(delegate.loadTable(TABLE, privileges)).thenReturn(loaded);
    when(delegate.tableExists(TABLE)).thenReturn(true);
    when(delegate.alterTable(TABLE, change)).thenReturn(altered);
    when(delegate.dropTable(TABLE)).thenReturn(true);
    when(delegate.purgeTable(TABLE)).thenReturn(true);
    when(delegate.listEntities(namespace)).thenReturn(entities);
    when(delegate.preview(TABLE, Entity.EntityType.TABLE, 10, COLUMNS)).thenReturn(preview);

    Assertions.assertSame(identifiers, dispatcher.listTables(namespace));
    Assertions.assertSame(loaded, dispatcher.loadTable(TABLE));
    Assertions.assertSame(loaded, dispatcher.loadTable(TABLE, privileges));
    Assertions.assertTrue(dispatcher.tableExists(TABLE));
    Assertions.assertSame(altered, dispatcher.alterTable(TABLE, change));
    Assertions.assertTrue(dispatcher.dropTable(TABLE));
    Assertions.assertTrue(dispatcher.purgeTable(TABLE));
    Assertions.assertSame(entities, dispatcher.listEntities(namespace));
    Assertions.assertSame(preview, dispatcher.preview(TABLE, Entity.EntityType.TABLE, 10, COLUMNS));

    verifyNoInteractions(catalogManager, policyDispatcher);
    Assertions.assertEquals(0, validationCalls.get());
  }

  /**
   * Matrix rows 4 and 10 (V2) on the Gravitino API: a key with an explicit version 2 is refused
   * with an {@link IllegalArgumentException}, which this API answers with HTTP 400, before any
   * policy lookup or KMS call, and the refusal is audited under the decision id it names.
   */
  @Test
  void testKeyWithExplicitVersionTwoIsRefusedBeforePolicyAndKms() {
    Map<String, String> clientKey = keyProperties(KEY_ID);
    clientKey.put("format-version", "2");
    assertUnsupportedFormatVersion(clientKey, IcebergFormatVersionResolution.Source.REQUEST);

    setUp();
    useCatalogProperty("gravitino.bypass.table-default.encryption.key-id", "catalog-key");
    assertUnsupportedFormatVersion(
        Collections.singletonMap("format-version", "2"),
        IcebergFormatVersionResolution.Source.REQUEST);
  }

  /**
   * Matrix rows 3 and 5 on the Gravitino API: a keyed create naming no version is refused when the
   * catalog declares no format version of its own, and is otherwise forwarded at the version the
   * resolution produced, from the catalog's declaration or from the request itself.
   */
  @Test
  void testKeyedCreateIsForwardedAtTheAdmittedVersion() {
    withoutCatalogProperty(IcebergFormatVersionResolution.TABLE_DEFAULT_FORMAT_VERSION);
    IllegalArgumentException refused =
        Assertions.assertThrows(
            IllegalArgumentException.class, () -> create(keyProperties(KEY_ID)));
    Assertions.assertTrue(
        refused
            .getMessage()
            .contains(IcebergFormatVersionResolution.Source.ICEBERG_DEFAULT.token()),
        refused.getMessage());
    Assertions.assertTrue(
        refused.getMessage().contains(IcebergFormatVersionResolution.TABLE_DEFAULT_FORMAT_VERSION),
        refused.getMessage());
    Assertions.assertEquals(0, validationCalls.get());
    verify(delegate, never()).createTable(any(), any(), any(), any(), any(), any(), any(), any());

    setUp();
    useCatalogProperty(IcebergFormatVersionResolution.TABLE_DEFAULT_FORMAT_VERSION, "4");
    Assertions.assertSame(createdTable, create(keyProperties(KEY_ID)));
    Assertions.assertEquals("4", forwardedProperties().get("format-version"));

    setUp();
    Map<String, String> explicitFour = keyProperties(KEY_ID);
    explicitFour.put("format-version", "4");
    Assertions.assertSame(createdTable, create(explicitFour));
    Assertions.assertEquals("4", forwardedProperties().get("format-version"));
  }

  private void assertUnsupportedFormatVersion(
      Map<String, String> properties, IcebergFormatVersionResolution.Source source) {
    IllegalArgumentException exception =
        Assertions.assertThrows(IllegalArgumentException.class, () -> create(properties));

    Assertions.assertTrue(exception.getMessage().contains("encryption is active"));
    Assertions.assertTrue(exception.getMessage().contains(source.token()), exception.getMessage());
    Map<String, String> extras = takeExtras();
    Assertions.assertEquals(
        Reason.UNSUPPORTED_FORMAT_VERSION.code(), extras.get(IcebergEncryptionAuditInfos.REASON));
    Assertions.assertTrue(
        exception
            .getMessage()
            .contains("decisionId=" + extras.get(IcebergEncryptionAuditInfos.DECISION_ID)));
    Assertions.assertEquals(
        IllegalArgumentException.class.getName(),
        extras.get(IcebergEncryptionAuditInfos.ERROR_TYPE));
    Assertions.assertEquals(
        KmsValidationStatus.NOT_ATTEMPTED.name(),
        extras.get(IcebergEncryptionAuditInfos.KMS_VALIDATION));
    Assertions.assertEquals(0, validationCalls.get());
    verifyNoInteractions(policyDispatcher);
    verify(delegate, never()).createTable(any(), any(), any(), any(), any(), any(), any(), any());
  }

  private Table create(Map<String, String> properties) {
    return dispatcher.createTable(
        TABLE, COLUMNS, "comment", properties, PARTITIONS, DISTRIBUTION, SORT_ORDERS, INDEXES);
  }

  @SuppressWarnings("unchecked")
  private Map<String, String> forwardedProperties() {
    ArgumentCaptor<Map<String, String>> properties = ArgumentCaptor.forClass(Map.class);
    verify(delegate)
        .createTable(
            same(TABLE),
            same(COLUMNS),
            eq("comment"),
            properties.capture(),
            same(PARTITIONS),
            same(DISTRIBUTION),
            same(SORT_ORDERS),
            same(INDEXES));
    return properties.getValue();
  }

  private Map<String, String> takeExtras() {
    Map<String, String> extras = RequestContext.takeAuditExtras();
    Assertions.assertFalse(extras.isEmpty(), "Expected encryption extras to be stashed");
    return extras;
  }

  private void associateOnSchema(PolicyEntity... policies) {
    when(policyDispatcher.listPolicyInfosForMetadataObject(
            eq(METALAKE), argThat(object -> object.type() == MetadataObject.Type.SCHEMA)))
        .thenReturn(policies);
  }

  private void useCatalogProperty(String key, String value) {
    Map<String, String> properties = new HashMap<>(catalog.properties());
    properties.put(key, value);
    when(catalog.properties()).thenReturn(properties);
  }

  private void withoutCatalogProperty(String key) {
    Map<String, String> properties = new HashMap<>(catalog.properties());
    properties.remove(key);
    when(catalog.properties()).thenReturn(properties);
  }

  private static IcebergEncryptionDecision.KmsValidationStatus validateWithProperties(
      KmsReference key, boolean enabled, boolean supportsWrapping) {
    KmsKeyProperties properties = mock(KmsKeyProperties.class);
    when(properties.enabled()).thenReturn(enabled);
    when(properties.supportsWrapping()).thenReturn(supportsWrapping);
    KmsClient client = ignored -> Optional.of(properties);
    return IcebergEncryptionKmsKeyValidators.fromClient(client).validate(key);
  }

  private static Map<String, String> keyProperties(String id) {
    return new HashMap<>(
        ImmutableMap.of(
            IcebergEncryptionPolicyEvaluator.ENCRYPTION_KEY_PROVIDER,
            SOURCE,
            IcebergEncryptionPolicyEvaluator.ENCRYPTION_KEY_ID,
            id));
  }

  private static PolicyEntity policy(
      String name, boolean required, Enforcement enforcement, String allowedId) {
    PolicyContent content =
        PolicyContents.icebergEncryption(
            IcebergEncryptionContent.CURRENT_SCHEMA_VERSION,
            required,
            Collections.singletonList(new KmsReference(SOURCE, allowedId)),
            enforcement);
    PolicyEntity policy = mock(PolicyEntity.class);
    when(policy.id()).thenReturn((long) name.hashCode());
    when(policy.name()).thenReturn(name);
    when(policy.enabled()).thenReturn(true);
    when(policy.policyType()).thenReturn(Policy.BuiltInType.ICEBERG_ENCRYPTION);
    when(policy.content()).thenReturn(content);
    return policy;
  }
}
