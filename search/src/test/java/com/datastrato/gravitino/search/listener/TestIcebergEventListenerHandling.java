/*
 * Copyright 2024 Datastrato Pvt Ltd.
 * This software is licensed under the Apache License version 2.
 */
package com.datastrato.gravitino.search.listener;

import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.datastrato.gravitino.search.service.SearchService;
import com.google.common.collect.ImmutableMap;
import java.lang.reflect.Field;
import org.apache.gravitino.Entity.EntityType;
import org.apache.gravitino.NameIdentifier;
import org.apache.gravitino.listener.api.event.IcebergNamespaceEvent;
import org.apache.gravitino.listener.api.event.IcebergRenameTableEvent;
import org.apache.gravitino.listener.api.event.IcebergRequestContext;
import org.apache.gravitino.listener.api.event.IcebergTableEvent;
import org.apache.gravitino.listener.api.event.IcebergTableFailureEvent;
import org.apache.gravitino.listener.api.event.OperationType;
import org.apache.gravitino.listener.api.event.SchemaEvent;
import org.apache.gravitino.listener.api.event.TableEvent;
import org.apache.iceberg.catalog.Namespace;
import org.apache.iceberg.catalog.TableIdentifier;
import org.apache.iceberg.rest.requests.RenameTableRequest;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

public class TestIcebergEventListenerHandling {

  private SearchService searchService;
  private IcebergEventHandler icebergHandler;

  private static final NameIdentifier TABLE_IDENT = NameIdentifier.of("ml", "cat", "db1", "tbl");
  private static final NameIdentifier SCHEMA_IDENT = NameIdentifier.of("ml", "cat", "db1");

  @BeforeEach
  public void setUp() {
    searchService = mock(SearchService.class);
    icebergHandler = new IcebergEventHandler(searchService);
  }

  // -------------------------------------------------------------------------
  // Iceberg table events — IcebergEventHandler behaviour
  // -------------------------------------------------------------------------

  @Test
  public void testIcebergCreateTableSyncsTable() {
    icebergHandler.handleEvent(new TestIcebergTableEvent(TABLE_IDENT, OperationType.CREATE_TABLE));
    verify(searchService, times(1))
        .synchronizeMetadata(eq(TABLE_IDENT), eq(EntityType.TABLE), eq(false));
  }

  @Test
  public void testIcebergRegisterTableSyncsTable() {
    icebergHandler.handleEvent(
        new TestIcebergTableEvent(TABLE_IDENT, OperationType.REGISTER_TABLE));
    verify(searchService, times(1))
        .synchronizeMetadata(eq(TABLE_IDENT), eq(EntityType.TABLE), eq(false));
  }

  @Test
  public void testIcebergAlterTableSyncsTable() {
    icebergHandler.handleEvent(new TestIcebergTableEvent(TABLE_IDENT, OperationType.ALTER_TABLE));
    verify(searchService, times(1))
        .synchronizeMetadata(eq(TABLE_IDENT), eq(EntityType.TABLE), eq(false));
  }

  @Test
  public void testIcebergRenameTableSameSchema() {
    NameIdentifier oldIdent = NameIdentifier.of("ml", "cat", "db1", "old_tbl");
    NameIdentifier newIdent = NameIdentifier.of("ml", "cat", "db1", "new_tbl");

    icebergHandler.handleEvent(buildRenameEvent(oldIdent, "db1", "new_tbl"));

    verify(searchService, times(1)).removeMetadata(eq(oldIdent), eq(EntityType.TABLE), eq(false));
    verify(searchService, times(1))
        .synchronizeMetadata(eq(newIdent), eq(EntityType.TABLE), eq(false));
  }

  @Test
  public void testIcebergRenameTableCrossSchema() {
    // Rename from ml.cat.db1.old_tbl → ml.cat.db2.new_tbl (schema also changes)
    NameIdentifier oldIdent = NameIdentifier.of("ml", "cat", "db1", "old_tbl");
    NameIdentifier newIdent = NameIdentifier.of("ml", "cat", "db2", "new_tbl");

    icebergHandler.handleEvent(buildRenameEvent(oldIdent, "db2", "new_tbl"));

    verify(searchService, times(1)).removeMetadata(eq(oldIdent), eq(EntityType.TABLE), eq(false));
    verify(searchService, times(1))
        .synchronizeMetadata(eq(newIdent), eq(EntityType.TABLE), eq(false));
  }

