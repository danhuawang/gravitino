/*
 * Copyright 2026 Datastrato Inc.
 */
package com.datastrato.gravitino.catalog.connection;

import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import javax.annotation.Nullable;
import org.apache.gravitino.NameIdentifier;

/** Storage contract used by the Enterprise Catalog dispatcher and connection overview API. */
public interface ConnectionTestStore {

  /**
   * Loads an immutable snapshot of the Catalog state used to bind a connection test result to the
   * observed Catalog configuration.
   *
   * @param identifier The Catalog identifier.
   * @return The current connection snapshot, or {@code null} if the Catalog is absent.
   */
  @Nullable
  CatalogConnectionSnapshot loadCatalogConnectionSnapshot(NameIdentifier identifier);

  /**
   * Records a completed test result for Catalog properties loaded independently from the test
   * store.
   *
   * @param testedCatalog The Catalog identifier used for the test.
   * @param testedProvider The Catalog provider used for the test.
   * @param testedProperties The persisted Catalog properties used for the test.
   * @param testType The Catalog or credential connection test type.
   * @param status The completed result.
   * @param lastTestedAt The completion time in epoch milliseconds.
   * @param errorMessage A safe non-empty message for failures, otherwise {@code null}.
   * @return {@code true} when the tested configuration is still current and was persisted.
   */
  default boolean recordTestResult(
      NameIdentifier testedCatalog,
      String testedProvider,
      Map<String, String> testedProperties,
      String testType,
      ConnectionTestResult.Status status,
      long lastTestedAt,
      @Nullable String errorMessage) {
    CatalogConnectionSnapshot current = loadCatalogConnectionSnapshot(testedCatalog);
    if (current == null
        || !Objects.equals(current.provider(), testedProvider)
        || !Objects.equals(current.properties(), testedProperties)) {
      return false;
    }
    return recordTestResult(current, testType, status, lastTestedAt, errorMessage);
  }

  /**
   * Records a completed connection test result only while the Catalog still matches the snapshot
   * captured before the test.
   *
   * @param testedSnapshot The snapshot captured before the connection test.
   * @param testType The Catalog or credential connection test type.
   * @param status The completed result.
   * @param lastTestedAt The completion time in epoch milliseconds.
   * @param errorMessage A safe non-empty message for failures, otherwise {@code null}.
   * @return {@code true} when the result was current and persisted; {@code false} when stale.
   */
  boolean recordTestResult(
      CatalogConnectionSnapshot testedSnapshot,
      String testType,
      ConnectionTestResult.Status status,
      long lastTestedAt,
      @Nullable String errorMessage);

  /**
   * Loads a connection test result only when its recorded Catalog version matches the current
   * Catalog version.
   *
   * @param identifier The Catalog identifier.
   * @param testType The Catalog or credential connection test type.
   * @return The valid result, or empty when absent or stale.
   */
  Optional<ConnectionTestResult> getValidTestResult(NameIdentifier identifier, String testType);

  /**
   * Reconciles a connection test result after a successful Catalog metadata change. The result is
   * advanced to the new Catalog version when {@code preserve} is true and invalidated otherwise.
   *
   * @param before The Catalog snapshot before the change.
   * @param after The Catalog snapshot after the change.
   * @param testType The Catalog or credential connection test type.
   * @param preserve Whether to carry the result to the new version.
   */
  void reconcileTestResultAfterCatalogChange(
      CatalogConnectionSnapshot before,
      CatalogConnectionSnapshot after,
      String testType,
      boolean preserve);

  /**
   * Reconciles every credential provider result after a successful Catalog metadata change. Results
   * are advanced to the new Catalog version when {@code preserve} is true and invalidated
   * otherwise.
   *
   * @param before The Catalog snapshot before the change.
   * @param after The Catalog snapshot after the change.
   * @param preserve Whether to carry the results to the new version.
   */
  void reconcileCredentialTestResultsAfterCatalogChange(
      CatalogConnectionSnapshot before, CatalogConnectionSnapshot after, boolean preserve);

  /**
   * Deletes persisted results whose Catalog no longer exists as a live relational entity.
   *
   * @param limit The maximum number of orphaned Catalog IDs to process.
   * @return The number of deleted connection test result rows.
   */
  int deleteOrphanedTestResults(int limit);
}
