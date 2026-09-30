/*
 * Copyright 2026 Datastrato Inc.
 */
package org.apache.gravitino.iceberg.service;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.same;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.google.common.base.Suppliers;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.stream.Stream;
import javax.annotation.Nullable;
import org.apache.gravitino.EntityFieldLimits;
import org.apache.gravitino.NameIdentifier;
import org.apache.gravitino.catalog.lakehouse.iceberg.IcebergCatalogBackend;
import org.apache.gravitino.catalog.lakehouse.iceberg.IcebergConstants;
import org.apache.gravitino.catalog.lakehouse.iceberg.IcebergFormatVersionResolution;
import org.apache.gravitino.encryption.AtomicIntegrityCapability;
import org.apache.gravitino.encryption.EncryptionAdmissionService;
import org.apache.gravitino.encryption.EncryptionAdmissionService.AdmissionRequest;
import org.apache.gravitino.encryption.EncryptionAdmissionService.EncryptionAdmission;
import org.apache.gravitino.encryption.EncryptionAdmissionService.Operation;
import org.apache.gravitino.encryption.EncryptionAdmissionService.Outcome;
import org.apache.gravitino.encryption.IcebergEncryptionKmsKeyValidators;
import org.apache.gravitino.encryption.IcebergEncryptionPolicyEvaluator;
import org.apache.gravitino.encryption.IcebergEncryptionPolicyResolver;
import org.apache.gravitino.encryption.MetadataIntegrityFailureClassifier.CatalogFacts;
import org.apache.gravitino.encryption.kms.KmsClient;
import org.apache.gravitino.encryption.kms.KmsClientRegistry;
import org.apache.gravitino.encryption.kms.KmsKeyProperties;
import org.apache.gravitino.encryption.kms.KmsReference;
import org.apache.gravitino.exceptions.EncryptionKeyRepointException;
import org.apache.gravitino.exceptions.MetadataIntegrityException;
import org.apache.gravitino.listener.api.event.IcebergEncryptionAuditInfos;
import org.apache.gravitino.listener.api.event.IcebergEncryptionAuditInfos.Reason;
import org.apache.gravitino.listener.api.event.IcebergRequestContext;
import org.apache.gravitino.meta.PolicyEntity;
import org.apache.gravitino.policy.IcebergEncryptionContent;
import org.apache.gravitino.policy.IcebergEncryptionContent.Enforcement;
import org.apache.gravitino.policy.Policy;
import org.apache.gravitino.policy.PolicyContents;
import org.apache.gravitino.utils.RequestContext;
import org.apache.iceberg.CatalogProperties;
import org.apache.iceberg.PartitionSpec;
import org.apache.iceberg.Schema;
import org.apache.iceberg.TableMetadata;
import org.apache.iceberg.TableMetadataParser;
import org.apache.iceberg.TableProperties;
import org.apache.iceberg.catalog.Namespace;
import org.apache.iceberg.catalog.TableIdentifier;
import org.apache.iceberg.exceptions.BadRequestException;
import org.apache.iceberg.exceptions.ServiceUnavailableException;
import org.apache.iceberg.inmemory.InMemoryFileIO;
import org.apache.iceberg.rest.requests.ImmutableRegisterTableRequest;
import org.apache.iceberg.rest.requests.RegisterTableRequest;
import org.apache.iceberg.rest.responses.LoadTableResponse;
import org.apache.iceberg.types.Types;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.EnumSource;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.ArgumentCaptor;
import org.mockito.invocation.InvocationOnMock;

/**
 * Tests the governed registration path: one read, one admission and publication of the object that
 * was read, with the backend reached only after admission.
 */
public class TestGovernedRegistrationService {

  private static final String METALAKE = "metalake";
  private static final String CATALOG = "catalog";
  private static final String SOURCE = "openbao";
  private static final String KEY_ID = "customer-pii-v1";
  // A key the KMS accepts but the test policy's allowlist does not name.
  private static final String UNLISTED_KEY = "unlisted-key-v1";
  private static final String POLICY_NAME = "customer-encryption";
  private static final Namespace NAMESPACE = Namespace.of("schema");
  private static final TableIdentifier TABLE = TableIdentifier.of(NAMESPACE, "table");
  private static final String LOCATION_PREFIX = "memory://governed-registration/schema/table/";

  private IcebergCatalogWrapperManager catalogWrapperManager;
  private CatalogWrapperForREST catalogWrapper;
  private IcebergRequestContext context;
  private IcebergEncryptionPolicyResolver resolver;
  private KmsClientRegistry kmsRegistry;
  private Map<String, String> catalogProperties;
  // The metadata file the next request names; set by metadataFile.
  private String currentLocation;

  @AfterEach
  void clearRequestContext() {
    RequestContext.clear();
  }

  @BeforeEach
  void setUp() {
    catalogWrapperManager = mock(IcebergCatalogWrapperManager.class);
    catalogWrapper = mock(CatalogWrapperForREST.class);
    when(catalogWrapperManager.getCatalogWrapper(CATALOG)).thenReturn(catalogWrapper);
    context = mock(IcebergRequestContext.class);
    when(context.catalogName()).thenReturn(CATALOG);
    when(context.requestCredentialVending()).thenReturn(true);
    resolver = mock(IcebergEncryptionPolicyResolver.class);
    when(resolver.resolve(any(NameIdentifier.class))).thenReturn(Optional.empty());
    kmsRegistry = mock(KmsClientRegistry.class);
    catalogProperties = new HashMap<>();
    catalogProperties.put(IcebergConstants.ENCRYPTION_KMS_SOURCE, SOURCE);
    catalogProperties.put(IcebergConstants.CATALOG_BACKEND, "jdbc");
  }

