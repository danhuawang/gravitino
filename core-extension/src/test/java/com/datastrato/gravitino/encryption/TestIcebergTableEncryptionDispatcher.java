/*
 * Copyright 2026 Datastrato Pvt Ltd.
 */
package com.datastrato.gravitino.encryption;

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
import org.apache.gravitino.encryption.IcebergEncryptionDecision;
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

    when(catalogManager.loadCatalog(CATALOG)).thenReturn(catalog);
    when(catalog.provider()).thenReturn("lakehouse-iceberg");
    when(catalog.properties()).thenReturn(ImmutableMap.of("encryption-kms-source", SOURCE));
    when(delegate.createTable(any(), any(), any(), any(), any(), any(), any(), any()))
        .thenReturn(createdTable);

    IcebergEncryptionPolicyResolver resolver =
        new IcebergEncryptionPolicyResolver(policyDispatcher, new SinglePolicyChecker());
    dispatcher =
        new IcebergTableEncryptionDispatcher(
            delegate,
            catalogManager,
            resolver,
            new IcebergEncryptionPolicyEvaluator(
                key -> {
                  validatedKey.set(key);
                  validationCalls.incrementAndGet();
                  return IcebergEncryptionDecision.KmsValidationStatus.valueOf(
                      validationStatus.get().name());
                }));
  }

  @Test
  void testNullConstructorAndFactoryArgumentsAreRejectedAsIllegalArguments() {
    IcebergEncryptionPolicyResolver resolver =
        new IcebergEncryptionPolicyResolver(policyDispatcher, new SinglePolicyChecker());
    IcebergEncryptionPolicyEvaluator evaluator =
        new IcebergEncryptionPolicyEvaluator(
            key -> IcebergEncryptionDecision.KmsValidationStatus.VALID);

    Assertions.assertEquals(
        "dispatcher cannot be null",
        Assertions.assertThrows(
                IllegalArgumentException.class,
                () ->
                    new IcebergTableEncryptionDispatcher(null, catalogManager, resolver, evaluator))
            .getMessage());
    Assertions.assertEquals(
        "catalogManager cannot be null",
        Assertions.assertThrows(
                IllegalArgumentException.class,
                () -> new IcebergTableEncryptionDispatcher(delegate, null, resolver, evaluator))
            .getMessage());
    Assertions.assertEquals(
        "policyResolver cannot be null",
        Assertions.assertThrows(
                IllegalArgumentException.class,
                () ->
                    new IcebergTableEncryptionDispatcher(delegate, catalogManager, null, evaluator))
            .getMessage());
    Assertions.assertEquals(
        "policyEvaluator cannot be null",
        Assertions.assertThrows(
                IllegalArgumentException.class,
                () ->
                    new IcebergTableEncryptionDispatcher(
                        delegate,
                        catalogManager,
                        resolver,
                        (IcebergEncryptionPolicyEvaluator) null))
            .getMessage());

    Assertions.assertEquals(
        "kmsClientRegistry cannot be null",
        Assertions.assertThrows(
                IllegalArgumentException.class,
                () -> IcebergTableEncryptionDispatcher.registryValidator(null))
            .getMessage());
    Assertions.assertEquals(
        "clientResolver cannot be null",
        Assertions.assertThrows(
                IllegalArgumentException.class,
                () -> IcebergTableEncryptionDispatcher.clientResolverValidator(null))
            .getMessage());
    Assertions.assertEquals(
        "kmsClient cannot be null",
        Assertions.assertThrows(
                IllegalArgumentException.class,
                () -> IcebergTableEncryptionDispatcher.clientValidator(null))
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

    Assertions.assertEquals("3", forwardedProperties().get("format-version"));
    Assertions.assertEquals("inherited", takeExtras().get(IcebergEncryptionAuditInfos.POLICY_NAME));
  }

  @Test
  void testInheritedSchemaAssociationAppliesPolicyDuringCreateWithNullProperties() {
    PolicyEntity policy = policy("inherited", false, Enforcement.REPORT, KEY_ID);
    associateOnSchema(policy);

    Assertions.assertSame(createdTable, create(null));

    Map<String, String> forwarded = forwardedProperties();
    Assertions.assertEquals("3", forwarded.get("format-version"));
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

  @Test
  void testReportMissingRequiredKey() {
    PolicyEntity reportPolicy = policy("report", true, Enforcement.REPORT, KEY_ID);
    associateOnSchema(reportPolicy);

    Assertions.assertSame(createdTable, create(Collections.emptyMap()));

    Map<String, String> reportEvent = takeExtras();
    Assertions.assertEquals("VIOLATION", reportEvent.get(IcebergEncryptionAuditInfos.COMPLIANCE));
    Assertions.assertEquals(
        Reason.KEY_REQUIRED.code(), reportEvent.get(IcebergEncryptionAuditInfos.REASON));
    Assertions.assertEquals("3", forwardedProperties().get("format-version"));
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
  void testValidKeyIsSanitizedAndIcebergV3IsStamped() {
    PolicyEntity policy = policy("valid", true, Enforcement.DENY_CREATE, KEY_ID);
    associateOnSchema(policy);
    Map<String, String> properties = keyProperties(KEY_ID);
    properties.put("format-version", "2");
    properties.put("unrelated", "preserved");

    Assertions.assertSame(createdTable, create(properties));

    Map<String, String> forwarded = forwardedProperties();
    Assertions.assertEquals("3", forwarded.get("format-version"));
    Assertions.assertEquals(SOURCE, forwarded.get("encryption.key-provider"));
    Assertions.assertEquals(KEY_ID, forwarded.get("encryption.key-id"));
    Assertions.assertFalse(forwarded.containsKey("encryption.kms-api"));
    Assertions.assertFalse(forwarded.containsKey("encryption.key-source"));
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
    Map<String, String> properties =
        ImmutableMap.of(IcebergEncryptionPolicyEvaluator.ENCRYPTION_KEY_ID, KEY_ID);

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
        IcebergTableEncryptionDispatcher.clientValidator(ignored -> Optional.empty())
            .validate(key));
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
        IcebergTableEncryptionDispatcher.clientValidator(invalidClient).validate(key));
    Assertions.assertEquals(
        IcebergEncryptionDecision.KmsValidationStatus.UNAVAILABLE,
        IcebergTableEncryptionDispatcher.clientValidator(unavailableClient).validate(key));
    Assertions.assertEquals(
        IcebergEncryptionDecision.KmsValidationStatus.UNAVAILABLE,
        IcebergTableEncryptionDispatcher.clientValidator(unexpectedFailureClient).validate(key));
  }

  @Test
  void testRegistryValidatorMapsGetClientFailures() {
    KmsReference key = new KmsReference(SOURCE, KEY_ID);
    KmsClientRegistry unknownProviderRegistry = new KmsClientRegistry(new Config(false) {});
    KmsClientRegistry closedRegistry = new KmsClientRegistry(new Config(false) {});
    closedRegistry.close();

    Assertions.assertEquals(
        IcebergEncryptionDecision.KmsValidationStatus.INVALID,
        IcebergTableEncryptionDispatcher.registryValidator(unknownProviderRegistry).validate(key));
    Assertions.assertEquals(
        IcebergEncryptionDecision.KmsValidationStatus.UNAVAILABLE,
        IcebergTableEncryptionDispatcher.registryValidator(closedRegistry).validate(key));
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

  private static IcebergEncryptionDecision.KmsValidationStatus validateWithProperties(
      KmsReference key, boolean enabled, boolean supportsWrapping) {
    KmsKeyProperties properties = mock(KmsKeyProperties.class);
    when(properties.enabled()).thenReturn(enabled);
    when(properties.supportsWrapping()).thenReturn(supportsWrapping);
    KmsClient client = ignored -> Optional.of(properties);
    return IcebergTableEncryptionDispatcher.clientValidator(client).validate(key);
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
