/*
 * Copyright 2026 Datastrato Pvt Ltd.
 */
package org.apache.gravitino.iceberg.service.dispatcher;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import java.nio.ByteBuffer;
import java.util.Arrays;
import java.util.Collections;
import java.util.Map;
import org.apache.gravitino.listener.EventBus;
import org.apache.gravitino.listener.api.EventListenerPlugin;
import org.apache.gravitino.listener.api.event.Event;
import org.apache.gravitino.listener.api.event.IcebergEncryptionAuditInfos;
import org.apache.gravitino.listener.api.event.IcebergEncryptionAuditInfos.Reason;
import org.apache.gravitino.listener.api.event.IcebergRequestContext;
import org.apache.gravitino.listener.api.event.IcebergUpdateTableFailureEvent;
import org.apache.gravitino.listener.api.event.OperationStatus;
import org.apache.gravitino.listener.api.event.OperationType;
import org.apache.gravitino.utils.RequestContext;
import org.apache.iceberg.MetadataUpdate;
import org.apache.iceberg.Snapshot;
import org.apache.iceberg.TableProperties;
import org.apache.iceberg.catalog.Namespace;
import org.apache.iceberg.catalog.TableIdentifier;
import org.apache.iceberg.encryption.BaseEncryptedKey;
import org.apache.iceberg.rest.requests.UpdateTableRequest;
import org.apache.iceberg.rest.responses.LoadTableResponse;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

/** Tests encryption invariant enforcement and audit metadata in the Iceberg table dispatcher. */
public class TestIcebergTableEncryptionDispatcher {

  private static final String METALAKE = "metalake";
  private static final String CATALOG = "catalog";
  private static final TableIdentifier TABLE = TableIdentifier.of(Namespace.of("schema"), "table");
  private static final String ADDED_KEY_IDS =
      IcebergEncryptionAuditInfos.PREFIX + "addedEncryptionKeyIds";
  private static final String REMOVED_KEY_IDS =
      IcebergEncryptionAuditInfos.PREFIX + "removedEncryptionKeyIds";
  private static final String COMMIT_ID = IcebergEncryptionAuditInfos.PREFIX + "commitId";

  private IcebergTableOperationDispatcher delegate;
  private EventListenerPlugin listener;
  private EventBus eventBus;
  private IcebergRequestContext context;
  private IcebergTableEncryptionDispatcher dispatcher;

  @AfterEach
  void clearRequestContext() {
    RequestContext.clear();
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
    dispatcher = new IcebergTableEncryptionDispatcher(delegate);
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
    verify(context).withAuditExtras(expectedExtras);

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

  @Test
  void testDeniedKeyIdRemovalDoesNotReachBackend() {
    UpdateTableRequest request =
        request(
            new MetadataUpdate.RemoveProperties(
                Collections.singleton(TableProperties.ENCRYPTION_TABLE_KEY)));

    Assertions.assertThrows(
        IllegalArgumentException.class, () -> dispatcher.updateTable(context, TABLE, request));

    verifyNoInteractions(delegate);
    Assertions.assertEquals(
        Reason.ENCRYPTION_KEY_CHANGE_DENIED.code(),
        takeExtras().get(IcebergEncryptionAuditInfos.REASON));
  }

  @Test
  void testNullConstructorAndUpdateArgumentsAreRejectedAsIllegalArguments() {
    Assertions.assertEquals(
        "dispatcher cannot be null",
        Assertions.assertThrows(
                IllegalArgumentException.class, () -> new IcebergTableEncryptionDispatcher(null))
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

  private Map<String, String> takeExtras() {
    Map<String, String> extras = RequestContext.takeAuditExtras();
    Assertions.assertFalse(extras.isEmpty(), "Expected encryption extras to be stashed");
    return extras;
  }

  private static UpdateTableRequest request(MetadataUpdate... updates) {
    return new UpdateTableRequest(Collections.emptyList(), Arrays.asList(updates));
  }

  private static BaseEncryptedKey encryptedKey(String keyId) {
    return new BaseEncryptedKey(
        keyId, ByteBuffer.wrap(new byte[] {1, 2, 3}), null, Collections.emptyMap());
  }
}