  @Test
  void testAdmittedRegistrationPublishesTheObjectThatWasRead() {
    KmsClient client = validKey();
    TableMetadata metadata = metadataFile("admitted", KEY_ID);
    LoadTableResponse response = mock(LoadTableResponse.class);
    when(catalogWrapper.registerTable(TABLE, metadata, false, true)).thenReturn(response);
    EncryptionAdmissionService admission = spy(admissionService());

    Assertions.assertSame(
        response, service(admission).register(context, NAMESPACE, request(false)));

    verify(catalogWrapper, times(1)).readTableMetadata(metadata.metadataFileLocation());
    verify(catalogWrapper).registerTable(eq(TABLE), same(metadata), eq(false), eq(true));
    AdmissionRequest admitted = admittedRequest(admission);
    Assertions.assertEquals(Operation.REGISTER, admitted.operation());
    Assertions.assertEquals(
        NameIdentifier.of(METALAKE, CATALOG, "schema", "table"), admitted.table());
    Assertions.assertEquals(
        Map.of(IcebergConstants.ENCRYPTION_KEY_ID, KEY_ID, IcebergConstants.FORMAT_VERSION, "3"),
        admitted.requestedProperties());
    verify(client, times(1)).getKeyProperties(new KmsReference(SOURCE, KEY_ID));
    Assertions.assertEquals(
        Reason.NO_POLICY.code(),
        RequestContext.takeAuditExtras().get(IcebergEncryptionAuditInfos.REASON));
  }

  @Test
  void testOverwriteReadmitsTheSameKeyOnce() {
    KmsClient client = validKey();
    TableMetadata metadata = metadataFile("overwrite", KEY_ID);
    LoadTableResponse response = mock(LoadTableResponse.class);
    when(catalogWrapper.registerTable(TABLE, metadata, true, true)).thenReturn(response);
    EncryptionAdmissionService admission = spy(admissionService());

    Assertions.assertSame(response, service(admission).register(context, NAMESPACE, request(true)));

    AdmissionRequest admitted = admittedRequest(admission);
    Assertions.assertEquals(Operation.OVERWRITE, admitted.operation());
    Assertions.assertEquals(
        KEY_ID, admitted.requestedProperties().get(IcebergConstants.ENCRYPTION_KEY_ID));
    verify(client, times(1)).getKeyProperties(new KmsReference(SOURCE, KEY_ID));
    verify(catalogWrapper).registerTable(eq(TABLE), same(metadata), eq(true), eq(true));
  }

  @Test
  void testPolicyRefusalStashesExtrasAndNeverReachesTheBackend() {
    PolicyEntity policy = requiredPolicy();
    when(resolver.resolve(any(NameIdentifier.class))).thenReturn(Optional.of(policy));
    metadataFile("refused", null);

    BadRequestException refusal =
        Assertions.assertThrows(
            BadRequestException.class,
            () -> service(admissionService()).register(context, NAMESPACE, request(false)));

    Assertions.assertTrue(refusal.getMessage().contains("decisionId="), refusal.getMessage());
    verify(catalogWrapper, never())
        .registerTable(any(TableIdentifier.class), any(), anyBoolean(), anyBoolean());
    Map<String, String> extras = RequestContext.takeAuditExtras();
    Assertions.assertEquals(
        BadRequestException.class.getName(), extras.get(IcebergEncryptionAuditInfos.ERROR_TYPE));
    verifyNoInteractions(kmsRegistry);
  }

  @Test
  void testUnavailableKmsIsServiceUnavailableAndNeverReachesTheBackend() {
    KmsClient client = mock(KmsClient.class);
    when(kmsRegistry.getClient(new KmsReference(SOURCE, KEY_ID))).thenReturn(client);
    when(client.getKeyProperties(any())).thenThrow(new IllegalStateException("kms down"));
    metadataFile("unavailable", KEY_ID);

    Assertions.assertThrows(
        ServiceUnavailableException.class,
        () -> service(admissionService()).register(context, NAMESPACE, request(false)));

    verify(catalogWrapper, never())
        .registerTable(any(TableIdentifier.class), any(), anyBoolean(), anyBoolean());
    Assertions.assertEquals(
        ServiceUnavailableException.class.getName(),
        RequestContext.takeAuditExtras().get(IcebergEncryptionAuditInfos.ERROR_TYPE));
  }

  /**
   * U-1 D4: an admitted overwrite of an encrypted table without an integrity row is refused by the
   * backend, not by admission. The refusal is rethrown unchanged with the failure audit facts.
   */
  @Test
  void testBackendRefusalAfterAdmittedOverwriteStashesFailureExtrasAndRethrows() {
    validKey();
    TableMetadata metadata = metadataFile("not-enrolled", KEY_ID);
    MetadataIntegrityException notEnrolled =
        new MetadataIntegrityException("Encrypted table %s is not enrolled", TABLE);
    when(catalogWrapper.registerTable(TABLE, metadata, true, true)).thenThrow(notEnrolled);
    EncryptionAdmissionService admission = spy(admissionService());

    Assertions.assertSame(
        notEnrolled,
        Assertions.assertThrows(
            MetadataIntegrityException.class,
            () -> service(admission).register(context, NAMESPACE, request(true))));

    verify(admission, times(1)).admit(any());
    Map<String, String> extras = RequestContext.takeAuditExtras();
    Assertions.assertEquals(
        Reason.TABLE_CREATE_FAILED.code(), extras.get(IcebergEncryptionAuditInfos.REASON));
    Assertions.assertEquals(
        MetadataIntegrityException.class.getName(),
        extras.get(IcebergEncryptionAuditInfos.ERROR_TYPE));
  }

