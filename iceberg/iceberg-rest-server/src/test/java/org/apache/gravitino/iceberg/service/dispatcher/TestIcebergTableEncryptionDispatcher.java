/*
 * Copyright 2026 Datastrato Inc.
 */
package org.apache.gravitino.iceberg.service.dispatcher;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.same;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.google.common.base.Preconditions;
import com.google.common.base.Suppliers;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.ByteBuffer;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Consumer;
import java.util.function.Predicate;
import java.util.stream.Collectors;
import java.util.stream.Stream;
import javax.servlet.http.HttpServletRequest;
import org.apache.commons.lang3.StringUtils;
import org.apache.gravitino.NameIdentifier;
import org.apache.gravitino.audit.AuditLogRedactor;
import org.apache.gravitino.audit.CapturingAuditLogWriter;
import org.apache.gravitino.audit.Formatter;
import org.apache.gravitino.audit.JsonAuditFormatter;
import org.apache.gravitino.audit.WrittenAuditLine;
import org.apache.gravitino.audit.v2.SimpleFormatterV2;
import org.apache.gravitino.catalog.lakehouse.iceberg.IcebergConstants;
import org.apache.gravitino.catalog.lakehouse.iceberg.IcebergEncryptionCatalogBinding;
import org.apache.gravitino.catalog.lakehouse.iceberg.IcebergFormatVersionResolution;
import org.apache.gravitino.encryption.AtomicIntegrityCapability;
import org.apache.gravitino.encryption.EncryptionAdmissionService;
import org.apache.gravitino.encryption.EncryptionAdmissionService.AdmissionFailure;
import org.apache.gravitino.encryption.EncryptionAdmissionService.AdmissionRequest;
import org.apache.gravitino.encryption.EncryptionAdmissionService.EncryptionAdmission;
import org.apache.gravitino.encryption.EncryptionAdmissionService.FailureKind;
import org.apache.gravitino.encryption.EncryptionAdmissionService.Operation;
import org.apache.gravitino.encryption.EncryptionAdmissionService.Outcome;
import org.apache.gravitino.encryption.IcebergEncryptionDecision;
import org.apache.gravitino.encryption.IcebergEncryptionKmsKeyValidators;
import org.apache.gravitino.encryption.IcebergEncryptionPolicyEvaluator;
import org.apache.gravitino.encryption.IcebergEncryptionPolicyResolver;
import org.apache.gravitino.encryption.kms.KmsClient;
import org.apache.gravitino.encryption.kms.KmsClientRegistry;
import org.apache.gravitino.encryption.kms.KmsKeyProperties;
import org.apache.gravitino.encryption.kms.KmsReference;
import org.apache.gravitino.iceberg.common.IcebergConfig;
import org.apache.gravitino.iceberg.common.integrity.IcebergMetadataIntegrity;
import org.apache.gravitino.iceberg.service.CatalogWrapperForREST;
import org.apache.gravitino.iceberg.service.EncryptionAdmissionRejections;
import org.apache.gravitino.iceberg.service.IcebergObjectMapper;
import org.apache.gravitino.listener.EventBus;
import org.apache.gravitino.listener.api.EventListenerPlugin;
import org.apache.gravitino.listener.api.event.Event;
import org.apache.gravitino.listener.api.event.IcebergEncryptionAuditInfos;
import org.apache.gravitino.listener.api.event.IcebergEncryptionAuditInfos.Reason;
import org.apache.gravitino.listener.api.event.IcebergRequestContext;
import org.apache.gravitino.listener.api.event.IcebergUpdateTableFailureEvent;
import org.apache.gravitino.listener.api.event.OperationStatus;
import org.apache.gravitino.listener.api.event.OperationType;
import org.apache.gravitino.meta.PolicyEntity;
import org.apache.gravitino.policy.IcebergEncryptionContent;
import org.apache.gravitino.policy.Policy;
import org.apache.gravitino.policy.PolicyContents;
import org.apache.gravitino.utils.HierarchicalSchemaUtil;
import org.apache.gravitino.utils.RequestContext;
import org.apache.iceberg.CatalogProperties;
import org.apache.iceberg.DataFiles;
import org.apache.iceberg.FileFormat;
import org.apache.iceberg.HasTableOperations;
import org.apache.iceberg.MetadataUpdate;
import org.apache.iceberg.PartitionSpec;
import org.apache.iceberg.Schema;
import org.apache.iceberg.Snapshot;
import org.apache.iceberg.SortOrder;
import org.apache.iceberg.Table;
import org.apache.iceberg.TableMetadata;
import org.apache.iceberg.TableProperties;
import org.apache.iceberg.Transaction;
import org.apache.iceberg.UpdateRequirement;
import org.apache.iceberg.catalog.Catalog;
import org.apache.iceberg.catalog.Namespace;
import org.apache.iceberg.catalog.SessionCatalog.SessionContext;
import org.apache.iceberg.catalog.TableIdentifier;
import org.apache.iceberg.encryption.BaseEncryptedKey;
import org.apache.iceberg.exceptions.BadRequestException;
import org.apache.iceberg.exceptions.ServiceUnavailableException;
import org.apache.iceberg.inmemory.InMemoryCatalog;
import org.apache.iceberg.inmemory.InMemoryFileIO;
import org.apache.iceberg.io.FileIO;
import org.apache.iceberg.jdbc.H2IntegrityJdbcCatalogs;
import org.apache.iceberg.jdbc.JdbcCatalogWithMetadataLocationSupport;
import org.apache.iceberg.rest.CatalogHandlers;
import org.apache.iceberg.rest.RESTCatalogProperties.SnapshotMode;
import org.apache.iceberg.rest.RESTClient;
import org.apache.iceberg.rest.RESTRequest;
import org.apache.iceberg.rest.RESTResponse;
import org.apache.iceberg.rest.RESTSessionCatalog;
import org.apache.iceberg.rest.requests.CreateTableRequest;
import org.apache.iceberg.rest.requests.UpdateTableRequest;
import org.apache.iceberg.rest.responses.ConfigResponse;
import org.apache.iceberg.rest.responses.ErrorResponse;
import org.apache.iceberg.rest.responses.LoadTableResponse;
import org.apache.iceberg.types.Types;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.function.Executable;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.EnumSource;
import org.junit.jupiter.params.provider.MethodSource;
import org.mockito.ArgumentCaptor;

/** Tests encryption invariant enforcement and audit metadata in the Iceberg table dispatcher. */
public class TestIcebergTableEncryptionDispatcher {

  private static final String METALAKE = "metalake";
  private static final String CATALOG = "catalog";
  private static final String FEDERATED_CATALOG = "federated";
  private static final Predicate<String> FEDERATED = Set.of(FEDERATED_CATALOG)::contains;
  private static final String SOURCE = "openbao";
  private static final String KEY_ID = "customer-pii-v1";
  private static final TableIdentifier TABLE = TableIdentifier.of(Namespace.of("schema"), "table");
  private static final String ADDED_KEY_IDS =
      IcebergEncryptionAuditInfos.PREFIX + "addedEncryptionKeyIds";
  private static final String REMOVED_KEY_IDS =
      IcebergEncryptionAuditInfos.PREFIX + "removedEncryptionKeyIds";
  private static final String COMMIT_ID = IcebergEncryptionAuditInfos.PREFIX + "commitId";
  private static final String OVERRIDE_KEY_ID = "catalog-override-key";
  private static final String JDBC_BACKEND = "jdbc_backend";
  private static final String UNLISTED_KEY_ID = "unlisted-key";
  private static final String POLICY_NAME = "customer-encryption";
  private static final String AUTHORIZATION_HEADER = "Authorization";
  private static final String AUTHORIZATION_VALUE = "Bearer audit-canary-token";
  private static final Schema SCHEMA =
      new Schema(Types.NestedField.required(1, "id", Types.LongType.get()));

  private IcebergTableOperationDispatcher delegate;
  private EventListenerPlugin listener;
  private EventBus eventBus;
  private IcebergRequestContext context;
  private IcebergRequestContext federatedContext;
  private IcebergTableEncryptionDispatcher dispatcher;
  private KmsClientRegistry kmsRegistry;
  // The atomic-integrity capability the loaded catalog reports to the admission service.
  private AtomicIntegrityCapability capability;
  // The audit facts each REST request left behind, in order, as the event dispatcher takes them at
  // the end of a request: an empty map is a request that recorded nothing.
  private List<Map<String, String>> requestAuditRecords;
  // The real audit chain requests enter through, or null for the dispatcher alone; see auditTo.
  private EventBus auditBus;

  @AfterEach
  void clearRequestContext() {
    RequestContext.clear();
    CapturingAuditLogWriter.clear();
  }

  @BeforeEach
  void setUp() {
    delegate = mock(IcebergTableOperationDispatcher.class);
    listener = mock(EventListenerPlugin.class);
    when(listener.transformPreEvent(any())).thenAnswer(invocation -> invocation.getArgument(0));
    eventBus = new EventBus(Collections.singletonList(listener));
    context = mock(IcebergRequestContext.class);
    when(context.catalogName()).thenReturn(CATALOG);
    when(context.userName()).thenReturn("spark-engine");
    when(context.remoteHostName()).thenReturn("198.51.100.10");
    when(context.httpHeaders()).thenReturn(Collections.emptyMap());
    federatedContext = mock(IcebergRequestContext.class);
    when(federatedContext.catalogName()).thenReturn(FEDERATED_CATALOG);
    dispatcher = new IcebergTableEncryptionDispatcher(delegate, catalogName -> false);
    capability = AtomicIntegrityCapability.SUPPORTED;
    requestAuditRecords = new ArrayList<>();
    auditBus = null;
    CapturingAuditLogWriter.clear();
  }

  @Test
  void testDeniedKeyIdMutationAlsoEmitsGenericIrcFailure() {
    Snapshot snapshot = mock(Snapshot.class);
    when(snapshot.snapshotId()).thenReturn(42L);
    UpdateTableRequest request =
        request(
            new MetadataUpdate.AddEncryptionKey(encryptedKey("kek-1")),
            new MetadataUpdate.RemoveEncryptionKey("kek-0"),
            new MetadataUpdate.SetProperties(
                Map.of(TableProperties.ENCRYPTION_TABLE_KEY, "replacement-master-key")),
            new MetadataUpdate.AddSnapshot(snapshot));
    IcebergRequestContext extrasContext = mock(IcebergRequestContext.class);
    when(extrasContext.catalogName()).thenReturn(CATALOG);
    when(extrasContext.userName()).thenReturn("spark-engine");
    when(extrasContext.remoteHostName()).thenReturn("198.51.100.10");
    when(extrasContext.httpHeaders()).thenReturn(Collections.emptyMap());
    Map<String, String> expectedExtras =
        Map.of(
            IcebergEncryptionAuditInfos.REASON,
            Reason.ENCRYPTION_KEY_CHANGE_DENIED.code(),
            ADDED_KEY_IDS,
            "kek-1",
            REMOVED_KEY_IDS,
            "kek-0");
    when(extrasContext.auditExtras()).thenReturn(expectedExtras);
    when(extrasContext.customInfo()).thenReturn(expectedExtras);
    when(context.withAuditExtras(any())).thenReturn(extrasContext);
    IcebergTableOperationDispatcher eventDispatcher =
        new IcebergTableEventDispatcher(dispatcher, eventBus, METALAKE);

    IllegalArgumentException exception =
        Assertions.assertThrows(
            IllegalArgumentException.class,
            () -> eventDispatcher.updateTable(context, TABLE, request));

    Assertions.assertTrue(exception.getMessage().contains("immutable"));
    verifyNoInteractions(delegate);

    @SuppressWarnings("unchecked")
    ArgumentCaptor<Map<String, String>> extrasCaptor = ArgumentCaptor.forClass(Map.class);
    verify(context).withAuditExtras(extrasCaptor.capture());
    Map<String, String> stashed = extrasCaptor.getValue();
    Assertions.assertEquals(
        Reason.ENCRYPTION_KEY_CHANGE_DENIED.code(),
        stashed.get(IcebergEncryptionAuditInfos.REASON));
    Assertions.assertEquals("kek-1", stashed.get(ADDED_KEY_IDS));
    Assertions.assertEquals("kek-0", stashed.get(REMOVED_KEY_IDS));
    // One identifier, in the refusal an operator reads and in the audit record it is joined to.
    String decisionId = stashed.get(IcebergEncryptionAuditInfos.DECISION_ID);
    Assertions.assertNotNull(decisionId, "refusal must carry a decision id");
    Assertions.assertTrue(
        exception.getMessage().contains("decisionId=" + decisionId), exception.getMessage());

    ArgumentCaptor<Event> eventCaptor = ArgumentCaptor.forClass(Event.class);
    verify(listener).onPostEvent(eventCaptor.capture());
    Event encryptionEvent = eventCaptor.getValue();
    Assertions.assertEquals(IcebergUpdateTableFailureEvent.class, encryptionEvent.getClass());
    Assertions.assertEquals(OperationType.ALTER_TABLE, encryptionEvent.operationType());
    Assertions.assertEquals(OperationStatus.FAILURE, encryptionEvent.operationStatus());
    Assertions.assertEquals(
        Reason.ENCRYPTION_KEY_CHANGE_DENIED.code(),
        encryptionEvent.customInfo().get(IcebergEncryptionAuditInfos.REASON));
    Assertions.assertEquals("kek-1", encryptionEvent.customInfo().get(ADDED_KEY_IDS));
    Assertions.assertEquals("kek-0", encryptionEvent.customInfo().get(REMOVED_KEY_IDS));
    Assertions.assertFalse(encryptionEvent.customInfo().containsKey(COMMIT_ID));
  }