  @Test
  public void testIcebergDropTableRemovesTable() {
    icebergHandler.handleEvent(new TestIcebergTableEvent(TABLE_IDENT, OperationType.DROP_TABLE));
    verify(searchService, times(1))
        .removeMetadata(eq(TABLE_IDENT), eq(EntityType.TABLE), eq(false));
  }

  // -------------------------------------------------------------------------
  // Iceberg namespace events — IcebergEventHandler behaviour
  // -------------------------------------------------------------------------

  @Test
  public void testIcebergCreateSchemaSyncsSchema() {
    icebergHandler.handleEvent(
        new TestIcebergNamespaceEvent(SCHEMA_IDENT, OperationType.CREATE_SCHEMA));
    verify(searchService, times(1))
        .synchronizeMetadata(eq(SCHEMA_IDENT), eq(EntityType.SCHEMA), eq(false));
  }

  @Test
  public void testIcebergAlterSchemaSyncsSchema() {
    icebergHandler.handleEvent(
        new TestIcebergNamespaceEvent(SCHEMA_IDENT, OperationType.ALTER_SCHEMA));
    verify(searchService, times(1))
        .synchronizeMetadata(eq(SCHEMA_IDENT), eq(EntityType.SCHEMA), eq(false));
  }

  @Test
  public void testIcebergDropSchemaRemovesSchemaWithCascade() {
    icebergHandler.handleEvent(
        new TestIcebergNamespaceEvent(SCHEMA_IDENT, OperationType.DROP_SCHEMA));
    verify(searchService, times(1))
        .removeMetadata(eq(SCHEMA_IDENT), eq(EntityType.SCHEMA), eq(true));
  }

  // -------------------------------------------------------------------------
  // DataDiscoveryListener routing — IcebergTableEvent → icebergEventHandler
  // -------------------------------------------------------------------------

  @Test
  public void testDataDiscoveryListenerRoutesIcebergTableEventToIcebergHandler() throws Exception {
    DataDiscoveryListener listener = new DataDiscoveryListener();
    IcebergEventHandler mockIcebergHandler = mock(IcebergEventHandler.class);
    EventHandler mockTableHandler = mock(EventHandler.class);
    EventHandler mockSchemaHandler = mock(EventHandler.class);
    injectHandlers(listener, mockTableHandler, mockSchemaHandler, mockIcebergHandler);

    TestIcebergTableEvent event =
        new TestIcebergTableEvent(TABLE_IDENT, OperationType.RENAME_TABLE);
    listener.onPostEvent(event);

    verify(mockIcebergHandler, times(1)).handleEvent(eq(event));
    verifyNoInteractions(mockTableHandler);
    verifyNoInteractions(mockSchemaHandler);
  }

  // -------------------------------------------------------------------------
  // DataDiscoveryListener routing — IcebergNamespaceEvent → icebergEventHandler
  // -------------------------------------------------------------------------

  @Test
  public void testDataDiscoveryListenerRoutesIcebergNamespaceEventToIcebergHandler()
      throws Exception {
    DataDiscoveryListener listener = new DataDiscoveryListener();
    IcebergEventHandler mockIcebergHandler = mock(IcebergEventHandler.class);
    EventHandler mockTableHandler = mock(EventHandler.class);
    EventHandler mockSchemaHandler = mock(EventHandler.class);
    injectHandlers(listener, mockTableHandler, mockSchemaHandler, mockIcebergHandler);

    TestIcebergNamespaceEvent event =
        new TestIcebergNamespaceEvent(SCHEMA_IDENT, OperationType.CREATE_SCHEMA);
    listener.onPostEvent(event);

    verify(mockIcebergHandler, times(1)).handleEvent(eq(event));
    verifyNoInteractions(mockTableHandler);
    verifyNoInteractions(mockSchemaHandler);
  }

  // -------------------------------------------------------------------------
  // DataDiscoveryListener routing — failure events are ignored
  // -------------------------------------------------------------------------