  /**
   * A backend's key-repoint refusal is recorded under its own type and rethrown as it is, like any
   * other backend refusal: the wire conversion belongs to {@link IcebergExceptionMapper}, which
   * every route shares, so the typed exception stays recognisable up to it.
   */
  @Test
  void testKeyRepointRefusalIsRecordedTypedAndRethrownUnchanged() {
    validKey();
    TableMetadata metadata = metadataFile("repoint", KEY_ID);
    EncryptionKeyRepointException repoint = repointRefusal();
    when(catalogWrapper.registerTable(TABLE, metadata, true, true)).thenThrow(repoint);
    EncryptionAdmissionService admission = spy(admissionService());

    EncryptionKeyRepointException thrown =
        Assertions.assertThrows(
            EncryptionKeyRepointException.class,
            () -> service(admission).register(context, NAMESPACE, request(true)));

    Assertions.assertSame(repoint, thrown);

    Map<String, String> extras = RequestContext.takeAuditExtras();
    Assertions.assertEquals(
        Reason.TABLE_CREATE_FAILED.code(), extras.get(IcebergEncryptionAuditInfos.REASON));
    Assertions.assertEquals(
        EncryptionKeyRepointException.class.getName(),
        extras.get(IcebergEncryptionAuditInfos.ERROR_TYPE));
  }

  /**
   * An overwrite that would strip a table's key names no key, so admission evaluates nothing, yet
   * the backend's key-repoint refusal is the integrity fact the audit record exists to carry. On a
   * governed catalog it is recorded as {@code METADATA_INTEGRITY_FAILED} with the catalog's facts.
   */
  @Test
  void testKeyStripRefusedByTheBackendIsRecordedWithoutAnAdmission() {
    TableMetadata metadata = metadataFile("strip", null);
    when(catalogWrapper.registerTable(TABLE, metadata, true, true)).thenThrow(repointRefusal());
    EncryptionAdmissionService admission = spy(admissionService());
    List<EncryptionAdmission> results = new ArrayList<>();
    doAnswer(invocation -> recordResult(results, invocation)).when(admission).admit(any());

    Assertions.assertThrows(
        EncryptionKeyRepointException.class,
        () -> service(admission, true).register(context, NAMESPACE, request(true)));

    Assertions.assertFalse(results.get(0).evaluated());
    Map<String, String> extras = RequestContext.takeAuditExtras();
    Assertions.assertEquals(
        Reason.METADATA_INTEGRITY_FAILED.code(), extras.get(IcebergEncryptionAuditInfos.REASON));
    Assertions.assertEquals(
        EncryptionKeyRepointException.class.getName(),
        extras.get(IcebergEncryptionAuditInfos.ERROR_TYPE));
    Assertions.assertEquals(
        IcebergCatalogBackend.JDBC.name(), extras.get(IcebergEncryptionAuditInfos.BACKEND));
    Assertions.assertEquals("true", extras.get(IcebergEncryptionAuditInfos.ENCRYPTION_GOVERNED));
  }

  /**
   * On an evaluated admission the integrity reason outranks the create-failure reason the admission
   * recorded for the same refusal, and takes its error type.
   */
  @Test
  void testKeyRepointRefusalOnAGovernedCatalogIsRecordedAsAnIntegrityFailure() {
    validKey();
    TableMetadata metadata = metadataFile("rekey", KEY_ID);
    when(catalogWrapper.registerTable(TABLE, metadata, true, true)).thenThrow(repointRefusal());

    Assertions.assertThrows(
        EncryptionKeyRepointException.class,
        () -> service(admissionService(), true).register(context, NAMESPACE, request(true)));

    Map<String, String> extras = RequestContext.takeAuditExtras();
    Assertions.assertEquals(
        Reason.METADATA_INTEGRITY_FAILED.code(), extras.get(IcebergEncryptionAuditInfos.REASON));
    Assertions.assertEquals(
        EncryptionKeyRepointException.class.getName(),
        extras.get(IcebergEncryptionAuditInfos.ERROR_TYPE));
  }

  /** An ungoverned catalog records no integrity facts, even where the backend refused a repoint. */
  @Test
  void testKeyStripOnAnUngovernedCatalogRecordsNothing() {
    TableMetadata metadata = metadataFile("strip-ungoverned", null);
    when(catalogWrapper.registerTable(TABLE, metadata, true, true)).thenThrow(repointRefusal());

    Assertions.assertThrows(
        EncryptionKeyRepointException.class,
        () -> service(admissionService(), false).register(context, NAMESPACE, request(true)));

    Assertions.assertTrue(RequestContext.takeAuditExtras().isEmpty());
  }

  /** A failed audit-facts probe cannot prevent the backend registration from succeeding. */
  @Test
  void testCatalogFactsFailureDoesNotPreventRegistration() {
    TableMetadata metadata = metadataFile("facts-unavailable", null);
    LoadTableResponse response = mock(LoadTableResponse.class);
    when(catalogWrapper.registerTable(TABLE, metadata, false, true)).thenReturn(response);

    Assertions.assertSame(
        response,
        serviceWithFailingCatalogFacts(admissionService())
            .register(context, NAMESPACE, request(false)));
  }