  /**
   * The dispatcher's one translation of an IRC request into an {@link AdmissionRequest}: the
   * operation each shape names, the identifier it resolves and the properties it folds, pinned on
   * the service's own request record so a stage, a create and a staged commit reach one decision.
   * The admission is left unevaluated, so each request must also be forwarded as it was sent.
   */
  @ParameterizedTest(name = "{0}")
  @MethodSource("requestShapes")
  void testEachRequestShapeIsAdmittedAsItsOperationWithItsFoldedProperties(
      String shape,
      Namespace namespace,
      RESTRequest request,
      Operation operation,
      Map<String, String> folded) {
    EncryptionAdmissionService admissionService = mock(EncryptionAdmissionService.class);
    when(admissionService.admit(any(AdmissionRequest.class)))
        .thenAnswer(
            invocation ->
                new EncryptionAdmission(
                    Outcome.ADMITTED_UNGOVERNED,
                    invocation.<AdmissionRequest>getArgument(0).requestedProperties(),
                    null,
                    Collections.emptyMap(),
                    null));
    dispatcher = mockedAdmissionDispatcher(admissionService);
    LoadTableResponse response = mock(LoadTableResponse.class);
    Object forwarded;
    if (request instanceof CreateTableRequest) {
      CreateTableRequest create = (CreateTableRequest) request;
      when(delegate.createTable(eq(context), eq(namespace), any(CreateTableRequest.class)))
          .thenReturn(response);
      Assertions.assertSame(response, dispatcher.createTable(context, namespace, create));
      forwarded = capturedCreate(namespace);
    } else {
      UpdateTableRequest commit = (UpdateTableRequest) request;
      when(delegate.updateTable(eq(context), eq(TABLE), any(UpdateTableRequest.class)))
          .thenReturn(response);
      Assertions.assertSame(response, dispatcher.updateTable(context, TABLE, commit));
      forwarded = capturedUpdate();
    }

    ArgumentCaptor<AdmissionRequest> admitted = ArgumentCaptor.forClass(AdmissionRequest.class);
    verify(admissionService).admit(admitted.capture());
    Assertions.assertEquals(operation, admitted.getValue().operation());
    Assertions.assertEquals(
        NameIdentifier.of(
            METALAKE,
            CATALOG,
            String.join(HierarchicalSchemaUtil.schemaSeparator(), namespace.levels()),
            TABLE.name()),
        admitted.getValue().table());
    Assertions.assertEquals(folded, admitted.getValue().requestedProperties());
    Assertions.assertSame(request, forwarded, "an unevaluated admission forwards the request");
    if (operation == Operation.STAGE) {
      Assertions.assertTrue(((CreateTableRequest) forwarded).stageCreate());
    }
    Assertions.assertTrue(RequestContext.takeAuditExtras().isEmpty());
  }

  private static Stream<Arguments> requestShapes() {
    String key = TableProperties.ENCRYPTION_TABLE_KEY;
    String kmsApi = IcebergConstants.ENCRYPTION_KMS_API;
    Map<String, String> keyed = Map.of(IcebergConstants.ENCRYPTION_KEY_ID, KEY_ID);
    return Stream.of(
        Arguments.of(
            "create in a nested namespace",
            Namespace.of("schema", "nested"),
            createRequest(KEY_ID, false),
            Operation.CREATE,
            keyed),
        Arguments.of(
            "create naming no properties",
            TABLE.namespace(),
            createRequest((Map<String, String>) null, false),
            Operation.CREATE,
            Map.of()),
        Arguments.of(
            "stage", TABLE.namespace(), createRequest(KEY_ID, true), Operation.STAGE, keyed),
        stagedCommitShape(
            "staged commit removing the leftover protocol property it set",
            keyed,
            new MetadataUpdate.SetProperties(Map.of(key, KEY_ID, kmsApi, "leftover")),
            new MetadataUpdate.RemoveProperties(Set.of(kmsApi))),
        stagedCommitShape(
            "staged commit setting the key twice",
            keyed,
            setProperty(key, "superseded-key"),
            setProperty(key, KEY_ID)),
        stagedCommitShape(
            "staged commit setting then removing the key",
            Map.of(),
            setProperty(key, KEY_ID),
            new MetadataUpdate.RemoveProperties(Set.of(key))),
        stagedCommitShape(
            "staged commit upgrading to version 2",
            Map.of(IcebergConstants.FORMAT_VERSION, "2"),
            new MetadataUpdate.AssignUUID("9c12d441-03fe-4693-9a96-a0705ddf69c1"),
            new MetadataUpdate.UpgradeFormatVersion(2)),
        stagedCommitShape(
            "staged commit upgrading twice",
            Map.of(IcebergConstants.FORMAT_VERSION, "3"),
            new MetadataUpdate.UpgradeFormatVersion(2),
            new MetadataUpdate.UpgradeFormatVersion(3)),
        stagedCommitShape(
            "staged commit setting nothing encryption-related",
            Map.of(),
            setProperty("write.format.default", "parquet")));
  }

  private static Arguments stagedCommitShape(
      String shape, Map<String, String> folded, MetadataUpdate... updates) {
    return Arguments.of(
        shape, TABLE.namespace(), stagedCommit(updates), Operation.STAGED_COMMIT, folded);
  }

  /**
   * What the backend creates is what the service admitted, not the instance the engine sent: the
   * completed binding and the resolved version reach it, an empty admission carries neither, and
   * the name, the schema and the stage flag survive the rebuild.
   */
  @ParameterizedTest(name = "{0}")
  @MethodSource("admittedCreates")
  void testAdmittedPropertiesAreForwardedVerbatimOnCreate(
      String shape, boolean staged, Map<String, String> admittedProperties) {
    Map<String, String> auditExtras =
        Map.of(IcebergEncryptionAuditInfos.REASON, Reason.COMPLIANT.code());
    dispatcher =
        mockedAdmissionDispatcher(admitting(governedAdmission(admittedProperties, auditExtras)));
    CreateTableRequest request = createRequest(KEY_ID, staged);
    LoadTableResponse response = mock(LoadTableResponse.class);
    when(delegate.createTable(eq(context), eq(TABLE.namespace()), any(CreateTableRequest.class)))
        .thenReturn(response);

    Assertions.assertSame(response, dispatcher.createTable(context, TABLE.namespace(), request));

    CreateTableRequest forwarded = capturedCreate(TABLE.namespace());
    Assertions.assertNotSame(request, forwarded);
    Assertions.assertEquals(admittedProperties, forwarded.properties());
    Assertions.assertEquals(TABLE.name(), forwarded.name());
    Assertions.assertEquals(SCHEMA.asStruct(), forwarded.schema().asStruct());
    Assertions.assertEquals(staged, forwarded.stageCreate(), "stage flag kept");
    Assertions.assertEquals(auditExtras, takeExtras());
  }

  private static Stream<Arguments> admittedCreates() {
    Map<String, String> completed =
        Map.of(
            IcebergConstants.ENCRYPTION_KEY_PROVIDER,
            SOURCE,
            IcebergConstants.ENCRYPTION_KEY_ID,
            KEY_ID,
            IcebergConstants.FORMAT_VERSION,
            "4");
    return Stream.of(
        Arguments.of("create admitted with a completed binding", false, completed),
        Arguments.of("create admitted with nothing", false, Map.of()),
        Arguments.of("stage admitted with a completed binding", true, completed));
  }

  /**
   * A commit carries updates, not a property map, so the binding the service admitted reaches the
   * staged table as one more update, and the resolved version only where the commit named none: a
   * version the commit names is kept, an unreadable one is ignored, and an admission with nothing
   * to append forwards the commit as sent. The requirements and the identifier survive the rebuild.
   */
  @ParameterizedTest(name = "{0}")
  @MethodSource("admittedStagedCommits")
  void testStagedCommitAppendsOnlyWhatAdmissionResolved(
      String shape,
      Map<String, String> admittedProperties,
      UpdateTableRequest commit,
      List<Integer> upgrades,
      boolean bindingAppended) {
    dispatcher =
        mockedAdmissionDispatcher(
            admitting(governedAdmission(admittedProperties, Collections.emptyMap())));
    LoadTableResponse response = mock(LoadTableResponse.class);
    when(delegate.updateTable(eq(context), eq(TABLE), any(UpdateTableRequest.class)))
        .thenReturn(response);

    Assertions.assertSame(response, dispatcher.updateTable(context, TABLE, commit));

    UpdateTableRequest forwarded = capturedUpdate();
    Assertions.assertEquals(upgrades, formatVersionUpgrades(forwarded));
    Assertions.assertEquals(commit.requirements(), forwarded.requirements(), "requirements kept");
    Assertions.assertEquals(TABLE, forwarded.identifier(), "identifier kept");
    if (bindingAppended) {
      MetadataUpdate last = forwarded.updates().get(forwarded.updates().size() - 1);
      Assertions.assertTrue(last instanceof MetadataUpdate.SetProperties, last.toString());
      Assertions.assertEquals(
          Map.of(
              IcebergConstants.ENCRYPTION_KEY_PROVIDER,
              SOURCE,
              IcebergConstants.ENCRYPTION_KEY_ID,
              KEY_ID),
          ((MetadataUpdate.SetProperties) last).updated());
    } else {
      Assertions.assertFalse(
          lastSetProperties(forwarded).containsKey(IcebergConstants.ENCRYPTION_KEY_ID));
    }
    if (bindingAppended || !upgrades.equals(formatVersionUpgrades(commit))) {
      Assertions.assertNotSame(commit, forwarded);
    } else {
      Assertions.assertSame(commit, forwarded, "nothing to append forwards the commit as sent");
    }
  }

  private static Stream<Arguments> admittedStagedCommits() {
    MetadataUpdate setKey = setProperty(TableProperties.ENCRYPTION_TABLE_KEY, KEY_ID);
    MetadataUpdate setFormat = setProperty("write.format.default", "parquet");
    Map<String, String> resolvedFour = admittedBinding("4");
    return Stream.of(
        Arguments.of(
            "the resolved version and the binding on a commit naming no version",
            resolvedFour,
            stagedCommit(setFormat),
            List.of(4),
            true),
        Arguments.of(
            "a version the commit names is kept",
            resolvedFour,
            stagedCommit(new MetadataUpdate.UpgradeFormatVersion(3), setKey),
            List.of(3),
            true),
        Arguments.of(
            "an unreadable resolved version is ignored",
            admittedBinding("three"),
            stagedCommit(setFormat),
            List.of(),
            true),
        Arguments.of(
            "nothing admitted on a commit naming no version",
            Map.of(),
            stagedCommit(setFormat),
            List.of(),
            false),
        Arguments.of(
            "nothing admitted on a keyless commit naming version 2",
            Map.of(),
            stagedCommit(
                new MetadataUpdate.AssignUUID("9c12d441-03fe-4693-9a96-a0705ddf69c1"),
                new MetadataUpdate.UpgradeFormatVersion(2)),
            List.of(2),
            false));
  }

