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
  private TableEventHandler tableHandler;
  private SchemaEventHandler schemaHandler;

  private static final NameIdentifier TABLE_IDENT = NameIdentifier.of("ml", "cat", "db1", "tbl");
  private static final NameIdentifier SCHEMA_IDENT = NameIdentifier.of("ml", "cat", "db1");

  @BeforeEach
  public void setUp() {
    searchService = mock(SearchService.class);
    tableHandler = new TableEventHandler(searchService);
    schemaHandler = new SchemaEventHandler(searchService);
  }

  // -------------------------------------------------------------------------
  // Iceberg table events — TableEventHandler behaviour
  // -------------------------------------------------------------------------

  @Test
  public void testIcebergCreateTableSyncsTable() {
    tableHandler.handleEvent(new TestIcebergTableEvent(TABLE_IDENT, OperationType.CREATE_TABLE));
    verify(searchService, times(1))
        .synchronizeMetadata(eq(TABLE_IDENT), eq(EntityType.TABLE), eq(false));
  }

  @Test
  public void testIcebergRegisterTableSyncsTable() {
    tableHandler.handleEvent(new TestIcebergTableEvent(TABLE_IDENT, OperationType.REGISTER_TABLE));
    verify(searchService, times(1))
        .synchronizeMetadata(eq(TABLE_IDENT), eq(EntityType.TABLE), eq(false));
  }

  @Test
  public void testIcebergAlterTableSyncsTable() {
    tableHandler.handleEvent(new TestIcebergTableEvent(TABLE_IDENT, OperationType.ALTER_TABLE));
    verify(searchService, times(1))
        .synchronizeMetadata(eq(TABLE_IDENT), eq(EntityType.TABLE), eq(false));
  }

  @Test
  public void testIcebergRenameTableSameSchema() {
    NameIdentifier oldIdent = NameIdentifier.of("ml", "cat", "db1", "old_tbl");
    NameIdentifier newIdent = NameIdentifier.of("ml", "cat", "db1", "new_tbl");

    tableHandler.handleEvent(buildRenameEvent(oldIdent, "db1", "new_tbl"));

    verify(searchService, times(1)).removeMetadata(eq(oldIdent), eq(EntityType.TABLE), eq(false));
    verify(searchService, times(1))
        .synchronizeMetadata(eq(newIdent), eq(EntityType.TABLE), eq(false));
  }

  @Test
  public void testIcebergRenameTableCrossSchema() {
    // Rename from ml.cat.db1.old_tbl → ml.cat.db2.new_tbl (schema also changes)
    NameIdentifier oldIdent = NameIdentifier.of("ml", "cat", "db1", "old_tbl");
    NameIdentifier newIdent = NameIdentifier.of("ml", "cat", "db2", "new_tbl");

    tableHandler.handleEvent(buildRenameEvent(oldIdent, "db2", "new_tbl"));

    verify(searchService, times(1)).removeMetadata(eq(oldIdent), eq(EntityType.TABLE), eq(false));
    verify(searchService, times(1))
        .synchronizeMetadata(eq(newIdent), eq(EntityType.TABLE), eq(false));
  }

  @Test
  public void testIcebergDropTableRemovesTable() {
    tableHandler.handleEvent(new TestIcebergTableEvent(TABLE_IDENT, OperationType.DROP_TABLE));
    verify(searchService, times(1))
        .removeMetadata(eq(TABLE_IDENT), eq(EntityType.TABLE), eq(false));
  }

  // -------------------------------------------------------------------------
  // Iceberg namespace events — SchemaEventHandler behaviour
  // -------------------------------------------------------------------------

  @Test
  public void testIcebergCreateSchemaSyncsSchema() {
    schemaHandler.handleEvent(
        new TestIcebergNamespaceEvent(SCHEMA_IDENT, OperationType.CREATE_SCHEMA));
    verify(searchService, times(1))
        .synchronizeMetadata(eq(SCHEMA_IDENT), eq(EntityType.SCHEMA), eq(false));
  }

  @Test
  public void testIcebergAlterSchemaSyncsSchema() {
    schemaHandler.handleEvent(
        new TestIcebergNamespaceEvent(SCHEMA_IDENT, OperationType.ALTER_SCHEMA));
    verify(searchService, times(1))
        .synchronizeMetadata(eq(SCHEMA_IDENT), eq(EntityType.SCHEMA), eq(false));
  }

  @Test
  public void testIcebergDropSchemaRemovesSchemaWithCascade() {
    schemaHandler.handleEvent(
        new TestIcebergNamespaceEvent(SCHEMA_IDENT, OperationType.DROP_SCHEMA));
    verify(searchService, times(1))
        .removeMetadata(eq(SCHEMA_IDENT), eq(EntityType.SCHEMA), eq(true));
  }

  // -------------------------------------------------------------------------
  // DataDiscoveryListener routing — IcebergTableEvent → tableHandler
  // -------------------------------------------------------------------------

  @Test
  public void testDataDiscoveryListenerRoutesIcebergTableEventToTableHandler() throws Exception {
    DataDiscoveryListener listener = new DataDiscoveryListener();
    EventHandler mockTableHandler = mock(EventHandler.class);
    EventHandler mockSchemaHandler = mock(EventHandler.class);
    injectHandlers(listener, mockTableHandler, mockSchemaHandler);

    TestIcebergTableEvent event =
        new TestIcebergTableEvent(TABLE_IDENT, OperationType.RENAME_TABLE);
    listener.onPostEvent(event);

    verify(mockTableHandler, times(1)).handleEvent(eq(event));
    verifyNoInteractions(mockSchemaHandler);
  }

  // -------------------------------------------------------------------------
  // DataDiscoveryListener routing — IcebergNamespaceEvent → schemaHandler
  // -------------------------------------------------------------------------

  @Test
  public void testDataDiscoveryListenerRoutesIcebergNamespaceEventToSchemaHandler()
      throws Exception {
    DataDiscoveryListener listener = new DataDiscoveryListener();
    EventHandler mockTableHandler = mock(EventHandler.class);
    EventHandler mockSchemaHandler = mock(EventHandler.class);
    injectHandlers(listener, mockTableHandler, mockSchemaHandler);

    TestIcebergNamespaceEvent event =
        new TestIcebergNamespaceEvent(SCHEMA_IDENT, OperationType.CREATE_SCHEMA);
    listener.onPostEvent(event);

    verify(mockSchemaHandler, times(1)).handleEvent(eq(event));
    verifyNoInteractions(mockTableHandler);
  }

  // -------------------------------------------------------------------------
  // DataDiscoveryListener routing — failure events are ignored
  // -------------------------------------------------------------------------

  @Test
  public void testDataDiscoveryListenerIgnoresIcebergTableFailureEvent() throws Exception {
    DataDiscoveryListener listener = new DataDiscoveryListener();
    EventHandler mockTableHandler = mock(EventHandler.class);
    EventHandler mockSchemaHandler = mock(EventHandler.class);
    injectHandlers(listener, mockTableHandler, mockSchemaHandler);

    // IcebergTableFailureEvent extends IcebergFailureEvent (not IcebergTableEvent),
    // so it is naturally excluded from the instanceof IcebergTableEvent check.
    TestIcebergTableFailureEvent failureEvent = new TestIcebergTableFailureEvent(TABLE_IDENT);
    listener.onPostEvent(failureEvent);

    verifyNoInteractions(mockTableHandler);
    verifyNoInteractions(mockSchemaHandler);
  }

  // -------------------------------------------------------------------------
  // Helpers
  // -------------------------------------------------------------------------

  private static void injectHandlers(
      DataDiscoveryListener listener, EventHandler tableHandler, EventHandler schemaHandler)
      throws Exception {
    Field field = DataDiscoveryListener.class.getDeclaredField("eventHandlers");
    field.setAccessible(true);
    field.set(
        listener,
        ImmutableMap.of(TableEvent.class, tableHandler, SchemaEvent.class, schemaHandler));
  }

  /**
   * Builds a mocked {@link IcebergRenameTableEvent} where the destination is in {@code destSchema}
   * with name {@code destTable}, under the same metalake and catalog as {@code sourceIdent}.
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