  /** A failed audit-facts probe cannot replace the backend refusal the caller must receive. */
  @Test
  void testCatalogFactsFailureDoesNotReplaceRegistrationFailure() {
    TableMetadata metadata = metadataFile("facts-unavailable-failure", null);
    MetadataIntegrityException failure = new MetadataIntegrityException("tampered");
    when(catalogWrapper.registerTable(TABLE, metadata, false, true)).thenThrow(failure);

    Assertions.assertSame(
        failure,
        Assertions.assertThrows(
            RuntimeException.class,
            () ->
                serviceWithFailingCatalogFacts(admissionService())
                    .register(context, NAMESPACE, request(false))));
  }

  /** The refusal {@code IcebergMetadataIntegrity.requireSameEncryptionKey} raises on a repoint. */
  private static EncryptionKeyRepointException repointRefusal() {
    return new EncryptionKeyRepointException(
        "Cannot change the encryption key of table %s by re-registering it", TABLE);
  }

  /**
   * A custom backend registers through the request form with no integrity binding; that is safe
   * only because it reports no atomic integrity, so encrypted input is refused before the backend.
   */
  @Test
  void testEncryptedRegistrationOnCustomBackendIsRefusedBeforeTheBackend() {
    catalogProperties.put(IcebergConstants.CATALOG_BACKEND, "custom");
    validKey();
    metadataFile("custom-encrypted", KEY_ID);

    BadRequestException refusal =
        Assertions.assertThrows(
            BadRequestException.class,
            () ->
                service(admissionService(AtomicIntegrityCapability.UNSUPPORTED))
                    .register(context, NAMESPACE, request(false)));

    Assertions.assertTrue(
        refusal.getMessage().contains("integrity store unavailable"), refusal.getMessage());
    verify(catalogWrapper, never())
        .registerTable(any(TableIdentifier.class), any(), anyBoolean(), anyBoolean());
    verify(catalogWrapper, never())
        .registerTable(any(Namespace.class), any(RegisterTableRequest.class), anyBoolean());
  }

  /**
   * A blank key in the file reaches admission as written, so both enforcement modes refuse it with
   * {@code KEY_REFERENCE_INVALID} and an audit record, before any KMS call or backend write.
   */
  @ParameterizedTest
  @EnumSource(Enforcement.class)
  void testBlankFileKeyIsRefusedByAdmissionInEveryMode(Enforcement enforcement) {
    PolicyEntity policy = requiredPolicy(enforcement);
    when(resolver.resolve(any(NameIdentifier.class))).thenReturn(Optional.of(policy));
    metadataFile("blank-key", " ");
    EncryptionAdmissionService admission = spy(admissionService());

    Assertions.assertThrows(
        BadRequestException.class,
        () -> service(admission).register(context, NAMESPACE, request(false)));

    Assertions.assertEquals(
        Map.of(IcebergConstants.ENCRYPTION_KEY_ID, " ", IcebergConstants.FORMAT_VERSION, "3"),
        admittedRequest(admission).requestedProperties());
    Map<String, String> extras = RequestContext.takeAuditExtras();
    Assertions.assertEquals(
        Reason.KEY_REFERENCE_INVALID.code(), extras.get(IcebergEncryptionAuditInfos.REASON));
    Assertions.assertEquals(
        BadRequestException.class.getName(), extras.get(IcebergEncryptionAuditInfos.ERROR_TYPE));
    verify(catalogWrapper, never())
        .registerTable(any(TableIdentifier.class), any(), anyBoolean(), anyBoolean());
    verify(catalogWrapper, never())
        .registerTable(any(Namespace.class), any(RegisterTableRequest.class), anyBoolean());
    verifyNoInteractions(kmsRegistry);
  }

  /**
   * A file carrying a leftover protocol property is judged on it, exactly as a create carrying it
   * is: admission refuses it with {@code KEY_REFERENCE_INVALID} before the backend.
   */
  @ParameterizedTest
  @ValueSource(
      strings = {IcebergConstants.ENCRYPTION_KMS_API, IcebergConstants.ENCRYPTION_KEY_SOURCE})
  void testFileCarryingALeftoverProtocolPropertyIsRefusedLikeACreate(String property) {
    when(resolver.resolve(any(NameIdentifier.class))).thenReturn(Optional.empty());
    TableMetadata metadata =
        TableMetadata.newTableMetadata(
            new Schema(Types.NestedField.required(1, "id", Types.LongType.get())),
            PartitionSpec.unpartitioned(),
            "memory://governed-registration/schema/table",
            Map.of(TableProperties.FORMAT_VERSION, "3", property, "openbao"));
    String location = LOCATION_PREFIX + "leftover.metadata.json";
    InMemoryFileIO io = new InMemoryFileIO();
    io.addFile(location, TableMetadataParser.toJson(metadata).getBytes(StandardCharsets.UTF_8));
    when(catalogWrapper.readTableMetadata(location))
        .thenReturn(TableMetadataParser.read(io, location));
    currentLocation = location;

    Assertions.assertThrows(
        BadRequestException.class,
        () -> service(admissionService()).register(context, NAMESPACE, request(false)));

    Assertions.assertEquals(
        Reason.KEY_REFERENCE_INVALID.code(),
        RequestContext.takeAuditExtras().get(IcebergEncryptionAuditInfos.REASON));
    verify(catalogWrapper, never())
        .registerTable(any(TableIdentifier.class), any(), anyBoolean(), anyBoolean());
  }

  @Test
  void testOversizedTableNameIsRefusedBeforeReadingTheFile() {
    RegisterTableRequest request =
        ImmutableRegisterTableRequest.builder()
            .name("t".repeat(EntityFieldLimits.MAX_NAME_LENGTH + 1))
            .metadataLocation(LOCATION_PREFIX + "oversized-name.metadata.json")
            .build();

    Assertions.assertThrows(
        IllegalArgumentException.class,
        () -> service(admissionService()).register(context, NAMESPACE, request));

    verify(catalogWrapper, never()).readTableMetadata(any());
    verify(catalogWrapper, never())
        .registerTable(any(TableIdentifier.class), any(), anyBoolean(), anyBoolean());
  }