  private static Map<String, String> admittedBinding(String formatVersion) {
    return Map.of(
        IcebergConstants.ENCRYPTION_KEY_PROVIDER,
        SOURCE,
        IcebergConstants.ENCRYPTION_KEY_ID,
        KEY_ID,
        IcebergConstants.FORMAT_VERSION,
        formatVersion);
  }

  /**
   * A refusal is thrown as the exception {@link EncryptionAdmissionRejections} maps its kind to,
   * with the service's message unchanged, on the create and the staged-commit route alike; the
   * delegate is never reached, and the audit record names the thrown type beside the service's own
   * facts.
   */
  @ParameterizedTest
  @EnumSource(FailureKind.class)
  void testARefusedAdmissionIsThrownAsItsRestExceptionAndNeverForwarded(FailureKind kind) {
    AdmissionFailure failure =
        new AdmissionFailure(kind, "d-1", kind + " refused: decisionId=d-1", List.of());
    Map<String, String> auditExtras =
        Map.of(
            IcebergEncryptionAuditInfos.REASON,
            Reason.KEY_NOT_ALLOWED.code(),
            IcebergEncryptionAuditInfos.DECISION_ID,
            "d-1");
    dispatcher = mockedAdmissionDispatcher(admitting(refusedAdmission(failure, auditExtras)));
    Class<? extends RuntimeException> restException =
        EncryptionAdmissionRejections.toRestException(failure).getClass();
    List<Executable> routes =
        List.of(
            () -> dispatcher.createTable(context, TABLE.namespace(), createRequest(KEY_ID, false)),
            () ->
                dispatcher.updateTable(
                    context,
                    TABLE,
                    stagedCommit(setProperty(TableProperties.ENCRYPTION_TABLE_KEY, KEY_ID))));

    for (Executable route : routes) {
      RuntimeException thrown = Assertions.assertThrows(RuntimeException.class, route);

      Assertions.assertEquals(restException, thrown.getClass());
      Assertions.assertEquals(failure.message(), thrown.getMessage());
      Map<String, String> extras = takeExtras();
      Assertions.assertEquals(
          restException.getName(), extras.get(IcebergEncryptionAuditInfos.ERROR_TYPE));
      Assertions.assertTrue(
          extras.entrySet().containsAll(auditExtras.entrySet()), extras.toString());
    }
    verifyNoInteractions(delegate);
  }

  /**
   * A backend failure after admission is rethrown as it was thrown and recorded as the create's
   * failure under the admission's own facts; a prepared stage carries none, so a stage the backend
   * fails records nothing.
   */
  @ParameterizedTest(name = "{0}")
  @MethodSource("delegateFailures")
  void testADelegateFailureAfterAdmissionRecordsTheCreateFailureAndRethrows(
      String shape, boolean staged, Map<String, String> auditExtras) {
    dispatcher =
        mockedAdmissionDispatcher(admitting(governedAdmission(admittedBinding("3"), auditExtras)));
    RuntimeException failure = new RuntimeException("backend create failed");
    when(delegate.createTable(eq(context), eq(TABLE.namespace()), any(CreateTableRequest.class)))
        .thenThrow(failure);

    RuntimeException thrown =
        Assertions.assertThrows(
            RuntimeException.class,
            () ->
                dispatcher.createTable(context, TABLE.namespace(), createRequest(KEY_ID, staged)));

    Assertions.assertSame(failure, thrown);
    Map<String, String> extras = RequestContext.takeAuditExtras();
    if (auditExtras.isEmpty()) {
      Assertions.assertTrue(extras.isEmpty(), "a failed stage has no decision to record");
    } else {
      Assertions.assertEquals(
          Reason.TABLE_CREATE_FAILED.code(), extras.get(IcebergEncryptionAuditInfos.REASON));
      Assertions.assertEquals(
          RuntimeException.class.getName(), extras.get(IcebergEncryptionAuditInfos.ERROR_TYPE));
      Assertions.assertEquals("d-1", extras.get(IcebergEncryptionAuditInfos.DECISION_ID));
    }
  }

  private static Stream<Arguments> delegateFailures() {
    return Stream.of(
        Arguments.of(
            "create",
            false,
            Map.of(
                IcebergEncryptionAuditInfos.REASON,
                Reason.NO_POLICY.code(),
                IcebergEncryptionAuditInfos.DECISION_ID,
                "d-1")),
        Arguments.of("stage", true, Map.of()));
  }

  /**
   * A commit carries updates, not a property map, so the key binding the service admitted reaches
   * the staged table as one more update. Nothing is stripped on top of that: the service reads the
   * map the commit's updates fold to, so a commit that already removed a leftover protocol property
   * has nothing left to remove, and one that still sets it is refused instead (the case below).
   */
  @Test
  void testStagedCommitCarriesTheAdmittedKeyBinding() {
    IcebergEncryptionPolicyResolver resolver = mock(IcebergEncryptionPolicyResolver.class);
    PolicyEntity policy = encryptionPolicy(true, IcebergEncryptionContent.Enforcement.DENY_CREATE);
    when(resolver.resolve(any(NameIdentifier.class))).thenReturn(Optional.of(policy));
    KmsReference reference = new KmsReference(SOURCE, KEY_ID);
    dispatcher = governedDispatcher(resolver, reference, validKey(reference), "jdbc");
    // The commit ends with no leftover protocol property, so it is admitted; a commit that still
    // carried one is refused, which is its own case below.
    UpdateTableRequest commitRequest =
        stagedCommit(
            new MetadataUpdate.SetProperties(
                Map.of(
                    TableProperties.ENCRYPTION_TABLE_KEY,
                    KEY_ID,
                    IcebergConstants.ENCRYPTION_KMS_API,
                    "leftover")),
            new MetadataUpdate.RemoveProperties(
                Collections.singleton(IcebergConstants.ENCRYPTION_KMS_API)));
    LoadTableResponse response = mock(LoadTableResponse.class);
    when(delegate.updateTable(eq(context), eq(TABLE), any(UpdateTableRequest.class)))
        .thenReturn(response);

    Assertions.assertSame(response, dispatcher.updateTable(context, TABLE, commitRequest));

    UpdateTableRequest forwarded = capturedUpdate();
    Assertions.assertNotSame(commitRequest, forwarded);
    Assertions.assertEquals(
        commitRequest.requirements().size(), forwarded.requirements().size(), "requirements kept");
    Assertions.assertEquals(TABLE, forwarded.identifier(), "identifier kept");
    Map<String, String> applied = lastSetProperties(forwarded);
    Assertions.assertEquals(SOURCE, applied.get(IcebergConstants.ENCRYPTION_KEY_PROVIDER));
    Assertions.assertEquals(KEY_ID, applied.get(IcebergConstants.ENCRYPTION_KEY_ID));
    // The commit's own removal is the only one: the dispatcher adds no second strip for a property
    // this commit already removed.
    Assertions.assertEquals(
        Set.of(IcebergConstants.ENCRYPTION_KMS_API), removedProperties(forwarded));
    Assertions.assertEquals(
        1,
        forwarded.updates().stream()
            .filter(MetadataUpdate.RemoveProperties.class::isInstance)
            .count(),
        "no strip is appended on top of the commit's own removal");
    // A commit carries updates rather than properties, so the format version the key binding needs
    // reaches the staged table the only way it can: as an upgrade update.
    Assertions.assertEquals(List.of(3), formatVersionUpgrades(forwarded));
    Assertions.assertEquals(
        Reason.COMPLIANT.code(), takeExtras().get(IcebergEncryptionAuditInfos.REASON));
  }

  @Test
  void testInvariantOnlyEncryptedCreatePassesThroughWithoutGovernedAudit() {
    CreateTableRequest request = createRequest(KEY_ID, false);
    LoadTableResponse response = mock(LoadTableResponse.class);
    when(delegate.createTable(context, TABLE.namespace(), request)).thenReturn(response);

    Assertions.assertSame(response, dispatcher.createTable(context, TABLE.namespace(), request));

    verify(delegate).createTable(context, TABLE.namespace(), request);
    Assertions.assertTrue(RequestContext.takeAuditExtras().isEmpty());
  }

  /**
   * An ungoverned deployment forwards a keyed staged-create commit unchanged: the commit guard
   * exempts it, because it sets the new table's first key rather than changing an existing one.
   */
  @Test
  void testInvariantOnlyKeyedStagedCommitIsForwardedUnchanged() {
    UpdateTableRequest commit =
        stagedCommit(
            new MetadataUpdate.UpgradeFormatVersion(3),
            setProperty(TableProperties.ENCRYPTION_TABLE_KEY, KEY_ID),
            setProperty(IcebergConstants.ENCRYPTION_KEY_PROVIDER, SOURCE));
    LoadTableResponse response = mock(LoadTableResponse.class);
    when(delegate.updateTable(context, TABLE, commit)).thenReturn(response);

    Assertions.assertSame(response, dispatcher.updateTable(context, TABLE, commit));

    verify(delegate).updateTable(context, TABLE, commit);
    Assertions.assertTrue(RequestContext.takeAuditExtras().isEmpty());
  }

  /**
   * Design 4.6 at the dispatcher: stage prepares and commit decides, so the stage records nothing
   * and the commit yields the one audit record, which still carries the commit's key updates and
   * id; the one KMS call is the commit's.
   */
  @Test
  void testStageRecordsNothingAndTheCommitYieldsTheOneAuditRecord() {
    AtomicInteger resolverCreations = new AtomicInteger();
    IcebergEncryptionPolicyResolver resolver = mock(IcebergEncryptionPolicyResolver.class);
    when(resolver.resolve(any(NameIdentifier.class))).thenReturn(Optional.empty());
    KmsReference reference = new KmsReference(SOURCE, KEY_ID);
    KmsClient client = validKey(reference);
    dispatcher = governedDispatcher(resolverCreations, resolver, reference, client, "jdbc");
    LoadTableResponse createResponse = mock(LoadTableResponse.class);
    when(delegate.createTable(eq(context), eq(TABLE.namespace()), any(CreateTableRequest.class)))
        .thenReturn(createResponse);
    Snapshot snapshot = mock(Snapshot.class);
    when(snapshot.snapshotId()).thenReturn(42L);
    UpdateTableRequest commitRequest =
        stagedCommit(
            new MetadataUpdate.SetProperties(Map.of(TableProperties.ENCRYPTION_TABLE_KEY, KEY_ID)),
            new MetadataUpdate.AddEncryptionKey(encryptedKey("kek-1")),
            new MetadataUpdate.RemoveEncryptionKey("kek-0"),
            new MetadataUpdate.AddSnapshot(snapshot));
    LoadTableResponse commitResponse = mock(LoadTableResponse.class);
    when(commitResponse.metadataLocation())
        .thenReturn("s3://warehouse/table/metadata/v3.metadata.json");
    when(delegate.updateTable(eq(context), eq(TABLE), any(UpdateTableRequest.class)))
        .thenReturn(commitResponse);

    Assertions.assertSame(
        createResponse,
        dispatcher.createTable(context, TABLE.namespace(), createRequest(KEY_ID, true)));
    // Each request publishes its own audit record; taking the extras is what the event dispatcher
    // does at the end of a request.
    Map<String, String> stageExtras = RequestContext.takeAuditExtras();
    verifyNoInteractions(client);
    Assertions.assertSame(commitResponse, dispatcher.updateTable(context, TABLE, commitRequest));
    Map<String, String> commitExtras = takeExtras();

    Assertions.assertTrue(capturedCreate(TABLE.namespace()).stageCreate(), "stage flag kept");
    Assertions.assertTrue(stageExtras.isEmpty(), "a prepared stage records nothing");
    Assertions.assertEquals(1, resolverCreations.get());
    verify(resolver, times(2)).resolve(any(NameIdentifier.class));
    verify(client, times(1)).getKeyProperties(reference);
    Assertions.assertNotNull(commitExtras.get(IcebergEncryptionAuditInfos.DECISION_ID));
    Assertions.assertEquals(
        Reason.NO_POLICY.code(), commitExtras.get(IcebergEncryptionAuditInfos.REASON));
    Assertions.assertEquals(SOURCE, commitExtras.get(IcebergEncryptionAuditInfos.PROVIDER));
    Assertions.assertEquals(KEY_ID, commitExtras.get(IcebergEncryptionAuditInfos.KEY_ID));
    Assertions.assertEquals("kek-1", commitExtras.get(ADDED_KEY_IDS));
    Assertions.assertEquals("kek-0", commitExtras.get(REMOVED_KEY_IDS));
    Assertions.assertEquals(
        "s3://warehouse/table/metadata/v3.metadata.json", commitExtras.get(COMMIT_ID));
  }

