/*
 * Copyright 2026 Datastrato Inc.
 */
package com.datastrato.gravitino.catalog;

import com.datastrato.gravitino.catalog.connection.CatalogConnectionSnapshot;
import com.datastrato.gravitino.catalog.connection.ConnectionPropertyClassifier;
import com.datastrato.gravitino.catalog.connection.ConnectionTestResult;
import com.datastrato.gravitino.catalog.connection.ConnectionTestStore;
import com.datastrato.gravitino.catalog.connection.ConnectionTestType;
import java.time.Clock;
import java.util.Map;
import org.apache.gravitino.Catalog;
import org.apache.gravitino.CatalogChange;
import org.apache.gravitino.NameIdentifier;
import org.apache.gravitino.Namespace;
import org.apache.gravitino.catalog.CatalogDispatcher;
import org.apache.gravitino.exceptions.CatalogAlreadyExistsException;
import org.apache.gravitino.exceptions.CatalogInUseException;
import org.apache.gravitino.exceptions.CatalogNotInUseException;
import org.apache.gravitino.exceptions.ConnectionFailedException;
import org.apache.gravitino.exceptions.NoSuchCatalogException;
import org.apache.gravitino.exceptions.NoSuchMetalakeException;
import org.apache.gravitino.exceptions.NonEmptyEntityException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/** Enterprise Catalog dispatcher that persists existing-Catalog connection test results. */
public class DatastratoCatalogDispatcher implements CatalogDispatcher {
  private static final Logger LOG = LoggerFactory.getLogger(DatastratoCatalogDispatcher.class);
  private static final String SAFE_CONNECTION_FAILURE_MESSAGE = "Failed to connect to the catalog";

  private final CatalogDispatcher delegate;
  private final ConnectionTestStore connectionTestStore;
  private final ConnectionPropertyClassifier propertyClassifier;
  private final Clock clock;

  /**
   * Creates an Enterprise Catalog dispatcher using the system clock.
   *
   * @param delegate The OSS Catalog dispatcher.
   * @param connectionTestStore The persistent connection test store.
   */
  public DatastratoCatalogDispatcher(
      CatalogDispatcher delegate, ConnectionTestStore connectionTestStore) {
    this(delegate, connectionTestStore, new ConnectionPropertyClassifier(), Clock.systemUTC());
  }

  /**
   * Creates an Enterprise Catalog dispatcher with injectable collaborators.
   *
   * @param delegate The OSS Catalog dispatcher.
   * @param connectionTestStore The persistent connection test store.
   * @param propertyClassifier The connection property classifier.
   * @param clock The completion clock.
   */
  public DatastratoCatalogDispatcher(
      CatalogDispatcher delegate,
      ConnectionTestStore connectionTestStore,
      ConnectionPropertyClassifier propertyClassifier,
      Clock clock) {
    this.delegate = delegate;
    this.connectionTestStore = connectionTestStore;
    this.propertyClassifier = propertyClassifier;
    this.clock = clock;
  }

  /** {@inheritDoc} */
  @Override
  public NameIdentifier[] listCatalogs(Namespace namespace) throws NoSuchMetalakeException {
    return delegate.listCatalogs(namespace);
  }

  /** {@inheritDoc} */
  @Override
  public Catalog[] listCatalogsInfo(Namespace namespace) throws NoSuchMetalakeException {
    return delegate.listCatalogsInfo(namespace);
  }

  /** {@inheritDoc} */
  @Override
  public Catalog loadCatalog(NameIdentifier ident) throws NoSuchCatalogException {
    return delegate.loadCatalog(ident);
  }

  /** {@inheritDoc} */
  @Override
  public Catalog createCatalog(
      NameIdentifier ident,
      Catalog.Type type,
      String provider,
      String comment,
      Map<String, String> properties)
      throws NoSuchMetalakeException, CatalogAlreadyExistsException {
    return delegate.createCatalog(ident, type, provider, comment, properties);
  }

  /** {@inheritDoc} */
  @Override
  public Catalog alterCatalog(NameIdentifier ident, CatalogChange... changes)
      throws NoSuchCatalogException, IllegalArgumentException {
    CatalogConnectionSnapshot before = bestEffortSnapshot(ident, "alter");
    Catalog altered = delegate.alterCatalog(ident, changes);
    if (before != null) {
      NameIdentifier currentIdentifier = NameIdentifier.of(ident.namespace(), altered.name());
      CatalogConnectionSnapshot after = bestEffortSnapshot(currentIdentifier, "alter");
      if (after != null) {
        boolean preserve = !propertyClassifier.connectionPropertiesChanged(before, after);
        bestEffortReconcile(before, after, preserve, "alter");
      }
    }
    return altered;
  }

  /** {@inheritDoc} */
  @Override
  public boolean dropCatalog(NameIdentifier ident, boolean force)
      throws NonEmptyEntityException, CatalogInUseException {
    return delegate.dropCatalog(ident, force);
  }