  @Test
  void testOversizedColumnFieldsAreRefusedBeforeAdmission() {
    List<Types.NestedField> oversized =
        List.of(
            Types.NestedField.required(
                1, "c".repeat(EntityFieldLimits.MAX_NAME_LENGTH + 1), Types.LongType.get()),
            Types.NestedField.optional(
                1,
                "id",
                Types.LongType.get(),
                "d".repeat(EntityFieldLimits.MAX_COMMENT_LENGTH + 1)));
    for (Types.NestedField field : oversized) {
      TableMetadata metadata =
          TableMetadata.newTableMetadata(
              new Schema(field),
              PartitionSpec.unpartitioned(),
              "memory://governed-registration/schema/table",
              Map.of(TableProperties.FORMAT_VERSION, "3"));
      String location = LOCATION_PREFIX + "oversized-column.metadata.json";
      when(catalogWrapper.readTableMetadata(location)).thenReturn(metadata);
      currentLocation = location;
      EncryptionAdmissionService admission = spy(admissionService());

      Assertions.assertThrows(
          IllegalArgumentException.class,
          () -> service(admission).register(context, NAMESPACE, request(false)));

      verify(admission, never()).admit(any());
    }
    verify(catalogWrapper, never())
        .registerTable(any(TableIdentifier.class), any(), anyBoolean(), anyBoolean());
  }

  /** Registration takes its key from the file; a catalog override key is not injected into it. */
  @Test
  void testCatalogKeyIsNotInjectedIntoAnUnencryptedRegistration() {
    catalogProperties.put(
        CatalogProperties.TABLE_OVERRIDE_PREFIX + TableProperties.ENCRYPTION_TABLE_KEY, KEY_ID);
    TableMetadata metadata = metadataFile("plain", null);
    when(catalogWrapper.registerTable(TABLE, metadata, false, true))
        .thenReturn(mock(LoadTableResponse.class));
    EncryptionAdmissionService admission = spy(admissionService());
    List<EncryptionAdmission> results = new ArrayList<>();
    doAnswer(invocation -> recordResult(results, invocation)).when(admission).admit(any());

    service(admission).register(context, NAMESPACE, request(false));

    Assertions.assertEquals(
        Map.of(IcebergConstants.FORMAT_VERSION, "3"),
        admittedRequest(admission).requestedProperties());
    Assertions.assertEquals(Outcome.ADMITTED_UNGOVERNED, results.get(0).outcome());
    verify(catalogWrapper).registerTable(eq(TABLE), same(metadata), eq(false), eq(true));
    verifyNoInteractions(kmsRegistry);
  }

  /**
   * A registration is refused on the file's own facts (R7): a KMS provider other than the
   * catalog's, or a key at a format version below 3, is refused before any KMS call or backend
   * write even though the key itself would validate. The version has to travel from the file for
   * the second refusal to happen: Iceberg parses and registers a keyed version 2 file without
   * complaint.
   */
  @ParameterizedTest(name = "{0} overwrite={6}")
  @MethodSource("filesRefusedOnTheirOwnFacts")
  void testAFileIsRefusedOnItsOwnFacts(
      String row,
      @Nullable String provider,
      int formatVersion,
      Reason refusal,
      String messageFragment,
      Class<? extends RuntimeException> expected,
      boolean overwrite) {
    KmsClient client = validKey();
    metadataFile("own-facts", KEY_ID, provider, formatVersion);
    EncryptionAdmissionService admission = spy(admissionService());

    RuntimeException thrown =
        Assertions.assertThrows(
            expected,
            () -> service(admission).register(context, NAMESPACE, request(overwrite)),
            row);

    Assertions.assertTrue(thrown.getMessage().contains(messageFragment), thrown.getMessage());
    AdmissionRequest admitted = admittedRequest(admission);
    Map<String, String> fileProperties = new HashMap<>();
    fileProperties.put(IcebergConstants.FORMAT_VERSION, String.valueOf(formatVersion));
    fileProperties.put(IcebergConstants.ENCRYPTION_KEY_ID, KEY_ID);
    if (provider != null) {
      fileProperties.put(IcebergConstants.ENCRYPTION_KEY_PROVIDER, provider);
    }
    Assertions.assertEquals(fileProperties, admitted.requestedProperties(), row);
    Assertions.assertEquals(
        overwrite ? Operation.OVERWRITE : Operation.REGISTER, admitted.operation());
    Map<String, String> extras = RequestContext.takeAuditExtras();
    Assertions.assertEquals(refusal.code(), extras.get(IcebergEncryptionAuditInfos.REASON));
    Assertions.assertEquals(expected.getName(), extras.get(IcebergEncryptionAuditInfos.ERROR_TYPE));
    // A file naming no provider is completed from the catalog's KMS source before the refusal.
    Assertions.assertEquals(
        provider == null ? SOURCE : provider, extras.get(IcebergEncryptionAuditInfos.PROVIDER));
    verify(kmsRegistry, never()).getClient(any());
    verify(client, never()).getKeyProperties(any());
    verify(catalogWrapper, never())
        .registerTable(any(TableIdentifier.class), any(), anyBoolean(), anyBoolean());
  }