  /**
   * Design 4.11: with no key anywhere and no policy there is nothing to decide: the stage and its
   * commit are forwarded unchanged, with no KMS call and no record.
   */
  @Test
  void testStageOnAnUnkeyedCatalogWithoutPolicyIsForwardedUnchanged() {
    IcebergEncryptionPolicyResolver resolver = noPolicy();
    dispatcher = governedDispatcher(resolver, null, null, "jdbc");
    CreateTableRequest request =
        createRequest(Collections.singletonMap("write.format.default", "parquet"), true);
    UpdateTableRequest commit =
        stagedCommit(new MetadataUpdate.SetProperties(Map.of("write.format.default", "parquet")));
    LoadTableResponse response = mock(LoadTableResponse.class);
    when(delegate.createTable(context, TABLE.namespace(), request)).thenReturn(response);
    when(delegate.updateTable(context, TABLE, commit)).thenReturn(response);

    Assertions.assertSame(response, dispatcher.createTable(context, TABLE.namespace(), request));
    Assertions.assertTrue(RequestContext.takeAuditExtras().isEmpty());
    Assertions.assertSame(response, dispatcher.updateTable(context, TABLE, commit));
    Assertions.assertTrue(RequestContext.takeAuditExtras().isEmpty());

    verify(delegate).createTable(same(context), same(TABLE.namespace()), same(request));
    verify(delegate).updateTable(same(context), same(TABLE), same(commit));
    verifyNoInteractions(kmsRegistry);
  }

  /**
   * Design 4.13 on a real integrity-aware JDBC backend: after the CTAS round trip, whose commit
   * carries the engine's first snapshot, the table's integrity hash is present, matches the
   * committed {@code metadata.json}, and a load verifies against it.
   */
  @Test
  void testCtasPublishesAVerifiedTableOnAnIntegrityAwareBackend() throws Exception {
    KmsReference override = new KmsReference(SOURCE, OVERRIDE_KEY_ID);
    dispatcher =
        governedDispatcher(
            new AtomicInteger(),
            noPolicy(),
            override,
            validKey(override),
            "jdbc",
            overrideCatalogKey());
    String uri = H2IntegrityJdbcCatalogs.newUri("ctas_verified");
    try (JdbcCatalogWithMetadataLocationSupport backend = jdbcBackend(uri, overrideCatalogKey())) {
      restStageThenCommit(
          transaction ->
              transaction
                  .newAppend()
                  .appendFile(
                      DataFiles.builder(PartitionSpec.unpartitioned())
                          .withPath(transaction.table().location() + "/data/ctas.parquet")
                          .withFileSizeInBytes(10)
                          .withRecordCount(1)
                          .withFormat(FileFormat.PARQUET)
                          .build())
                  .commit());

      Table table = backend.loadTable(TABLE);
      TableMetadata committed = ((HasTableOperations) table).operations().current();
      Assertions.assertNotNull(committed.currentSnapshot(), "the engine's snapshot is committed");
      Assertions.assertEquals(
          OVERRIDE_KEY_ID, committed.properties().get(TableProperties.ENCRYPTION_TABLE_KEY));
      Assertions.assertEquals(
          Optional.of(IcebergMetadataIntegrity.metadataHash(committed)),
          H2IntegrityJdbcCatalogs.metadataHash(uri, JDBC_BACKEND, TABLE));
      assertOnlyTheCommitRecorded();
    }
  }

  /**
   * The key identity is immutable to an ordinary commit in both modes, whatever the table's current
   * properties: setting or removing the key or its provider, or setting a legacy protocol spelling,
   * is refused before the delegate with a 400 naming the property, under the decision id its audit
   * record carries. A REPLACE TABLE that adds a key to a plain table on a keyed catalog (SPIP V4,
   * matrix rows 18 and 19) is the same commit, and the table stays plain at version 2.
   */
  @ParameterizedTest(name = "{0}: {1}")
  @MethodSource("guardedKeyIdentityChanges")
  void testTheCommitGuardRefusesKeyIdentityChangesInEveryMode(
      Mode mode, String shape, String property, MetadataUpdate change, boolean replace) {
    InMemoryCatalog backend = replace ? plainTableOnAKeyedBackend() : null;
    if (mode == Mode.GOVERNED) {
      dispatcher = keyedDispatcher();
    }
    UpdateTableRequest commit =
        replace ? replaceCommit(currentMetadata(backend).uuid(), change) : request(change);

    IllegalArgumentException refusal =
        Assertions.assertThrows(
            IllegalArgumentException.class, () -> dispatcher.updateTable(context, TABLE, commit));

    Assertions.assertTrue(refusal.getMessage().contains(property), refusal.getMessage());
    Assertions.assertTrue(refusal.getMessage().contains("immutable"), refusal.getMessage());
    Map<String, String> denial = takeExtras();
    Assertions.assertEquals(
        Reason.ENCRYPTION_KEY_CHANGE_DENIED.code(), denial.get(IcebergEncryptionAuditInfos.REASON));
    String decisionId = denial.get(IcebergEncryptionAuditInfos.DECISION_ID);
    Assertions.assertNotNull(decisionId, "refusal must carry a decision id");
    Assertions.assertTrue(
        refusal.getMessage().contains("decisionId=" + decisionId), refusal.getMessage());
    verify(delegate, never()).updateTable(any(), any(), any());
    if (replace) {
      TableMetadata unchanged = currentMetadata(backend);
      Assertions.assertEquals(2, unchanged.formatVersion());
      Assertions.assertFalse(
          unchanged.properties().containsKey(TableProperties.ENCRYPTION_TABLE_KEY));
    }
  }

  private static Stream<Arguments> guardedKeyIdentityChanges() {
    String key = TableProperties.ENCRYPTION_TABLE_KEY;
    String provider = IcebergConstants.ENCRYPTION_KEY_PROVIDER;
    String kmsApi = IcebergConstants.ENCRYPTION_KMS_API;
    String keySource = IcebergConstants.ENCRYPTION_KEY_SOURCE;
    return Stream.of(Mode.values())
        .flatMap(
            mode ->
                Stream.of(
                    Arguments.of(mode, "setting the key", key, setProperty(key, KEY_ID), false),
                    Arguments.of(
                        mode,
                        "setting the provider",
                        provider,
                        setProperty(provider, SOURCE),
                        false),
                    Arguments.of(
                        mode,
                        "removing the key",
                        key,
                        new MetadataUpdate.RemoveProperties(Set.of(key)),
                        false),
                    Arguments.of(
                        mode,
                        "removing the provider",
                        provider,
                        new MetadataUpdate.RemoveProperties(Set.of(provider)),
                        false),
                    Arguments.of(
                        mode,
                        "setting the kms api",
                        kmsApi,
                        setProperty(kmsApi, "another-kms"),
                        false),
                    Arguments.of(
                        mode,
                        "setting the key source",
                        keySource,
                        setProperty(keySource, "another-kms"),
                        false),
                    Arguments.of(
                        mode,
                        "a replace adding a key to a plain table",
                        key,
                        setProperty(key, KEY_ID),
                        true)));
  }

  /** Removing a legacy protocol spelling cannot name a KMS, so an ordinary commit may do it. */
  @Test
  void testLegacyProtocolPropertyRemovalIsAllowed() {
    UpdateTableRequest request =
        request(
            new MetadataUpdate.RemoveProperties(
                Set.of(
                    IcebergConstants.ENCRYPTION_KMS_API, IcebergConstants.ENCRYPTION_KEY_SOURCE)));
    LoadTableResponse response = mock(LoadTableResponse.class);
    when(delegate.updateTable(context, TABLE, request)).thenReturn(response);

    Assertions.assertSame(response, dispatcher.updateTable(context, TABLE, request));

    IcebergEncryptionPolicyResolver resolver = mock(IcebergEncryptionPolicyResolver.class);
    PolicyEntity policy = encryptionPolicy(true, IcebergEncryptionContent.Enforcement.DENY_CREATE);
    when(resolver.resolve(any(NameIdentifier.class))).thenReturn(Optional.of(policy));
    dispatcher = governedDispatcher(resolver, null, null, "jdbc");

    Assertions.assertSame(response, dispatcher.updateTable(context, TABLE, request));

    verify(delegate, times(2)).updateTable(context, TABLE, request);
    Assertions.assertTrue(RequestContext.takeAuditExtras().isEmpty());
  }

  @Test
  void testNullConstructorAndUpdateArgumentsAreRejectedAsIllegalArguments() {
    Assertions.assertEquals(
        "dispatcher cannot be null",
        Assertions.assertThrows(
                IllegalArgumentException.class,
                () -> new IcebergTableEncryptionDispatcher(null, catalogName -> false))
            .getMessage());
    Assertions.assertEquals(
        "federatedCatalog cannot be null",
        Assertions.assertThrows(
                IllegalArgumentException.class,
                () -> new IcebergTableEncryptionDispatcher(delegate, null))
            .getMessage());

    UpdateTableRequest request =
        request(new MetadataUpdate.SetProperties(Map.of("write.format.default", "parquet")));
    Assertions.assertEquals(
        "context cannot be null",
        Assertions.assertThrows(
                IllegalArgumentException.class, () -> dispatcher.updateTable(null, TABLE, request))
            .getMessage());
    Assertions.assertEquals(
        "tableIdentifier cannot be null",
        Assertions.assertThrows(
                IllegalArgumentException.class,
                () -> dispatcher.updateTable(context, null, request))
            .getMessage());
    Assertions.assertEquals(
        "updateTableRequest cannot be null",
        Assertions.assertThrows(
                IllegalArgumentException.class, () -> dispatcher.updateTable(context, TABLE, null))
            .getMessage());

    verifyNoInteractions(delegate);
  }

  @Test
  void testOrdinaryCommitPassesThroughWithoutEncryptionAudit() {
    UpdateTableRequest request =
        request(new MetadataUpdate.SetProperties(Map.of("write.format.default", "parquet")));
    LoadTableResponse response = mock(LoadTableResponse.class);
    when(delegate.updateTable(context, TABLE, request)).thenReturn(response);

    Assertions.assertSame(response, dispatcher.updateTable(context, TABLE, request));

    verify(delegate).updateTable(context, TABLE, request);
    Assertions.assertTrue(RequestContext.takeAuditExtras().isEmpty());
  }

