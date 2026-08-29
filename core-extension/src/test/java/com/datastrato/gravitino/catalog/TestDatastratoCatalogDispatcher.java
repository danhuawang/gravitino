/*
 * Copyright 2026 Datastrato Pvt Ltd.
 */
package com.datastrato.gravitino.catalog;

import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.datastrato.gravitino.catalog.connection.CatalogConnectionSnapshot;
import com.datastrato.gravitino.catalog.connection.ConnectionPropertyClassifier;
import com.datastrato.gravitino.catalog.connection.ConnectionTestResult;
import com.datastrato.gravitino.catalog.connection.ConnectionTestStore;
import com.datastrato.gravitino.catalog.connection.ConnectionTestType;
import com.google.common.collect.ImmutableMap;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Collections;
import org.apache.gravitino.Catalog;
import org.apache.gravitino.CatalogChange;
import org.apache.gravitino.NameIdentifier;
import org.apache.gravitino.catalog.CatalogDispatcher;
import org.apache.gravitino.exceptions.CatalogNotInUseException;
import org.apache.gravitino.exceptions.ConnectionFailedException;
import org.apache.gravitino.exceptions.NoSuchCatalogException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class TestDatastratoCatalogDispatcher {
  private static final long COMPLETED_AT = 123456L;
  private static final NameIdentifier IDENTIFIER = NameIdentifier.of("metalake", "catalog");

  private CatalogDispatcher delegate;
  private ConnectionTestStore store;
  private DatastratoCatalogDispatcher dispatcher;
  private CatalogConnectionSnapshot before;

  @BeforeEach
  void setUp() {
    delegate = mock(CatalogDispatcher.class);
    store = mock(ConnectionTestStore.class);
    Clock clock = Clock.fixed(Instant.ofEpochMilli(COMPLETED_AT), ZoneOffset.UTC);
    dispatcher =
        new DatastratoCatalogDispatcher(delegate, store, new ConnectionPropertyClassifier(), clock);
    before =
        new CatalogConnectionSnapshot(
            10L, 1L, "catalog", "jdbc-mysql", ImmutableMap.of("jdbc-url", "jdbc:mysql://host/db"));
  }

  @Test
  void testExistingCatalogPassedAndFailedResults() throws Exception {
    when(store.loadCatalogConnectionSnapshot(IDENTIFIER)).thenReturn(before);
    dispatcher.testConnection(IDENTIFIER);
    verify(store)
        .recordTestResult(
            before,
            ConnectionTestType.CATALOG,
            ConnectionTestResult.Status.PASSED,
            COMPLETED_AT,
            null);

    ConnectionFailedException connectionFailure =
        new ConnectionFailedException("backend detail must not be stored");
    doThrow(connectionFailure).when(delegate).testConnection(IDENTIFIER);
    ConnectionFailedException thrown =
        assertThrows(ConnectionFailedException.class, () -> dispatcher.testConnection(IDENTIFIER));
    assertSame(connectionFailure, thrown);
    verify(store)
        .recordTestResult(
            before,
            ConnectionTestType.CATALOG,
            ConnectionTestResult.Status.FAILED,
            COMPLETED_AT,
            "Failed to connect to the catalog");
  }

  @Test
  void testOtherErrorsPassThroughWithoutPersistence() throws Exception {
    when(store.loadCatalogConnectionSnapshot(IDENTIFIER)).thenReturn(before);

    assertPassthrough(new IllegalArgumentException("No probe was attempted"));
    assertPassthrough(new NoSuchCatalogException("Catalog does not exist"));
    assertPassthrough(new UnsupportedOperationException("Probe is unsupported"));
    assertPassthrough(new CatalogNotInUseException("Catalog is disabled"));
    assertPassthrough(new RuntimeException("Unexpected server failure"));
    verify(store, never()).recordTestResult(any(), any(), any(), anyLong(), any());
  }

  @Test
  void testPersistenceFailureBecomesRequestFailure() throws Exception {
    when(store.loadCatalogConnectionSnapshot(IDENTIFIER)).thenReturn(before);
    RuntimeException storageFailure = new RuntimeException("database unavailable");
    when(store.recordTestResult(any(), any(), any(), anyLong(), any())).thenThrow(storageFailure);
    assertSame(
        storageFailure,
        assertThrows(RuntimeException.class, () -> dispatcher.testConnection(IDENTIFIER)));

    ConnectionFailedException connectionFailure = new ConnectionFailedException("unavailable");
    doThrow(connectionFailure).when(delegate).testConnection(IDENTIFIER);
    assertSame(
        storageFailure,
        assertThrows(RuntimeException.class, () -> dispatcher.testConnection(IDENTIFIER)));
  }

  @Test
  void testPreCreateProbeDoesNotPersist() throws Exception {
    dispatcher.testConnection(
        IDENTIFIER,
        Catalog.Type.RELATIONAL,
        "jdbc-mysql",
        null,
        Collections.singletonMap("jdbc-url", "jdbc:mysql://host/db"));

    verify(delegate)
        .testConnection(
            eq(IDENTIFIER), eq(Catalog.Type.RELATIONAL), eq("jdbc-mysql"), eq(null), any());
    verify(store, never()).loadCatalogConnectionSnapshot(any());
  }

  @Test
  void testCreateCatalogDelegates() {
    Catalog catalog = mock(Catalog.class);
    ImmutableMap<String, String> properties = ImmutableMap.of("jdbc-url", "jdbc:mysql://host/db");
    when(delegate.createCatalog(
            IDENTIFIER, Catalog.Type.RELATIONAL, "jdbc-mysql", "comment", properties))
        .thenReturn(catalog);

    assertSame(
        catalog,
        dispatcher.createCatalog(
            IDENTIFIER, Catalog.Type.RELATIONAL, "jdbc-mysql", "comment", properties));
    verify(delegate)
        .createCatalog(IDENTIFIER, Catalog.Type.RELATIONAL, "jdbc-mysql", "comment", properties);
  }

  @Test
  void testAlterInvalidatesOnlyForConnectionPropertyChange() {
    Catalog alteredCatalog = mock(Catalog.class);
    when(alteredCatalog.name()).thenReturn("catalog");
    when(delegate.alterCatalog(eq(IDENTIFIER), any(CatalogChange[].class)))
        .thenReturn(alteredCatalog);
    CatalogConnectionSnapshot changed =
        new CatalogConnectionSnapshot(
            10L, 2L, "catalog", "jdbc-mysql", ImmutableMap.of("jdbc-url", "jdbc:mysql://other/db"));
    when(store.loadCatalogConnectionSnapshot(IDENTIFIER)).thenReturn(before, changed);

    dispatcher.alterCatalog(
        IDENTIFIER, CatalogChange.setProperty("jdbc-url", "jdbc:mysql://other/db"));
    verify(store)
        .reconcileTestResultAfterCatalogChange(before, changed, ConnectionTestType.CATALOG, false);
    verify(store).reconcileCredentialTestResultsAfterCatalogChange(before, changed, false);

    CatalogConnectionSnapshot commentOnly =
        new CatalogConnectionSnapshot(
            10L,
            3L,
            "catalog",
            "jdbc-mysql",
            ImmutableMap.of("jdbc-url", "jdbc:mysql://other/db", "display-color", "blue"));
    when(store.loadCatalogConnectionSnapshot(IDENTIFIER)).thenReturn(changed, commentOnly);
    dispatcher.alterCatalog(IDENTIFIER, CatalogChange.updateComment("new comment"));
    verify(store)
        .reconcileTestResultAfterCatalogChange(
            changed, commentOnly, ConnectionTestType.CATALOG, true);
    verify(store).reconcileCredentialTestResultsAfterCatalogChange(changed, commentOnly, true);
  }

  @Test
  void testRenameEnableDisableAndDropLifecycle() {
    Catalog renamedCatalog = mock(Catalog.class);
    when(renamedCatalog.name()).thenReturn("renamed");
    NameIdentifier renamedIdentifier = NameIdentifier.of("metalake", "renamed");
    CatalogConnectionSnapshot renamed =
        new CatalogConnectionSnapshot(10L, 2L, "renamed", "jdbc-mysql", before.properties());
    when(delegate.alterCatalog(eq(IDENTIFIER), any(CatalogChange[].class)))
        .thenReturn(renamedCatalog);
    when(store.loadCatalogConnectionSnapshot(IDENTIFIER)).thenReturn(before);
    when(store.loadCatalogConnectionSnapshot(renamedIdentifier)).thenReturn(renamed);
    dispatcher.alterCatalog(IDENTIFIER, CatalogChange.rename("renamed"));
    verify(store)
        .reconcileTestResultAfterCatalogChange(before, renamed, ConnectionTestType.CATALOG, true);
    verify(store).reconcileCredentialTestResultsAfterCatalogChange(before, renamed, true);

    CatalogConnectionSnapshot enabled =
        new CatalogConnectionSnapshot(10L, 3L, "renamed", "jdbc-mysql", before.properties());
    CatalogConnectionSnapshot disabled =
        new CatalogConnectionSnapshot(10L, 4L, "renamed", "jdbc-mysql", before.properties());
    when(store.loadCatalogConnectionSnapshot(renamedIdentifier))
        .thenReturn(renamed, enabled, enabled, disabled);
    dispatcher.enableCatalog(renamedIdentifier);
    dispatcher.disableCatalog(renamedIdentifier);
    verify(store)
        .reconcileTestResultAfterCatalogChange(renamed, enabled, ConnectionTestType.CATALOG, true);
    verify(store).reconcileCredentialTestResultsAfterCatalogChange(renamed, enabled, true);
    verify(store)
        .reconcileTestResultAfterCatalogChange(enabled, disabled, ConnectionTestType.CATALOG, true);
    verify(store).reconcileCredentialTestResultsAfterCatalogChange(enabled, disabled, true);

    when(delegate.dropCatalog(renamedIdentifier, true)).thenReturn(true);
    dispatcher.dropCatalog(renamedIdentifier, true);
    verify(delegate).dropCatalog(renamedIdentifier, true);
  }

  @Test
  void testPostMutationStorageFailureDoesNotRollBackCatalogOperation() {
    Catalog alteredCatalog = mock(Catalog.class);
    when(alteredCatalog.name()).thenReturn("catalog");
    CatalogConnectionSnapshot after =
        new CatalogConnectionSnapshot(10L, 2L, "catalog", "jdbc-mysql", before.properties());
    when(delegate.alterCatalog(eq(IDENTIFIER), any(CatalogChange[].class)))
        .thenReturn(alteredCatalog);
    when(store.loadCatalogConnectionSnapshot(IDENTIFIER)).thenReturn(before, after);
    doThrow(new RuntimeException("storage unavailable"))
        .when(store)
        .reconcileTestResultAfterCatalogChange(before, after, ConnectionTestType.CATALOG, true);

    assertSame(
        alteredCatalog,
        dispatcher.alterCatalog(IDENTIFIER, CatalogChange.updateComment("new comment")));
    verify(delegate, times(1)).alterCatalog(eq(IDENTIFIER), any(CatalogChange[].class));
  }

  private void assertPassthrough(RuntimeException failure) throws Exception {
    doThrow(failure).when(delegate).testConnection(IDENTIFIER);
    assertSame(
        failure, assertThrows(failure.getClass(), () -> dispatcher.testConnection(IDENTIFIER)));
  }
}