  /**
   * A file that names the catalog's own KMS provider reaches admission with that provider and is
   * validated once and published as read.
   */
  @ParameterizedTest
  @ValueSource(booleans = {false, true})
  void testFileNamingTheCatalogKmsProviderIsAdmittedAndPublishedWithThatProvider(
      boolean overwrite) {
    KmsClient client = validKey();
    TableMetadata metadata = metadataFile("same-provider", KEY_ID, SOURCE);
    when(catalogWrapper.registerTable(TABLE, metadata, overwrite, true))
        .thenReturn(mock(LoadTableResponse.class));
    EncryptionAdmissionService admission = spy(admissionService());

    service(admission).register(context, NAMESPACE, request(overwrite));

    Assertions.assertEquals(
        Map.of(
            IcebergConstants.ENCRYPTION_KEY_ID,
            KEY_ID,
            IcebergConstants.ENCRYPTION_KEY_PROVIDER,
            SOURCE,
            IcebergConstants.FORMAT_VERSION,
            "3"),
        admittedRequest(admission).requestedProperties());
    verify(client, times(1)).getKeyProperties(new KmsReference(SOURCE, KEY_ID));
    verify(catalogWrapper).registerTable(eq(TABLE), same(metadata), eq(overwrite), eq(true));
    Map<String, String> extras = RequestContext.takeAuditExtras();
    Assertions.assertEquals(
        Reason.NO_POLICY.code(), extras.get(IcebergEncryptionAuditInfos.REASON));
    Assertions.assertNull(extras.get(IcebergEncryptionAuditInfos.ERROR_TYPE));
  }

  /**
   * Under a REPORT policy a registration that violates it (no key, or a valid key the allowlist
   * does not name) is still published, as the object that was read, with a VIOLATION record.
   */
  @ParameterizedTest(name = "{1} overwrite={3}")
  @MethodSource("reportViolations")
  void testReportPolicyPublishesAViolatingRegistrationWithAViolationRecord(
      @Nullable String fileKey, Reason reason, int kmsCalls, boolean overwrite) {
    PolicyEntity policy = requiredPolicy(Enforcement.REPORT);
    when(resolver.resolve(any(NameIdentifier.class))).thenReturn(Optional.of(policy));
    KmsClient client = fileKey == null ? null : validKey(fileKey);
    TableMetadata metadata = metadataFile("report-" + reason.code(), fileKey);
    LoadTableResponse response = mock(LoadTableResponse.class);
    when(catalogWrapper.registerTable(TABLE, metadata, overwrite, true)).thenReturn(response);
    EncryptionAdmissionService admission = spy(admissionService());

    Assertions.assertSame(
        response, service(admission).register(context, NAMESPACE, request(overwrite)));

    verify(catalogWrapper).registerTable(eq(TABLE), same(metadata), eq(overwrite), eq(true));
    Assertions.assertEquals(
        overwrite ? Operation.OVERWRITE : Operation.REGISTER,
        admittedRequest(admission).operation());
    Map<String, String> extras = RequestContext.takeAuditExtras();
    Assertions.assertEquals(reason.code(), extras.get(IcebergEncryptionAuditInfos.REASON));
    Assertions.assertEquals("VIOLATION", extras.get(IcebergEncryptionAuditInfos.COMPLIANCE));
    Assertions.assertEquals("REPORT", extras.get(IcebergEncryptionAuditInfos.ENFORCEMENT));
    Assertions.assertEquals(POLICY_NAME, extras.get(IcebergEncryptionAuditInfos.POLICY_NAME));
    Assertions.assertNull(extras.get(IcebergEncryptionAuditInfos.ERROR_TYPE));
    if (client == null) {
      verifyNoInteractions(kmsRegistry);
    } else {
      verify(client, times(kmsCalls)).getKeyProperties(new KmsReference(SOURCE, fileKey));
    }
  }

  /**
   * A REPORT policy does not relax hard errors: a foreign provider or a key the KMS rejects is
   * still a 400, and an unreachable KMS is still a 503, each with a VIOLATION record and no backend
   * write.
   */
  @ParameterizedTest(name = "{0} overwrite={1}")
  @MethodSource("reportRefusals")
  void testReportPolicyStillRefusesAnInvalidOrUnverifiableRegistration(
      Reason reason, boolean overwrite, Class<? extends RuntimeException> expected) {
    PolicyEntity policy = requiredPolicy(Enforcement.REPORT);
    when(resolver.resolve(any(NameIdentifier.class))).thenReturn(Optional.of(policy));
    KmsClient client = mock(KmsClient.class);
    when(kmsRegistry.getClient(new KmsReference(SOURCE, KEY_ID))).thenReturn(client);
    switch (reason) {
      case KEY_SOURCE_MISMATCH:
        metadataFile("report-refused", KEY_ID, "aws");
        break;
      case KMS_KEY_INVALID:
        when(client.getKeyProperties(any())).thenReturn(Optional.empty());
        metadataFile("report-refused", KEY_ID);
        break;
      case KMS_SERVICE_UNAVAILABLE:
        when(client.getKeyProperties(any())).thenThrow(new IllegalStateException("kms down"));
        metadataFile("report-refused", KEY_ID);
        break;
      default:
        throw new IllegalArgumentException("Unexpected reason " + reason);
    }

    Assertions.assertThrows(
        expected,
        () -> service(admissionService()).register(context, NAMESPACE, request(overwrite)));

    Map<String, String> extras = RequestContext.takeAuditExtras();
    Assertions.assertEquals(reason.code(), extras.get(IcebergEncryptionAuditInfos.REASON));
    Assertions.assertEquals("VIOLATION", extras.get(IcebergEncryptionAuditInfos.COMPLIANCE));
    Assertions.assertEquals("REPORT", extras.get(IcebergEncryptionAuditInfos.ENFORCEMENT));
    Assertions.assertEquals(expected.getName(), extras.get(IcebergEncryptionAuditInfos.ERROR_TYPE));
    if (reason == Reason.KEY_SOURCE_MISMATCH) {
      verify(kmsRegistry, never()).getClient(any());
      verify(client, never()).getKeyProperties(any());
    } else {
      verify(client, times(1)).getKeyProperties(new KmsReference(SOURCE, KEY_ID));
    }
    verify(catalogWrapper, never())
        .registerTable(any(TableIdentifier.class), any(), anyBoolean(), anyBoolean());
  }