  /**
   * Ledger 7 on the IRC: a table with no encryption is loaded and committed to exactly as before
   * even when an enforcing or reporting policy covers it. Only a staged-create commit is admitted,
   * so an ordinary commit never resolves the policy, calls the KMS or leaves an audit record.
   */
  @ParameterizedTest
  @EnumSource(
      value = IcebergEncryptionContent.Enforcement.class,
      names = {"DENY_CREATE", "REPORT"})
  void testPlainTableUnderAnEnforceOrAuditPolicyLoadsAndCommitsWithoutPolicyKmsOrAudit(
      IcebergEncryptionContent.Enforcement enforcement) {
    IcebergEncryptionPolicyResolver resolver = mock(IcebergEncryptionPolicyResolver.class);
    PolicyEntity policy = encryptionPolicy(true, enforcement);
    when(resolver.resolve(any(NameIdentifier.class))).thenReturn(Optional.of(policy));
    AtomicInteger resolverCreations = new AtomicInteger();
    dispatcher = governedDispatcher(resolverCreations, resolver, null, null, "jdbc");
    UpdateTableRequest commit =
        request(new MetadataUpdate.SetProperties(Map.of("owner", "analytics")));
    LoadTableResponse loaded = mock(LoadTableResponse.class);
    LoadTableResponse committed = mock(LoadTableResponse.class);
    when(delegate.loadTable(context, TABLE)).thenReturn(loaded);
    when(delegate.updateTable(context, TABLE, commit)).thenReturn(committed);

    Assertions.assertSame(loaded, dispatcher.loadTable(context, TABLE), enforcement.name());
    Assertions.assertSame(
        committed, dispatcher.updateTable(context, TABLE, commit), enforcement.name());

    verify(delegate).updateTable(context, TABLE, commit);
    Assertions.assertEquals(0, resolverCreations.get(), enforcement.name());
    verifyNoInteractions(resolver);
    verifyNoInteractions(kmsRegistry);
    Assertions.assertTrue(RequestContext.takeAuditExtras().isEmpty(), enforcement.name());
  }

  @Test
  void testSuccessfulEncryptionKeyCommitPublishesSemanticAudit() {
    Snapshot snapshot = mock(Snapshot.class);
    when(snapshot.snapshotId()).thenReturn(42L);
    UpdateTableRequest request =
        request(
            new MetadataUpdate.AddEncryptionKey(encryptedKey("kek-1")),
            new MetadataUpdate.RemoveEncryptionKey("kek-0"),
            new MetadataUpdate.AddSnapshot(snapshot));
    LoadTableResponse response = mock(LoadTableResponse.class);
    when(response.metadataLocation()).thenReturn("s3://warehouse/table/metadata/v3.metadata.json");
    when(delegate.updateTable(context, TABLE, request)).thenReturn(response);

    Assertions.assertSame(response, dispatcher.updateTable(context, TABLE, request));

    verify(delegate).updateTable(context, TABLE, request);
    Map<String, String> event = takeExtras();
    Assertions.assertEquals(
        Reason.ENCRYPTION_KEY_UPDATE_OBSERVED.code(),
        event.get(IcebergEncryptionAuditInfos.REASON));
    Assertions.assertEquals("kek-1", event.get(ADDED_KEY_IDS));
    Assertions.assertEquals("kek-0", event.get(REMOVED_KEY_IDS));
    Assertions.assertEquals("s3://warehouse/table/metadata/v3.metadata.json", event.get(COMMIT_ID));
  }

  @Test
  void testBlankEncryptionKeyIdIsRejectedBeforeBackendCommit() {
    for (MetadataUpdate update :
        Arrays.asList(
            new MetadataUpdate.AddEncryptionKey(encryptedKey("")),
            new MetadataUpdate.RemoveEncryptionKey(""))) {
      IllegalArgumentException exception =
          Assertions.assertThrows(
              IllegalArgumentException.class,
              () -> dispatcher.updateTable(context, TABLE, request(update)));

      Assertions.assertTrue(exception.getMessage().contains("key ID cannot be blank"));
    }

    verifyNoInteractions(delegate);
    verify(listener, never()).onPostEvent(any());
  }

  @Test
  void testFailedEncryptionKeyCommitPublishesFailureAndRethrows() {
    Snapshot snapshot = mock(Snapshot.class);
    when(snapshot.snapshotId()).thenReturn(42L);
    UpdateTableRequest request =
        request(
            new MetadataUpdate.AddEncryptionKey(encryptedKey("kek-1")),
            new MetadataUpdate.AddSnapshot(snapshot));
    RuntimeException failure = new RuntimeException("backend commit failed");
    when(delegate.updateTable(context, TABLE, request)).thenThrow(failure);

    RuntimeException thrown =
        Assertions.assertThrows(
            RuntimeException.class, () -> dispatcher.updateTable(context, TABLE, request));

    Assertions.assertSame(failure, thrown);
    Map<String, String> event = takeExtras();
    Assertions.assertEquals(
        Reason.OPERATION_FAILED.code(), event.get(IcebergEncryptionAuditInfos.REASON));
    Assertions.assertEquals(
        RuntimeException.class.getName(), event.get(IcebergEncryptionAuditInfos.ERROR_TYPE));
    Assertions.assertEquals("42", event.get(COMMIT_ID));
  }

  /**
   * A federated catalog is pass-through in both modes: every request shape, from a keyed create to
   * a commit that rebinds or rotates keys, is forwarded exactly as the engine sent it, without
   * admission and without an encryption audit, because the remote catalog owns encryption for its
   * tables.
   */
  @ParameterizedTest(name = "{1}: {0}")
  @MethodSource("federatedRequests")
  void testFederatedRequestsAreForwardedUntouchedInEveryMode(
      String shape, Mode mode, RESTRequest request) {
    EncryptionAdmissionService admissionService = mock(EncryptionAdmissionService.class);
    dispatcher =
        mode == Mode.GOVERNED
            ? mockedAdmissionDispatcher(admissionService)
            : new IcebergTableEncryptionDispatcher(delegate, FEDERATED);
    LoadTableResponse response = mock(LoadTableResponse.class);

    if (request instanceof CreateTableRequest) {
      CreateTableRequest create = (CreateTableRequest) request;
      when(delegate.createTable(federatedContext, TABLE.namespace(), create)).thenReturn(response);
      Assertions.assertSame(
          response, dispatcher.createTable(federatedContext, TABLE.namespace(), create));
      verify(delegate).createTable(same(federatedContext), same(TABLE.namespace()), same(create));
    } else {
      UpdateTableRequest commit = (UpdateTableRequest) request;
      when(delegate.updateTable(federatedContext, TABLE, commit)).thenReturn(response);
      Assertions.assertSame(response, dispatcher.updateTable(federatedContext, TABLE, commit));
      verify(delegate).updateTable(same(federatedContext), same(TABLE), same(commit));
    }

    verifyNoInteractions(admissionService);
    Assertions.assertTrue(RequestContext.takeAuditExtras().isEmpty());
  }

  private static Stream<Arguments> federatedRequests() {
    return Stream.of(Mode.values())
        .flatMap(
            mode ->
                Stream.of(
                    Arguments.of("keyed create", mode, createRequest("unconfirmed-key", false)),
                    Arguments.of("keyed stage", mode, createRequest("unconfirmed-key", true)),
                    Arguments.of(
                        "staged commit",
                        mode,
                        stagedCommit(
                            setProperty(TableProperties.ENCRYPTION_TABLE_KEY, "unconfirmed-key"))),
                    Arguments.of(
                        "key removal",
                        mode,
                        request(
                            new MetadataUpdate.RemoveProperties(
                                Set.of(TableProperties.ENCRYPTION_TABLE_KEY)))),
                    Arguments.of(
                        "provider set",
                        mode,
                        request(
                            setProperty(IcebergConstants.ENCRYPTION_KEY_PROVIDER, "another-kms"))),
                    Arguments.of(
                        "key rotation",
                        mode,
                        request(
                            new MetadataUpdate.AddEncryptionKey(encryptedKey("kek-1")),
                            new MetadataUpdate.RemoveEncryptionKey("kek-0")))));
  }

  /**
   * SPIP V2 on the commit route, read off the backend: a keyed {@code assert-create} commit naming
   * version 2 is refused as an unsupported format version, whatever the catalog's {@code
   * table-override.format-version} says, and no table exists afterwards. The commit is sent without
   * a stage, which the CTAS design allows, shaped as Iceberg's REST client shapes a create-commit,
   * and the delegate is Iceberg's own {@code CatalogHandlers} over an in-memory catalog, so a
   * commit that slipped through would have published an encrypted table below version 3.
   */
  @Test
  void testKeyedStagedCommitNamingVersionTwoIsNeverPublishedWithAKey() {
    KmsReference reference = new KmsReference(SOURCE, KEY_ID);
    Map<String, String> overriddenVersion =
        Map.of(IcebergFormatVersionResolution.TABLE_OVERRIDE_FORMAT_VERSION, "3");
    dispatcher =
        governedDispatcher(
            new AtomicInteger(),
            noPolicy(),
            reference,
            validKey(reference),
            "jdbc",
            overriddenVersion);
    InMemoryCatalog backend = inMemoryBackend(overriddenVersion);
    UpdateTableRequest commit =
        stagedCreateCommit(2, Map.of(TableProperties.ENCRYPTION_TABLE_KEY, KEY_ID));

    IllegalArgumentException refusal =
        Assertions.assertThrows(
            IllegalArgumentException.class, () -> dispatcher.updateTable(context, TABLE, commit));

    Assertions.assertTrue(
        refusal.getMessage().contains(Reason.UNSUPPORTED_FORMAT_VERSION.code()),
        refusal.getMessage());
    Assertions.assertFalse(backend.tableExists(TABLE));
    Assertions.assertEquals(
        Reason.UNSUPPORTED_FORMAT_VERSION.code(),
        takeExtras().get(IcebergEncryptionAuditInfos.REASON));
  }

  /**
   * R6, in the one shape the rule forbids: a staged-create commit that names version 3 is published
   * at 3 even when the admission resolved 4. Gravitino writes a version only where the request
   * named none and never replaces one a client named, so the dispatcher appends no {@code
   * upgrade-format-version} to a commit that already carries one, whatever the resolution says.
   *
   * <p>The admission is stubbed because no catalog configuration produces that disagreement today:
   * the rules write a resolved version only into a request that named none, so a real service hands
   * back exactly the version the commit carries. Stubbing pins the dispatcher's half of the
   * invariant on its own, so a resolution that ever did hand back a higher number would fail here
   * rather than silently re-version a client's commit.
   *
   * <p>The assertion reads the metadata the backend published, not the request the dispatcher
   * forwarded. Asserting on what is forwarded is the mistake that let a keyed version 2 table ship.
   */
  @Test
  void testStagedCommitNamingAVersionIsNeverRaisedToTheResolvedOne() {
    EncryptionAdmissionService admissionService = mock(EncryptionAdmissionService.class);
    when(admissionService.admit(any()))
        .thenReturn(
            new EncryptionAdmission(
                Outcome.ADMITTED_GOVERNED,
                Map.of(
                    IcebergConstants.FORMAT_VERSION,
                    "4",
                    IcebergConstants.ENCRYPTION_KEY_PROVIDER,
                    SOURCE,
                    IcebergConstants.ENCRYPTION_KEY_ID,
                    KEY_ID),
                mock(IcebergEncryptionDecision.class),
                Collections.emptyMap(),
                null));
    dispatcher =
        new IcebergTableEncryptionDispatcher(delegate, METALAKE, admissionService, FEDERATED);
    InMemoryCatalog backend =
        inMemoryBackend(Map.of(IcebergFormatVersionResolution.TABLE_DEFAULT_FORMAT_VERSION, "3"));

    dispatcher.updateTable(
        context,
        TABLE,
        stagedCreateCommit(3, Map.of(TableProperties.ENCRYPTION_TABLE_KEY, KEY_ID)));

    TableMetadata published = currentMetadata(backend);
    Assertions.assertEquals(
        3,
        published.formatVersion(),
        "the commit named version 3; the version admission resolved may not replace it");
    Assertions.assertEquals(
        KEY_ID, published.properties().get(TableProperties.ENCRYPTION_TABLE_KEY));
  }