  @Test
  public void testDataDiscoveryListenerIgnoresIcebergTableFailureEvent() throws Exception {
    DataDiscoveryListener listener = new DataDiscoveryListener();
    IcebergEventHandler mockIcebergHandler = mock(IcebergEventHandler.class);
    EventHandler mockTableHandler = mock(EventHandler.class);
    EventHandler mockSchemaHandler = mock(EventHandler.class);
    injectHandlers(listener, mockTableHandler, mockSchemaHandler, mockIcebergHandler);

    // IcebergTableFailureEvent extends IcebergFailureEvent, NOT IcebergTableEvent,
    // so IcebergEventUtils.isSubclassOf check returns false and the event is silently dropped.
    TestIcebergTableFailureEvent failureEvent = new TestIcebergTableFailureEvent(TABLE_IDENT);
    listener.onPostEvent(failureEvent);

    verifyNoInteractions(mockIcebergHandler);
    verifyNoInteractions(mockTableHandler);
    verifyNoInteractions(mockSchemaHandler);
  }

  // -------------------------------------------------------------------------
  // Helpers
  // -------------------------------------------------------------------------

  private static void injectHandlers(
      DataDiscoveryListener listener,
      EventHandler tableHandler,
      EventHandler schemaHandler,
      IcebergEventHandler icebergHandler)
      throws Exception {
    Field handlersField = DataDiscoveryListener.class.getDeclaredField("eventHandlers");
    handlersField.setAccessible(true);
    handlersField.set(
        listener,
        ImmutableMap.of(TableEvent.class, tableHandler, SchemaEvent.class, schemaHandler));

    Field icebergField = DataDiscoveryListener.class.getDeclaredField("icebergEventHandler");
    icebergField.setAccessible(true);
    icebergField.set(listener, icebergHandler);
  }

  /**
   * Builds a mocked {@link IcebergRenameTableEvent} where the destination is in {@code destSchema}
   * with name {@code destTable}.
   */
  private static IcebergRenameTableEvent buildRenameEvent(
      NameIdentifier sourceIdent, String destSchema, String destTable) {
    TableIdentifier mockDest = mock(TableIdentifier.class);
    when(mockDest.namespace()).thenReturn(Namespace.of(destSchema));
    when(mockDest.name()).thenReturn(destTable);

    RenameTableRequest mockReq = mock(RenameTableRequest.class);
    when(mockReq.destination()).thenReturn(mockDest);

    IcebergRenameTableEvent renameEvent = mock(IcebergRenameTableEvent.class);
    when(renameEvent.identifier()).thenReturn(sourceIdent);
    when(renameEvent.operationType()).thenReturn(OperationType.RENAME_TABLE);
    when(renameEvent.renameTableRequest()).thenReturn(mockReq);
    return renameEvent;
  }

  private static IcebergRequestContext mockRequestContext() {
    IcebergRequestContext ctx = mock(IcebergRequestContext.class);
    when(ctx.userName()).thenReturn("test");
    when(ctx.remoteHostName()).thenReturn("localhost");
    when(ctx.httpHeaders()).thenReturn(ImmutableMap.of());
    return ctx;
  }

  /** Generic IcebergTableEvent stub parameterised by operation type. */
  private static class TestIcebergTableEvent extends IcebergTableEvent {
    private final OperationType operationType;

    TestIcebergTableEvent(NameIdentifier identifier, OperationType operationType) {
      super(mockRequestContext(), identifier);
      this.operationType = operationType;
    }

    @Override
    public OperationType operationType() {
      return operationType;
    }
  }

  /** Generic IcebergNamespaceEvent stub parameterised by operation type. */
  private static class TestIcebergNamespaceEvent extends IcebergNamespaceEvent {
    private final OperationType operationType;

    TestIcebergNamespaceEvent(NameIdentifier identifier, OperationType operationType) {
      super(mockRequestContext(), identifier);
      this.operationType = operationType;
    }

    @Override
    public OperationType operationType() {
      return operationType;
    }
  }

  /** IcebergTableFailureEvent stub to verify failure events are ignored by the listener. */
  private static class TestIcebergTableFailureEvent extends IcebergTableFailureEvent {
    TestIcebergTableFailureEvent(NameIdentifier identifier) {
      super(mockRequestContext(), identifier, new RuntimeException("simulated failure"));
    }

    @Override
    public OperationType operationType() {
      return OperationType.CREATE_TABLE;
    }
  }
}