  /** An allowed, valid key under either enforcement mode is admitted governed and COMPLIANT. */
  @ParameterizedTest(name = "{0} overwrite={1}")
  @MethodSource("enforcementsAndOverwrite")
  void testGovernedRegistrationWithAnAllowedKeyIsRecordedCompliant(
      Enforcement enforcement, boolean overwrite) {
    PolicyEntity policy = requiredPolicy(enforcement);
    when(resolver.resolve(any(NameIdentifier.class))).thenReturn(Optional.of(policy));
    KmsClient client = validKey();
    TableMetadata metadata = metadataFile("compliant", KEY_ID);
    when(catalogWrapper.registerTable(TABLE, metadata, overwrite, true))
        .thenReturn(mock(LoadTableResponse.class));
    EncryptionAdmissionService admission = spy(admissionService());
    List<EncryptionAdmission> results = new ArrayList<>();
    doAnswer(invocation -> recordResult(results, invocation)).when(admission).admit(any());

    service(admission).register(context, NAMESPACE, request(overwrite));

    Assertions.assertEquals(1, results.size());
    Assertions.assertEquals(Outcome.ADMITTED_GOVERNED, results.get(0).outcome());
    Map<String, String> extras = RequestContext.takeAuditExtras();
    Assertions.assertEquals("COMPLIANT", extras.get(IcebergEncryptionAuditInfos.COMPLIANCE));
    Assertions.assertEquals(
        Reason.COMPLIANT.code(), extras.get(IcebergEncryptionAuditInfos.REASON));
    Assertions.assertEquals(
        enforcement.name(), extras.get(IcebergEncryptionAuditInfos.ENFORCEMENT));
    Assertions.assertEquals(POLICY_NAME, extras.get(IcebergEncryptionAuditInfos.POLICY_NAME));
    verify(client, times(1)).getKeyProperties(new KmsReference(SOURCE, KEY_ID));
    verify(catalogWrapper, times(1))
        .registerTable(eq(TABLE), same(metadata), eq(overwrite), eq(true));
  }

  @Test
  void testNullConstructorArgumentsAreRejected() {
    EncryptionAdmissionService admission = admissionService();
    Assertions.assertThrows(
        IllegalArgumentException.class,
        () ->
            new GovernedRegistrationService(
                null, catalogWrapperManager, METALAKE, Optional.empty()));
    Assertions.assertThrows(
        IllegalArgumentException.class,
        () -> new GovernedRegistrationService(admission, null, METALAKE, Optional.empty()));
    Assertions.assertThrows(
        IllegalArgumentException.class,
        () ->
            new GovernedRegistrationService(
                admission, catalogWrapperManager, " ", Optional.empty()));
    Assertions.assertThrows(
        IllegalArgumentException.class,
        () -> new GovernedRegistrationService(admission, catalogWrapperManager, METALAKE, null));
  }

  private static Stream<Arguments> filesRefusedOnTheirOwnFacts() {
    String fromFile = "(from " + IcebergFormatVersionResolution.Source.FILE.token() + ")";
    return Stream.of(false, true)
        .flatMap(
            overwrite ->
                Stream.of(
                    Arguments.of(
                        "foreign provider",
                        "aws",
                        3,
                        Reason.KEY_SOURCE_MISMATCH,
                        "reason=" + Reason.KEY_SOURCE_MISMATCH.code(),
                        BadRequestException.class,
                        overwrite),
                    Arguments.of(
                        "keyed version 2 file",
                        null,
                        2,
                        Reason.UNSUPPORTED_FORMAT_VERSION,
                        fromFile,
                        IllegalArgumentException.class,
                        overwrite)));
  }

  private static Stream<Arguments> reportViolations() {
    return Stream.of(false, true)
        .flatMap(
            overwrite ->
                Stream.of(
                    Arguments.of(null, Reason.KEY_REQUIRED, 0, overwrite),
                    Arguments.of(UNLISTED_KEY, Reason.KEY_NOT_ALLOWED, 1, overwrite)));
  }

  private static Stream<Arguments> reportRefusals() {
    return Stream.of(false, true)
        .flatMap(
            overwrite ->
                Stream.of(
                    Arguments.of(Reason.KEY_SOURCE_MISMATCH, overwrite, BadRequestException.class),
                    Arguments.of(Reason.KMS_KEY_INVALID, overwrite, BadRequestException.class),
                    Arguments.of(
                        Reason.KMS_SERVICE_UNAVAILABLE,
                        overwrite,
                        ServiceUnavailableException.class)));
  }

  private static Stream<Arguments> enforcementsAndOverwrite() {
    return Stream.of(Enforcement.DENY_CREATE, Enforcement.REPORT)
        .flatMap(
            enforcement ->
                Stream.of(false, true).map(overwrite -> Arguments.of(enforcement, overwrite)));
  }

  private GovernedRegistrationService service(EncryptionAdmissionService admission) {
    return new GovernedRegistrationService(
        admission, catalogWrapperManager, METALAKE, Optional.empty());
  }