  /** Audit journey A2: an enforced policy refuses a key it does not allow with one failure line. */
  @Test
  void testAuditedEnforcedCreateWithAKeyThePolicyRefusesWritesOneFailureLine() {
    auditTo(SimpleFormatterV2.class);
    IcebergEncryptionPolicyResolver resolver = mock(IcebergEncryptionPolicyResolver.class);
    PolicyEntity policy = encryptionPolicy(true, IcebergEncryptionContent.Enforcement.DENY_CREATE);
    when(resolver.resolve(any(NameIdentifier.class))).thenReturn(Optional.of(policy));
    KmsReference unlisted = new KmsReference(SOURCE, UNLISTED_KEY_ID);
    dispatcher = governedDispatcher(resolver, unlisted, validKey(unlisted), "jdbc");

    Assertions.assertThrows(
        BadRequestException.class,
        () ->
            restEntryPoint()
                .createTable(context, TABLE.namespace(), createRequest(UNLISTED_KEY_ID, false)));

    verify(delegate, never()).createTable(any(), any(), any());
    WrittenAuditLine line = onlyWrittenLine();
    assertOperation(line, OperationType.CREATE_TABLE, OperationStatus.FAILURE);
    assertDecision(line, Reason.KEY_NOT_ALLOWED, UNLISTED_KEY_ID);
    Assertions.assertEquals("VIOLATION", fact(line, IcebergEncryptionAuditInfos.COMPLIANCE));
    Assertions.assertEquals(POLICY_NAME, fact(line, IcebergEncryptionAuditInfos.POLICY_NAME));
    Assertions.assertEquals(
        BadRequestException.class.getName(), fact(line, IcebergEncryptionAuditInfos.ERROR_TYPE));
  }

  /**
   * Audit journey A4 (#2099) on the CTAS design 4.6 round trip: Iceberg's own {@link
   * RESTSessionCatalog} stages the create and builds the commit, the real handlers publish the
   * table under the catalog's key at version 3 after the one KMS call, and of the stage line and
   * the commit line only the commit's carries the encryption decision.
   */
  @ParameterizedTest
  @MethodSource("auditFormatters")
  void testAuditedCtasWritesTheEncryptionDecisionOnlyOnTheCommitLine(
      Class<? extends Formatter> formatter) throws Exception {
    auditTo(formatter);
    KmsReference override = new KmsReference(SOURCE, OVERRIDE_KEY_ID);
    KmsClient client = validKey(override);
    dispatcher =
        governedDispatcher(
            new AtomicInteger(), noPolicy(), override, client, "jdbc", overrideCatalogKey());
    InMemoryCatalog backend = inMemoryBackend(overrideCatalogKey());

    restStageThenCommit(transaction -> {});

    Table table = backend.loadTable(TABLE);
    Assertions.assertEquals(
        OVERRIDE_KEY_ID, table.properties().get(TableProperties.ENCRYPTION_TABLE_KEY));
    Assertions.assertEquals(
        SOURCE, table.properties().get(IcebergConstants.ENCRYPTION_KEY_PROVIDER));
    Assertions.assertEquals(3, ((HasTableOperations) table).operations().current().formatVersion());
    verify(client, times(1)).getKeyProperties(override);
    List<WrittenAuditLine> lines = writtenLines();
    Assertions.assertEquals(2, lines.size(), "one stage and one commit line: " + lines);
    WrittenAuditLine stage = lines.get(0);
    assertOperation(stage, OperationType.CREATE_TABLE, OperationStatus.SUCCESS);
    assertNoEncryptionFacts(stage);
    WrittenAuditLine commit = lines.get(1);
    assertOperation(commit, OperationType.ALTER_TABLE, OperationStatus.SUCCESS);
    assertDecision(commit, Reason.NO_POLICY, OVERRIDE_KEY_ID);
    Assertions.assertEquals("VALID", fact(commit, IcebergEncryptionAuditInfos.KMS_VALIDATION));
    Assertions.assertNull(fact(commit, IcebergEncryptionAuditInfos.POLICY_NAME), commit.raw());
    Assertions.assertNull(fact(commit, IcebergEncryptionAuditInfos.ERROR_TYPE), commit.raw());
  }

  /**
   * Audit journey A6 (#2099): a CTAS whose KMS is unreachable at commit writes a stage line without
   * facts and one commit failure line naming the unavailable KMS.
   */
  @Test
  void testAuditedCtasWithTheKmsDownAtCommitWritesOneUnavailableLine() {
    auditTo(SimpleFormatterV2.class);
    KmsReference override = new KmsReference(SOURCE, OVERRIDE_KEY_ID);
    KmsClient client = mock(KmsClient.class);
    when(client.getKeyProperties(override)).thenThrow(new IllegalStateException("kms down"));
    dispatcher =
        governedDispatcher(
            new AtomicInteger(), noPolicy(), override, client, "jdbc", overrideCatalogKey());
    InMemoryCatalog backend = inMemoryBackend(overrideCatalogKey());

    Assertions.assertThrows(
        ServiceUnavailableException.class, () -> restStageThenCommit(transaction -> {}));

    Assertions.assertFalse(backend.tableExists(TABLE));
    List<WrittenAuditLine> lines = writtenLines();
    Assertions.assertEquals(2, lines.size(), "one stage and one commit line: " + lines);
    assertOperation(lines.get(0), OperationType.CREATE_TABLE, OperationStatus.SUCCESS);
    assertNoEncryptionFacts(lines.get(0));
    WrittenAuditLine commit = lines.get(1);
    assertOperation(commit, OperationType.ALTER_TABLE, OperationStatus.FAILURE);
    assertDecision(commit, Reason.KMS_SERVICE_UNAVAILABLE, OVERRIDE_KEY_ID);
    Assertions.assertEquals(
        "UNAVAILABLE", fact(commit, IcebergEncryptionAuditInfos.KMS_VALIDATION));
    Assertions.assertEquals(
        ServiceUnavailableException.class.getName(),
        fact(commit, IcebergEncryptionAuditInfos.ERROR_TYPE));
  }

  /**
   * Audit journey A11 (#2121 F7): a federated catalog's encrypted create is forwarded, and its line
   * carries no encryption fact because this catalog made no decision.
   */
  @ParameterizedTest
  @MethodSource("auditFormatters")
  void testAuditedFederatedCreateWritesALineWithoutEncryptionFacts(
      Class<? extends Formatter> formatter) {
    auditTo(formatter);
    IcebergEncryptionPolicyResolver resolver = mock(IcebergEncryptionPolicyResolver.class);
    PolicyEntity policy = encryptionPolicy(true, IcebergEncryptionContent.Enforcement.DENY_CREATE);
    when(resolver.resolve(any(NameIdentifier.class))).thenReturn(Optional.of(policy));
    dispatcher = governedDispatcher(resolver, null, null, "jdbc");
    InMemoryCatalog backend = inMemoryBackend(Collections.emptyMap());
    IcebergRequestContext remote = auditedContext(FEDERATED_CATALOG);
    when(delegate.createTable(eq(remote), any(Namespace.class), any(CreateTableRequest.class)))
        .thenAnswer(
            invocation ->
                CatalogHandlers.createTable(
                    backend, invocation.getArgument(1), invocation.getArgument(2)));

    restEntryPoint()
        .createTable(
            remote,
            TABLE.namespace(),
            createRequest(
                new HashMap<>(
                    Map.of(
                        TableProperties.ENCRYPTION_TABLE_KEY,
                        "unconfirmed-key",
                        IcebergConstants.FORMAT_VERSION,
                        "3")),
                false));

    Assertions.assertTrue(backend.tableExists(TABLE));
    verifyNoInteractions(resolver, kmsRegistry);
    WrittenAuditLine line = onlyWrittenLine();
    assertOperation(line, OperationType.CREATE_TABLE, OperationStatus.SUCCESS);
    assertNoEncryptionFacts(line);
  }

  private static Stream<Class<? extends Formatter>> auditFormatters() {
    return Stream.of(SimpleFormatterV2.class, JsonAuditFormatter.class);
  }

  /**
   * Switches this test to the audit sink: requests enter through {@link
   * IcebergTableEventDispatcher} over the real audit chain writing through {@code formatter}, with
   * a real request context carrying an {@code Authorization} header. Call it before the backend is
   * served, since the backend answers the context by identity.
   */
  private void auditTo(Class<? extends Formatter> formatter) {
    auditBus = CapturingAuditLogWriter.auditEventBus(formatter);
    context = auditedContext(CATALOG);
  }

  /** A real request context, as the IRC builds it from an HTTP request. */
  private static IcebergRequestContext auditedContext(String catalogName) {
    HttpServletRequest http = mock(HttpServletRequest.class);
    when(http.getHeaderNames())
        .thenAnswer(invocation -> Collections.enumeration(List.of(AUTHORIZATION_HEADER)));
    when(http.getHeader(AUTHORIZATION_HEADER)).thenReturn(AUTHORIZATION_VALUE);
    when(http.getRemoteHost()).thenReturn("198.51.100.10");
    return new IcebergRequestContext(http, catalogName);
  }

  /** The dispatcher chain a request enters: the event dispatcher when auditing, else the one. */
  private IcebergTableOperationDispatcher restEntryPoint() {
    return auditBus == null
        ? dispatcher
        : new IcebergTableEventDispatcher(dispatcher, auditBus, METALAKE);
  }

  /**
   * The lines written so far, each checked for what every line must hold: the {@code Authorization}
   * header masked, and no key material, hash or digest among the encryption facts.
   */
  private static List<WrittenAuditLine> writtenLines() {
    List<WrittenAuditLine> lines =
        CapturingAuditLogWriter.lines().stream()
            .map(WrittenAuditLine::parse)
            .collect(Collectors.toList());
    for (WrittenAuditLine line : lines) {
      Assertions.assertEquals(
          AuditLogRedactor.REDACTED_VALUE, line.customInfo().get(AUTHORIZATION_HEADER), line.raw());
      Assertions.assertFalse(line.raw().contains(AUTHORIZATION_VALUE), line.raw());
      for (String key : line.customInfo().keySet()) {
        String lower = key.toLowerCase(Locale.ROOT);
        Assertions.assertFalse(
            lower.startsWith(IcebergEncryptionAuditInfos.PREFIX.toLowerCase(Locale.ROOT))
                && (lower.contains("hash")
                    || lower.contains("digest")
                    || lower.contains("material")),
            key + " in " + line.raw());
      }
    }
    return lines;
  }

  /** Asserts that exactly one line was written and returns it. */
  private static WrittenAuditLine onlyWrittenLine() {
    List<WrittenAuditLine> lines = writtenLines();
    Assertions.assertEquals(1, lines.size(), "one written line: " + lines);
    return lines.get(0);
  }

  private static void assertOperation(
      WrittenAuditLine line, OperationType operation, OperationStatus status) {
    Assertions.assertEquals(operation.name(), line.operation(), line.raw());
    Assertions.assertEquals(status.name(), line.status(), line.raw());
  }

  /** Asserts the one encryption decision a line carries: its id, reason, key and provider. */
  private static void assertDecision(WrittenAuditLine line, Reason reason, String keyId) {
    Assertions.assertFalse(
        StringUtils.isBlank(fact(line, IcebergEncryptionAuditInfos.DECISION_ID)), line.raw());
    Assertions.assertEquals(reason.code(), fact(line, IcebergEncryptionAuditInfos.REASON));
    Assertions.assertEquals(keyId, fact(line, IcebergEncryptionAuditInfos.KEY_ID));
    Assertions.assertEquals(SOURCE, fact(line, IcebergEncryptionAuditInfos.PROVIDER));
  }

  private static void assertNoEncryptionFacts(WrittenAuditLine line) {
    Assertions.assertTrue(
        line.customInfo().keySet().stream()
            .noneMatch(key -> key.startsWith(IcebergEncryptionAuditInfos.PREFIX)),
        line.raw());
    Assertions.assertFalse(line.raw().contains(IcebergEncryptionAuditInfos.PREFIX), line.raw());
  }

  private static String fact(WrittenAuditLine line, String key) {
    return line.customInfo().get(key);
  }

  /** The dispatcher under test over a mocked admission service, so a test scripts the decision. */
  private IcebergTableEncryptionDispatcher mockedAdmissionDispatcher(
      EncryptionAdmissionService admissionService) {
    return new IcebergTableEncryptionDispatcher(delegate, METALAKE, admissionService, FEDERATED);
  }