  /** {@inheritDoc} */
  @Override
  public void testConnection(
      NameIdentifier ident,
      Catalog.Type type,
      String provider,
      String comment,
      Map<String, String> properties)
      throws Exception {
    delegate.testConnection(ident, type, provider, comment, properties);
  }

  /** {@inheritDoc} */
  @Override
  public void testConnection(NameIdentifier ident) throws Exception {
    CatalogConnectionSnapshot before = connectionTestStore.loadCatalogConnectionSnapshot(ident);
    try {
      delegate.testConnection(ident);
      persistCompletedProbe(before, ConnectionTestResult.Status.PASSED, null, clock.millis());
    } catch (ConnectionFailedException e) {
      persistCompletedProbe(
          before,
          ConnectionTestResult.Status.FAILED,
          SAFE_CONNECTION_FAILURE_MESSAGE,
          clock.millis());
      throw e;
    }
  }

  /**
   * {@inheritDoc}
   *
   * <p>Proposed changes are tested against a temporary effective configuration and are never
   * persisted, so this does not touch the stored connection test result for the existing Catalog.
   */
  @Override
  public void testConnection(NameIdentifier ident, CatalogChange... changes) throws Exception {
    delegate.testConnection(ident, changes);
  }

  /** {@inheritDoc} */
  @Override
  public void enableCatalog(NameIdentifier ident)
      throws NoSuchCatalogException, CatalogNotInUseException {
    CatalogConnectionSnapshot before = bestEffortSnapshot(ident, "enable");
    delegate.enableCatalog(ident);
    reconcilePreservingResult(ident, before, "enable");
  }

  /** {@inheritDoc} */
  @Override
  public void disableCatalog(NameIdentifier ident) throws NoSuchCatalogException {
    CatalogConnectionSnapshot before = bestEffortSnapshot(ident, "disable");
    delegate.disableCatalog(ident);
    reconcilePreservingResult(ident, before, "disable");
  }

  /**
   * Persists a completed Catalog connection probe against its pre-probe snapshot.
   *
   * <p>Unlike post-mutation bookkeeping, a persistence failure is propagated because a completed
   * connection-test response promises that the result can be loaded by the overview API.
   *
   * @param before Catalog snapshot captured before the probe
   * @param status completed probe status
   * @param errorMessage safe failure message, or {@code null} for a passed probe
   * @param completedAt probe completion time in milliseconds
   */
  private void persistCompletedProbe(
      CatalogConnectionSnapshot before,
      ConnectionTestResult.Status status,
      String errorMessage,
      long completedAt) {
    if (before == null) {
      return;
    }
    connectionTestStore.recordTestResult(
        before, ConnectionTestType.CATALOG, status, completedAt, errorMessage);
  }

  /**
   * Reconciles a successful Catalog mutation while preserving its existing connection-test result.
   *
   * <p>Loading the post-mutation snapshot and reconciling the stored result are both best-effort so
   * that bookkeeping failures do not turn an already successful Catalog mutation into a request
   * failure.
   *
   * @param ident identifier after the Catalog mutation
   * @param before Catalog snapshot captured before the mutation
   * @param operation operation name used in diagnostic logging
   */
  private void reconcilePreservingResult(
      NameIdentifier ident, CatalogConnectionSnapshot before, String operation) {
    if (before == null) {
      return;
    }
    CatalogConnectionSnapshot after = bestEffortSnapshot(ident, operation);
    if (after != null) {
      bestEffortReconcile(before, after, true, operation);
    }
  }

  /**
   * Loads a Catalog snapshot for mutation bookkeeping without failing the primary operation.
   *
   * @param ident Catalog identifier to load
   * @param operation operation name used in diagnostic logging
   * @return the snapshot, or {@code null} when persistence cannot be inspected
   */
  private CatalogConnectionSnapshot bestEffortSnapshot(NameIdentifier ident, String operation) {
    try {
      return connectionTestStore.loadCatalogConnectionSnapshot(ident);
    } catch (RuntimeException e) {
      LOG.warn(
          "Unable to inspect Catalog {} connection test state around {} operation",
          ident,
          operation,
          e);
      return null;
    }
  }

  /**
   * Reconciles a stored connection-test result after a successful Catalog mutation.
   *
   * <p>Persistence failures are logged and suppressed because the primary Catalog operation has
   * already completed.
   *
   * @param before Catalog snapshot captured before the mutation
   * @param after Catalog snapshot captured after the mutation
   * @param preserve whether the existing result should be moved to the new Catalog version
   * @param operation operation name used in diagnostic logging
   */
  private void bestEffortReconcile(
      CatalogConnectionSnapshot before,
      CatalogConnectionSnapshot after,
      boolean preserve,
      String operation) {
    try {
      connectionTestStore.reconcileTestResultAfterCatalogChange(
          before, after, ConnectionTestType.CATALOG, preserve);
      connectionTestStore.reconcileCredentialTestResultsAfterCatalogChange(before, after, preserve);
    } catch (RuntimeException e) {
      LOG.warn(
          "Catalog {} {} operation succeeded, but its connection test result could not be reconciled",
          after.catalogId(),
          operation,
          e);
    }
  }
}