  /** A service that classifies backend refusals against a JDBC catalog, governed or not. */
  private GovernedRegistrationService service(
      EncryptionAdmissionService admission, boolean governed) {
    return new GovernedRegistrationService(
        admission,
        catalogWrapperManager,
        METALAKE,
        Optional.empty(),
        catalogName ->
            CatalogFacts.of(
                IcebergCatalogBackend.JDBC, governed, AtomicIntegrityCapability.SUPPORTED));
  }

  private GovernedRegistrationService serviceWithFailingCatalogFacts(
      EncryptionAdmissionService admission) {
    return new GovernedRegistrationService(
        admission,
        catalogWrapperManager,
        METALAKE,
        Optional.empty(),
        catalogName -> {
          throw new IllegalStateException("catalog facts unavailable");
        });
  }

  private EncryptionAdmissionService admissionService() {
    return admissionService(AtomicIntegrityCapability.SUPPORTED);
  }

  private EncryptionAdmissionService admissionService(AtomicIntegrityCapability capability) {
    return new EncryptionAdmissionService(
        table -> catalogProperties,
        Suppliers.memoize(() -> resolver),
        new IcebergEncryptionPolicyEvaluator(
            IcebergEncryptionKmsKeyValidators.fromRegistry(kmsRegistry)),
        table -> capability,
        true);
  }

  private static AdmissionRequest admittedRequest(EncryptionAdmissionService admission) {
    ArgumentCaptor<AdmissionRequest> captor = ArgumentCaptor.forClass(AdmissionRequest.class);
    verify(admission, times(1)).admit(captor.capture());
    return captor.getValue();
  }

  private static EncryptionAdmission recordResult(
      List<EncryptionAdmission> results, InvocationOnMock invocation) throws Throwable {
    EncryptionAdmission result = (EncryptionAdmission) invocation.callRealMethod();
    results.add(result);
    return result;
  }

  private KmsClient validKey() {
    return validKey(KEY_ID);
  }

  /** Has the catalog KMS report {@code keyId} as an enabled wrapping key. */
  private KmsClient validKey(String keyId) {
    KmsReference reference = new KmsReference(SOURCE, keyId);
    KmsKeyProperties properties = mock(KmsKeyProperties.class);
    when(properties.enabled()).thenReturn(true);
    when(properties.supportsWrapping()).thenReturn(true);
    KmsClient client = mock(KmsClient.class);
    when(client.getKeyProperties(reference)).thenReturn(Optional.of(properties));
    when(kmsRegistry.getClient(reference)).thenReturn(client);
    return client;
  }

  /** Writes a metadata file, reads it once, and has the wrapper hand out that one read. */
  private TableMetadata metadataFile(String name, @Nullable String keyId) {
    return metadataFile(name, keyId, null);
  }

  /** Like {@link #metadataFile(String, String)}, with the file naming its own KMS provider. */
  private TableMetadata metadataFile(
      String name, @Nullable String keyId, @Nullable String provider) {
    return metadataFile(name, keyId, provider, 3);
  }

  /** Like {@link #metadataFile(String, String, String)}, at the given format version. */
  private TableMetadata metadataFile(
      String name, @Nullable String keyId, @Nullable String provider, int formatVersion) {
    Map<String, String> properties = new HashMap<>();
    properties.put(TableProperties.FORMAT_VERSION, String.valueOf(formatVersion));
    if (provider != null) {
      properties.put(IcebergConstants.ENCRYPTION_KEY_PROVIDER, provider);
    }
    TableMetadata metadata =
        TableMetadata.newTableMetadata(
            new Schema(Types.NestedField.required(1, "id", Types.LongType.get())),
            PartitionSpec.unpartitioned(),
            "memory://governed-registration/schema/table",
            properties);
    if (keyId != null) {
      // Set after the build: newTableMetadata is Iceberg's one key-version compatibility check,
      // and a file pairing a key with version 2 is what an engine can write and a registration
      // can name.
      metadata =
          TableMetadata.buildFrom(metadata)
              .setProperties(Map.of(TableProperties.ENCRYPTION_TABLE_KEY, keyId))
              .build();
    }
    String location = LOCATION_PREFIX + name + ".metadata.json";
    InMemoryFileIO io = new InMemoryFileIO();
    io.addFile(location, TableMetadataParser.toJson(metadata).getBytes(StandardCharsets.UTF_8));
    TableMetadata read = TableMetadataParser.read(io, location);
    when(catalogWrapper.readTableMetadata(location)).thenReturn(read);
    currentLocation = location;
    return read;
  }

  private RegisterTableRequest request(boolean overwrite) {
    return ImmutableRegisterTableRequest.builder()
        .name(TABLE.name())
        .metadataLocation(currentLocation)
        .overwrite(overwrite)
        .build();
  }

  private static PolicyEntity requiredPolicy() {
    return requiredPolicy(Enforcement.DENY_CREATE);
  }

  private static PolicyEntity requiredPolicy(Enforcement enforcement) {
    PolicyEntity policy = mock(PolicyEntity.class);
    when(policy.name()).thenReturn(POLICY_NAME);
    when(policy.enabled()).thenReturn(true);
    when(policy.policyType()).thenReturn(Policy.BuiltInType.ICEBERG_ENCRYPTION);
    when(policy.content())
        .thenReturn(
            PolicyContents.icebergEncryption(
                IcebergEncryptionContent.CURRENT_SCHEMA_VERSION,
                true,
                List.of(new KmsReference(SOURCE, KEY_ID)),
                enforcement));
    return policy;
  }
}