  /** An admission service that answers every request with {@code admission}. */
  private static EncryptionAdmissionService admitting(EncryptionAdmission admission) {
    EncryptionAdmissionService admissionService = mock(EncryptionAdmissionService.class);
    when(admissionService.admit(any(AdmissionRequest.class))).thenReturn(admission);
    return admissionService;
  }

  /**
   * An evaluated, governed admission of {@code admittedProperties} carrying {@code auditExtras};
   * the properties are held as the service holds them, in an unmodifiable view of an ordered map.
   */
  private static EncryptionAdmission governedAdmission(
      Map<String, String> admittedProperties, Map<String, String> auditExtras) {
    return new EncryptionAdmission(
        Outcome.ADMITTED_GOVERNED,
        Collections.unmodifiableMap(new LinkedHashMap<>(admittedProperties)),
        mock(IcebergEncryptionDecision.class),
        auditExtras,
        null);
  }

  /** A refusal, in the outcome the service gives the failure's kind. */
  private static EncryptionAdmission refusedAdmission(
      AdmissionFailure failure, Map<String, String> auditExtras) {
    Outcome outcome =
        failure.kind() == FailureKind.KMS_UNAVAILABLE ? Outcome.UNAVAILABLE : Outcome.DENIED;
    return new EncryptionAdmission(outcome, Collections.emptyMap(), null, auditExtras, failure);
  }

  private IcebergTableEncryptionDispatcher governedDispatcher(
      IcebergEncryptionPolicyResolver resolver,
      KmsReference reference,
      KmsClient client,
      String backend) {
    return governedDispatcher(new AtomicInteger(), resolver, reference, client, backend);
  }

  private IcebergTableEncryptionDispatcher governedDispatcher(
      AtomicInteger resolverCreations,
      IcebergEncryptionPolicyResolver resolver,
      KmsReference reference,
      KmsClient client,
      String backend) {
    return governedDispatcher(
        resolverCreations, resolver, reference, client, backend, Collections.emptyMap());
  }

  private IcebergTableEncryptionDispatcher governedDispatcher(
      AtomicInteger resolverCreations,
      IcebergEncryptionPolicyResolver resolver,
      KmsReference reference,
      KmsClient client,
      String backend,
      Map<String, String> additionalCatalogProperties) {
    Map<String, String> properties = new HashMap<>();
    properties.put(IcebergConstants.ENCRYPTION_KMS_SOURCE, SOURCE);
    properties.put(IcebergConstants.CATALOG_BACKEND, backend);
    // R3 and R9: a keyed request on a catalog that declares nothing resolves to Iceberg's default
    // of 2 and is refused, so every catalog built here declares the version an operator pairs with
    // a key. A caller that wants an undeclared catalog passes undeclared(...).
    properties.put(IcebergFormatVersionResolution.TABLE_DEFAULT_FORMAT_VERSION, "3");
    properties.putAll(additionalCatalogProperties);
    if (StringUtils.isEmpty(
        properties.get(IcebergFormatVersionResolution.TABLE_DEFAULT_FORMAT_VERSION))) {
      properties.remove(IcebergFormatVersionResolution.TABLE_DEFAULT_FORMAT_VERSION);
    }
    KmsClientRegistry registry = mock(KmsClientRegistry.class);
    kmsRegistry = registry;
    if (reference != null) {
      when(registry.getClient(reference)).thenReturn(client);
    }
    // The capability is the loaded catalog's own answer; a test that needs a backend without it
    // sets the field before building the dispatcher.
    AtomicIntegrityCapability backendCapability = capability;
    EncryptionAdmissionService admissionService =
        new EncryptionAdmissionService(
            table -> properties,
            Suppliers.memoize(
                () -> {
                  resolverCreations.incrementAndGet();
                  return resolver;
                }),
            new IcebergEncryptionPolicyEvaluator(
                IcebergEncryptionKmsKeyValidators.fromRegistry(registry)),
            table -> backendCapability);
    return new IcebergTableEncryptionDispatcher(delegate, METALAKE, admissionService, FEDERATED);
  }

  private static IcebergEncryptionPolicyResolver noPolicy() {
    IcebergEncryptionPolicyResolver resolver = mock(IcebergEncryptionPolicyResolver.class);
    when(resolver.resolve(any(NameIdentifier.class))).thenReturn(Optional.empty());
    return resolver;
  }

  private static Map<String, String> overrideCatalogKey() {
    return Map.of(
        CatalogProperties.TABLE_OVERRIDE_PREFIX + TableProperties.ENCRYPTION_TABLE_KEY,
        OVERRIDE_KEY_ID);
  }

  private static Map<String, String> defaultCatalogKey() {
    return Map.of(
        CatalogProperties.TABLE_DEFAULT_PREFIX + TableProperties.ENCRYPTION_TABLE_KEY, KEY_ID);
  }

  /**
   * The defaults {@code /v1/config} sends clients for a catalog that binds {@link #KEY_ID} as its
   * default key, taken from the server's own {@link CatalogWrapperForREST} over a memory backend.
   */
  private static Map<String, String> keyedCatalogConfigToClients() {
    Map<String, String> catalogProperties = new HashMap<>(defaultCatalogKey());
    catalogProperties.put(IcebergConstants.CATALOG_BACKEND, "memory");
    try (CatalogWrapperForREST wrapper =
        new CatalogWrapperForREST(
            "keyed-config-" + UUID.randomUUID(), new IcebergConfig(catalogProperties, true))) {
      return wrapper.getCatalogConfigToClient();
    } catch (Exception e) {
      throw new IllegalStateException(e);
    }
  }

  /** A governed dispatcher on a catalog that binds {@link #KEY_ID} as its default key. */
  private IcebergTableEncryptionDispatcher keyedDispatcher() {
    KmsReference reference = new KmsReference(SOURCE, KEY_ID);
    return governedDispatcher(
        new AtomicInteger(),
        noPolicy(),
        reference,
        validKey(reference),
        "jdbc",
        defaultCatalogKey());
  }

  /**
   * Backs the delegate with a plain version 2 table created before its catalog bound a key: the
   * table is created on an unkeyed backend, which is then re-initialized with the properties the
   * keyed catalog is loaded with, as a restart after the binding would load it (SPIP V4).
   */
  private InMemoryCatalog plainTableOnAKeyedBackend() {
    InMemoryCatalog backend = inMemoryBackend(Collections.emptyMap());
    backend.createTable(TABLE, SCHEMA);
    Map<String, String> keyedProperties =
        new HashMap<>(icebergCatalogProperties(defaultCatalogKey()));
    keyedProperties.put(CatalogProperties.WAREHOUSE_LOCATION, "memory://warehouse");
    backend.initialize("backend", keyedProperties);

    TableMetadata plain = currentMetadata(backend);
    Assertions.assertEquals(2, plain.formatVersion());
    Assertions.assertFalse(plain.properties().containsKey(TableProperties.ENCRYPTION_TABLE_KEY));
    // The binding took effect: a table created on the backend now is encrypted at version 3.
    Table createdAfter =
        backend.createTable(TableIdentifier.of(TABLE.namespace(), "created_after"), SCHEMA);
    Assertions.assertEquals(
        KEY_ID, createdAfter.properties().get(TableProperties.ENCRYPTION_TABLE_KEY));
    Assertions.assertEquals(
        3, ((HasTableOperations) createdAfter).operations().current().formatVersion());
    return backend;
  }

  private static TableMetadata currentMetadata(Catalog backend) {
    return ((HasTableOperations) backend.loadTable(TABLE)).operations().current();
  }

  /**
   * A REPLACE TABLE commit as Iceberg's REST client shapes it for an unchanged schema, spec and
   * sort order: asserted on the table's uuid, it resets the current schema, the default spec and
   * the default sort order, followed by any further updates the replacement carries.
   */
  private static UpdateTableRequest replaceCommit(String uuid, MetadataUpdate... variation) {
    List<MetadataUpdate> updates = new ArrayList<>();
    updates.add(new MetadataUpdate.SetCurrentSchema(SCHEMA.schemaId()));
    updates.add(new MetadataUpdate.SetDefaultPartitionSpec(PartitionSpec.unpartitioned().specId()));
    updates.add(new MetadataUpdate.SetDefaultSortOrder(SortOrder.unsorted().orderId()));
    updates.addAll(Arrays.asList(variation));
    return UpdateTableRequest.create(
        TABLE, List.of(new UpdateRequirement.AssertTableUUID(uuid)), updates);
  }

  /**
   * Backs the delegate with Iceberg's own REST handlers over an in-memory catalog, so staging and
   * committing run exactly the code the IRC runs behind the dispatcher chain.
   *
   * <p>The backend is initialized with the catalog properties {@link IcebergConfig} hands a loaded
   * catalog, so the catalog's {@code table-default.}/{@code table-override.} bindings are part of
   * the fixture. That is what makes the create-commit path realistic: Iceberg builds a
   * create-commit's placeholder table from those catalog defaults alone, so a bound key without a
   * bound format version is refused as invalid for v2 before any Enterprise code runs.
   *
   * @param catalogProperties the Gravitino catalog properties the dispatcher was given
   */
  private InMemoryCatalog inMemoryBackend(Map<String, String> catalogProperties) {
    Map<String, String> backendProperties =
        new HashMap<>(icebergCatalogProperties(catalogProperties));
    backendProperties.put(CatalogProperties.WAREHOUSE_LOCATION, "memory://warehouse");
    InMemoryCatalog backend = new InMemoryCatalog();
    backend.initialize("backend", backendProperties);
    backend.createNamespace(TABLE.namespace());
    serveWith(backend);
    return backend;
  }

  /**
   * Backs the delegate with Iceberg's own REST handlers over an integrity-aware JDBC catalog on H2,
   * which publishes an encrypted table's pointer and integrity hash in one transaction and verifies
   * the hash on load.
   *
   * @param uri the H2 database the catalog and its integrity store live in
   * @param catalogProperties the Gravitino catalog properties the dispatcher was given
   */
  private JdbcCatalogWithMetadataLocationSupport jdbcBackend(
      String uri, Map<String, String> catalogProperties) throws IOException {
    Map<String, String> backendProperties =
        new HashMap<>(icebergCatalogProperties(catalogProperties));
    backendProperties.put(CatalogProperties.URI, uri);
    backendProperties.put(
        CatalogProperties.WAREHOUSE_LOCATION,
        Files.createTempDirectory("ctas-integrity").toUri().toString());
    backendProperties.put("jdbc.schema-version", "V1");
    JdbcCatalogWithMetadataLocationSupport backend =
        H2IntegrityJdbcCatalogs.initialize(JDBC_BACKEND, backendProperties);
    backend.createNamespace(TABLE.namespace());
    serveWith(backend);
    return backend;
  }

  /**
   * The Iceberg catalog properties a loaded catalog gets, with the declaration an operator pairs
   * with a key (R9).
   */
  private static Map<String, String> icebergCatalogProperties(
      Map<String, String> catalogProperties) {
    return new IcebergConfig(declaredByDefault(catalogProperties), true)
        .getIcebergCatalogProperties();
  }

  /**
   * The catalog properties an operator would deploy: a catalog that binds an encryption key also
   * declares {@code table-default.format-version=3}, because Gravitino declares none of its own
   * (R9). A catalog that binds no key is left exactly as it is, so a plain backend still creates at
   * Iceberg's own default.
   *
   * @param catalogProperties the catalog properties the dispatcher was given
   * @return the properties with the operator's declaration where a key is bound
   */
  private static Map<String, String> declaredByDefault(Map<String, String> catalogProperties) {
    if (!IcebergEncryptionCatalogBinding.bindsKey(catalogProperties)) {
      return catalogProperties;
    }
    Map<String, String> declared = new HashMap<>();
    declared.put(IcebergFormatVersionResolution.TABLE_DEFAULT_FORMAT_VERSION, "3");
    declared.putAll(catalogProperties);
    if (StringUtils.isEmpty(
        declared.get(IcebergFormatVersionResolution.TABLE_DEFAULT_FORMAT_VERSION))) {
      declared.remove(IcebergFormatVersionResolution.TABLE_DEFAULT_FORMAT_VERSION);
    }
    return declared;
  }

  /** Serves the delegate's creates, stages, loads and commits from a real Iceberg catalog. */
  private void serveWith(Catalog backend) {
    when(delegate.createTable(eq(context), any(Namespace.class), any(CreateTableRequest.class)))
        .thenAnswer(
            invocation -> {
              Namespace namespace = invocation.getArgument(1);
              CreateTableRequest request = invocation.getArgument(2);
              return request.stageCreate()
                  ? CatalogHandlers.stageTableCreate(backend, namespace, request)
                  : CatalogHandlers.createTable(backend, namespace, request);
            });
    when(delegate.loadTable(eq(context), any(TableIdentifier.class)))
        .thenAnswer(
            invocation ->
                CatalogHandlers.loadTable(backend, invocation.getArgument(1), SnapshotMode.ALL));
    when(delegate.updateTable(
            eq(context), any(TableIdentifier.class), any(UpdateTableRequest.class)))
        .thenAnswer(
            invocation ->
                CatalogHandlers.updateTable(
                    backend, invocation.getArgument(1), invocation.getArgument(2)));
  }

  /**
   * Runs a CTAS the way an engine does: Iceberg's own {@link RESTSessionCatalog} stages the create,
   * lets the engine write, and commits the transaction, every request crossing the dispatcher under
   * test after a JSON round trip.
   *
   * @param write what the engine does between stage and commit
   */
  private void restStageThenCommit(EngineWrite write) throws Exception {
    try (RESTSessionCatalog restCatalog =
        new RESTSessionCatalog(config -> new DispatcherRestClient(), (ctx, props) -> newFileIO())) {
      restCatalog.initialize("irc", Collections.emptyMap());
      Transaction transaction =
          restCatalog.buildTable(SessionContext.createEmpty(), TABLE, SCHEMA).createTransaction();
      write.write(transaction);
      transaction.commitTransaction();
    }
  }

  /**
   * Asserts that a CTAS left exactly one audit record, at commit: the stage request recorded
   * nothing.
   *
   * @return the commit's audit record
   */
  private Map<String, String> assertOnlyTheCommitRecorded() {
    Assertions.assertEquals(2, requestAuditRecords.size(), "one stage and one commit request");
    Assertions.assertTrue(requestAuditRecords.get(0).isEmpty(), "the stage records nothing");
    Map<String, String> commitRecord = requestAuditRecords.get(1);
    Assertions.assertFalse(commitRecord.isEmpty(), "the commit yields the one audit record");
    return commitRecord;
  }

  private static FileIO newFileIO() {
    return new InMemoryFileIO();
  }

  /** Serializes a REST message and reads it back, as it would cross the wire. */
  private static <T> T overTheWire(Object message, Class<T> type) {
    ObjectMapper mapper = IcebergObjectMapper.getInstance();
    try {
      return mapper.readValue(mapper.writeValueAsString(message), type);
    } catch (JsonProcessingException e) {
      throw new UncheckedIOException(e);
    }
  }

  /**
   * An Iceberg {@link RESTClient} whose transport is this test's dispatcher chain: table creates,
   * loads and commits reach the dispatcher under test after a JSON round trip, and nothing else is
   * served.
   */
  private final class DispatcherRestClient implements RESTClient {

    @Override
    public void head(
        String path, Map<String, String> headers, Consumer<ErrorResponse> errorHandler) {
      throw new UnsupportedOperationException(path);
    }

    @Override
    public <T extends RESTResponse> T delete(
        String path,
        Class<T> responseType,
        Map<String, String> headers,
        Consumer<ErrorResponse> errorHandler) {
      throw new UnsupportedOperationException(path);
    }

    @Override
    public <T extends RESTResponse> T get(
        String path,
        Map<String, String> queryParams,
        Class<T> responseType,
        Map<String, String> headers,
        Consumer<ErrorResponse> errorHandler) {
      if (responseType == LoadTableResponse.class) {
        LoadTableResponse response;
        try {
          response = restEntryPoint().loadTable(context, TABLE);
        } finally {
          requestAuditRecords.add(RequestContext.takeAuditExtras());
        }
        return overTheWire(response, responseType);
      }
      Preconditions.checkArgument(responseType == ConfigResponse.class, "unexpected GET %s", path);
      return responseType.cast(
          ConfigResponse.builder().withDefaults(keyedCatalogConfigToClients()).build());
    }

    // A table load asks for the response headers; this transport has none to report.
    @Override
    public <T extends RESTResponse> T get(
        String path,
        Map<String, String> queryParams,
        Class<T> responseType,
        Map<String, String> headers,
        Consumer<ErrorResponse> errorHandler,
        Consumer<Map<String, String>> responseHeaders) {
      return get(path, queryParams, responseType, headers, errorHandler);
    }

    @Override
    public <T extends RESTResponse> T post(
        String path,
        RESTRequest body,
        Class<T> responseType,
        Map<String, String> headers,
        Consumer<ErrorResponse> errorHandler) {
      if (!(body instanceof CreateTableRequest) && !(body instanceof UpdateTableRequest)) {
        throw new UnsupportedOperationException(path);
      }
      LoadTableResponse response;
      try {
        response =
            body instanceof CreateTableRequest
                ? restEntryPoint()
                    .createTable(
                        context, TABLE.namespace(), overTheWire(body, CreateTableRequest.class))
                : restEntryPoint()
                    .updateTable(context, TABLE, overTheWire(body, UpdateTableRequest.class));
      } finally {
        // What the event dispatcher does at the end of every request, successful or not.
        requestAuditRecords.add(RequestContext.takeAuditExtras());
      }
      return overTheWire(response, responseType);
    }

    @Override
    public <T extends RESTResponse> T postForm(
        String path,
        Map<String, String> formData,
        Class<T> responseType,
        Map<String, String> headers,
        Consumer<ErrorResponse> errorHandler) {
      throw new UnsupportedOperationException(path);
    }

    @Override
    public void close() {}
  }

  private static KmsClient validKey(KmsReference reference) {
    KmsKeyProperties properties = mock(KmsKeyProperties.class);
    when(properties.enabled()).thenReturn(true);
    when(properties.supportsWrapping()).thenReturn(true);
    KmsClient client = mock(KmsClient.class);
    when(client.getKeyProperties(reference)).thenReturn(Optional.of(properties));
    return client;
  }

  private static CreateTableRequest createRequest(String keyId, boolean staged) {
    return createRequest(
        keyId == null
            ? null
            : Collections.singletonMap(TableProperties.ENCRYPTION_TABLE_KEY, keyId),
        staged);
  }

  private static CreateTableRequest createRequest(Map<String, String> properties, boolean staged) {
    CreateTableRequest.Builder builder =
        CreateTableRequest.builder().withName(TABLE.name()).withSchema(SCHEMA);
    if (properties != null) {
      builder = builder.setProperties(properties);
    }
    if (staged) {
      builder = builder.stageCreate();
    }
    return builder.build();
  }

  private static PolicyEntity encryptionPolicy(
      boolean required, IcebergEncryptionContent.Enforcement enforcement) {
    PolicyEntity policy = mock(PolicyEntity.class);
    when(policy.name()).thenReturn(POLICY_NAME);
    when(policy.enabled()).thenReturn(true);
    when(policy.policyType()).thenReturn(Policy.BuiltInType.ICEBERG_ENCRYPTION);
    when(policy.content())
        .thenReturn(
            PolicyContents.icebergEncryption(
                IcebergEncryptionContent.CURRENT_SCHEMA_VERSION,
                required,
                List.of(new KmsReference(SOURCE, KEY_ID)),
                enforcement));
    return policy;
  }

  private CreateTableRequest capturedCreate(Namespace namespace) {
    ArgumentCaptor<CreateTableRequest> captor = ArgumentCaptor.forClass(CreateTableRequest.class);
    verify(delegate).createTable(eq(context), eq(namespace), captor.capture());
    return captor.getValue();
  }

  private UpdateTableRequest capturedUpdate() {
    ArgumentCaptor<UpdateTableRequest> captor = ArgumentCaptor.forClass(UpdateTableRequest.class);
    verify(delegate).updateTable(eq(context), eq(TABLE), captor.capture());
    return captor.getValue();
  }

  /** The properties a commit ends up setting, later updates winning over earlier ones. */
  private static Map<String, String> lastSetProperties(UpdateTableRequest request) {
    Map<String, String> properties = new HashMap<>();
    for (MetadataUpdate update : request.updates()) {
      if (update instanceof MetadataUpdate.SetProperties) {
        properties.putAll(((MetadataUpdate.SetProperties) update).updated());
      }
    }
    return properties;
  }

  /** The format versions a commit upgrades to, in the order its updates name them. */
  private static List<Integer> formatVersionUpgrades(UpdateTableRequest request) {
    return request.updates().stream()
        .filter(MetadataUpdate.UpgradeFormatVersion.class::isInstance)
        .map(MetadataUpdate.UpgradeFormatVersion.class::cast)
        .map(MetadataUpdate.UpgradeFormatVersion::formatVersion)
        .collect(Collectors.toList());
  }

  private static Set<String> removedProperties(UpdateTableRequest request) {
    Set<String> removed = new HashSet<>();
    for (MetadataUpdate update : request.updates()) {
      if (update instanceof MetadataUpdate.RemoveProperties) {
        removed.addAll(((MetadataUpdate.RemoveProperties) update).removed());
      }
    }
    return removed;
  }

  private Map<String, String> takeExtras() {
    Map<String, String> extras = RequestContext.takeAuditExtras();
    Assertions.assertFalse(extras.isEmpty(), "Expected encryption extras to be stashed");
    return extras;
  }

  private static UpdateTableRequest request(MetadataUpdate... updates) {
    return new UpdateTableRequest(Collections.emptyList(), Arrays.asList(updates));
  }

  /** A staged-create commit as a REST client sends it: identifier included. */
  private static UpdateTableRequest stagedCommit(MetadataUpdate... updates) {
    return UpdateTableRequest.create(
        TABLE, List.of(new UpdateRequirement.AssertTableDoesNotExist()), Arrays.asList(updates));
  }

  /**
   * A complete {@code assert-create} commit, carrying every update {@code
   * RESTSessionCatalog#createChanges} puts in one, so a real backend can publish it. {@link
   * #stagedCommit} carries only the updates a test varies, which is enough for a mocked delegate
   * and not enough for {@code CatalogHandlers.create} to build a table from.
   *
   * @param formatVersion the version the commit's {@code upgrade-format-version} names
   * @param properties the properties the commit sets
   */
  private static UpdateTableRequest stagedCreateCommit(
      int formatVersion, Map<String, String> properties) {
    return stagedCommit(
        new MetadataUpdate.AssignUUID(UUID.randomUUID().toString()),
        new MetadataUpdate.UpgradeFormatVersion(formatVersion),
        new MetadataUpdate.AddSchema(SCHEMA),
        new MetadataUpdate.SetCurrentSchema(-1),
        new MetadataUpdate.AddPartitionSpec(PartitionSpec.unpartitioned()),
        new MetadataUpdate.SetDefaultPartitionSpec(-1),
        new MetadataUpdate.AddSortOrder(SortOrder.unsorted()),
        new MetadataUpdate.SetDefaultSortOrder(-1),
        new MetadataUpdate.SetLocation(
            "memory://warehouse/" + TABLE.namespace() + "/" + TABLE.name()),
        new MetadataUpdate.SetProperties(properties));
  }

  private static MetadataUpdate setProperty(String property, String value) {
    return new MetadataUpdate.SetProperties(Map.of(property, value));
  }

  /** Whether the dispatcher under test carries an admission service. */
  private enum Mode {
    INVARIANT_ONLY,
    GOVERNED
  }

  /** What an engine does with a staged table before it commits it. */
  @FunctionalInterface
  private interface EngineWrite {
    void write(Transaction transaction) throws Exception;
  }

  private static BaseEncryptedKey encryptedKey(String keyId) {
    return new BaseEncryptedKey(
        keyId, ByteBuffer.wrap(new byte[] {1, 2, 3}), null, Collections.emptyMap());
  }
}
