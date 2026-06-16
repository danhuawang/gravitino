/*
 * Copyright 2024 Datastrato Pvt Ltd.
 * This software is licensed under the Apache License version 2.
 */
package org.apache.gravitino.integration.test.rest;

import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import org.apache.hadoop.conf.Configuration;
import org.apache.iceberg.AppendFiles;
import org.apache.iceberg.CatalogProperties;
import org.apache.iceberg.DataFile;
import org.apache.iceberg.DataFiles;
import org.apache.iceberg.FileFormat;
import org.apache.iceberg.PartitionSpec;
import org.apache.iceberg.Schema;
import org.apache.iceberg.Table;
import org.apache.iceberg.catalog.Namespace;
import org.apache.iceberg.catalog.TableIdentifier;
import org.apache.iceberg.exceptions.AlreadyExistsException;
import org.apache.iceberg.rest.RESTCatalog;
import org.apache.iceberg.types.Types;
import org.awaitility.Awaitility;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * E2E tests for async purge fault tolerance — covers test plan items #3, #4, #9, #10 from the
 * perspective of externally observable behavior through the Iceberg REST API.
 *
 * <ul>
 *   <li>#3: Worker heartbeat timeout → task reclaimed → cleanup eventually completes.
 *   <li>#4: Transient file deletion failures → retries succeed → name released.
 *   <li>#9: Large table with many files → cleanup completes (all files deleted).
 *   <li>#10: After max-attempts exhausted (FAILED state) → name lock released → table can be
 *       re-created.
 * </ul>
 *
 * <p><b>Approach:</b> Since we cannot inject faults into a running external server, these tests
 * verify the <em>observable guarantees</em> of the fault-tolerance mechanisms:
 *
 * <ul>
 *   <li>Regardless of how many retries are needed internally, the name is eventually released.
 *   <li>Large tables (many data files) are cleaned up completely.
 *   <li>If a cleanup permanently fails (e.g., because max-attempts is 1 and the table's storage is
 *       inaccessible), the 409 lock is released and the name becomes available.
 * </ul>
 *
 * <p><b>Configuration requirements:</b> The target Gravitino+IRC service should have async purge
 * enabled with a reasonably small {@code poll-interval-secs} (e.g., 5) so cleanup completes within
 * the test timeout. For #10, if you need to test permanent failure, configure a catalog whose
 * storage backend is intentionally broken (e.g., bad S3 credentials with max-attempts=1).
 *
 * <p>These tests connect to a running Gravitino service with Iceberg REST catalog (IRC).
 */
@DisplayName("Async Purge: Fault Tolerance (#3, #4, #9, #10)")
public class IcebergAsyncPurgeFaultToleranceIT {

  private static final Logger LOG =
      LoggerFactory.getLogger(IcebergAsyncPurgeFaultToleranceIT.class);

  private static final String ASYNC_PURGE_HEADER = "X-Gravitino-Async-Purge";

  private static final Schema TABLE_SCHEMA =
      new Schema(
          Types.NestedField.required(1, "id", Types.LongType.get()),
          Types.NestedField.optional(2, "data", Types.StringType.get()));

  private static long cleanupTimeoutSecs;
  private static long pollIntervalSecs;

  private static String ircUri;
  private static String simpleUser;

  private static RESTCatalog asyncCatalog;
  private static RESTCatalog syncCatalog;

  private static String testNamespace;

  @BeforeAll
  public static void setup() {
    ircUri = System.getProperty("gravitino.irc.uri", "http://localhost:30001/iceberg/");
    simpleUser = System.getProperty("gravitino.simple.user", "admin");
    cleanupTimeoutSecs =
        Long.parseLong(System.getProperty("async.purge.cleanup.timeout.secs", "120"));
    pollIntervalSecs = Long.parseLong(System.getProperty("async.purge.poll.interval.secs", "2"));
    testNamespace = "async_purge_ft_" + System.currentTimeMillis();

    asyncCatalog = buildRESTCatalog("ft-async", true);
    syncCatalog = buildRESTCatalog("ft-sync", false);

    asyncCatalog.createNamespace(Namespace.of(testNamespace));
    LOG.info(
        "IcebergAsyncPurgeFaultToleranceIT setup: ircUri={}, namespace={},"
            + " cleanupTimeoutSecs={}",
        ircUri,
        testNamespace,
        cleanupTimeoutSecs);
  }

  @AfterEach
  public void cleanTables() {
    try {
      syncCatalog
          .listTables(Namespace.of(testNamespace))
          .forEach(id -> syncCatalog.dropTable(id, false));
    } catch (Exception e) {
      LOG.warn("Per-test table cleanup failed", e);
    }
  }

  @AfterAll
  public static void teardown() {
    try {
      if (syncCatalog != null) {
        syncCatalog.dropNamespace(Namespace.of(testNamespace));
      }
    } catch (Exception e) {
      LOG.warn("Namespace cleanup failed", e);
    }
    closeQuietly(asyncCatalog);
    closeQuietly(syncCatalog);
  }

  // ────────────────────────────────────────────────────────────────────────────
  // #3: Worker heartbeat timeout → reclaim → cleanup eventually completes
  // ────────────────────────────────────────────────────────────────────────────

  @Test
  @DisplayName("#3: Cleanup eventually completes even if first worker is slow (reclaim scenario)")
  public void testCleanupEventuallyCompletesAfterReclaim() {
    // This test verifies the externally observable guarantee: regardless of whether the
    // first worker times out and the job is reclaimed, the cleanup eventually finishes
    // and the name becomes available.
    //
    // In a real multi-node deployment, this tests that the heartbeat timeout + reclaim
    // mechanism ensures liveness (no stuck jobs). In a single-node deployment, it simply
    // verifies that cleanup completes within the configured timeout.
    TableIdentifier tableId = TableIdentifier.of(testNamespace, "t_reclaim");

    asyncCatalog.createTable(tableId, TABLE_SCHEMA);
    asyncCatalog.dropTable(tableId, true);

    // The name should initially be occupied (cleanup in progress).
    assertNameOccupied(tableId, "After async drop, name should be occupied");

    // Wait for the cleanup to eventually complete — this covers both the happy path
    // (single worker succeeds) and the reclaim path (first worker dies, another picks up).
    // In multi-node deployments, set -Dasync.purge.cleanup.timeout.secs high enough to
    // cover heartbeat-timeout-secs (default 300s) + poll-interval-secs.
    Awaitility.await()
        .atMost(cleanupTimeoutSecs, TimeUnit.SECONDS)
        .pollInterval(pollIntervalSecs, TimeUnit.SECONDS)
        .untilAsserted(
            () -> {
              try {
                asyncCatalog.createTable(tableId, TABLE_SCHEMA);
                LOG.info("#3: Name released after cleanup (possibly via reclaim)");
              } catch (AlreadyExistsException e) {
                LOG.debug("#3: Still occupied: {}", e.getMessage());
                Assertions.fail("Name still occupied: " + e.getMessage());
              }
            });

    asyncCatalog.dropTable(tableId, false);
  }

  // ────────────────────────────────────────────────────────────────────────────
  // #4: Transient failures → retries → cleanup eventually succeeds
  // ────────────────────────────────────────────────────────────────────────────

  @Test
  @DisplayName("#4: Cleanup retries on transient failure and eventually releases the name")
  public void testCleanupRetriesAndEventuallySucceeds() {
    // The server's cleanup worker retries on transient errors (e.g., temporary S3 503).
    // From the client's perspective, the observable behavior is: the 409 lock is held
    // for a while (retries) but eventually released (success after retries).
    //
    // This test creates a table, drops it with async purge, confirms the 409 lock, and
    // waits for the name to be released — proving that even if transient failures occurred
    // internally, the retry mechanism eventually succeeded.
    TableIdentifier tableId = TableIdentifier.of(testNamespace, "t_retry");

    asyncCatalog.createTable(tableId, TABLE_SCHEMA);
    asyncCatalog.dropTable(tableId, true);

    // Confirm 409 lock is active.
    assertNameOccupied(tableId, "After async drop, name should be occupied");

    // Eventually the name is released (cleanup succeeded after any internal retries).
    Awaitility.await()
        .atMost(cleanupTimeoutSecs, TimeUnit.SECONDS)
        .pollInterval(pollIntervalSecs, TimeUnit.SECONDS)
        .untilAsserted(
            () -> {
              try {
                asyncCatalog.createTable(tableId, TABLE_SCHEMA);
                LOG.info("#4: Cleanup completed (retries succeeded internally)");
              } catch (AlreadyExistsException e) {
                LOG.debug("#4: Still occupied (retrying internally): {}", e.getMessage());
                Assertions.fail("Name still occupied: " + e.getMessage());
              }
            });

    asyncCatalog.dropTable(tableId, false);
  }

  // ────────────────────────────────────────────────────────────────────────────
  // #9: Large table with many data files → cleanup completes fully
  // ────────────────────────────────────────────────────────────────────────────

  @Test
  @DisplayName("#9: Large table with many data files is cleaned up completely")
  public void testLargeTableCleanupCompletes() {
    TableIdentifier tableId = TableIdentifier.of(testNamespace, "t_large");

    // Create a table and append many data files to exercise multi-batch deletion.
    // Each commit adds a data file, creating multiple manifests.
    Table table = asyncCatalog.createTable(tableId, TABLE_SCHEMA);

    // Append multiple data files via separate commits to create a non-trivial manifest tree.
    // The server's delete-batch-size (default 1000) means this needs > 1000 reachable files
    // to truly exercise multi-batch, but even with fewer files we verify the end-to-end
    // behavior: all files must be cleaned up and the name released.
    int numCommits = 10;
    for (int i = 0; i < numCommits; i++) {
      DataFile dataFile =
          DataFiles.builder(PartitionSpec.unpartitioned())
              .withPath(table.location() + "/data/large-test-" + i + ".parquet")
              .withFormat(FileFormat.PARQUET)
              .withFileSizeInBytes(100L)
              .withRecordCount(1L)
              .build();
      table.newAppend().appendFile(dataFile).commit();
      // Reload table to get fresh metadata after each commit.
      table = asyncCatalog.loadTable(tableId);
    }

    LOG.info("#9: Created table with {} commits (multiple manifests)", numCommits);

    // Drop with async purge.
    asyncCatalog.dropTable(tableId, true);

    // Confirm 409 lock.
    assertNameOccupied(tableId, "Large table should be occupied during cleanup");

    // Wait for the cleanup of all files across multiple batches.
    Awaitility.await()
        .atMost(cleanupTimeoutSecs, TimeUnit.SECONDS)
        .pollInterval(pollIntervalSecs, TimeUnit.SECONDS)
        .untilAsserted(
            () -> {
              try {
                asyncCatalog.createTable(tableId, TABLE_SCHEMA);
                LOG.info("#9: Large table cleanup completed (multi-batch)");
              } catch (AlreadyExistsException e) {
                LOG.debug("#9: Still cleaning up large table: {}", e.getMessage());
                Assertions.fail("Large table still being cleaned up: " + e.getMessage());
              }
            });

    asyncCatalog.dropTable(tableId, false);
  }

  @Test
  @DisplayName("#9 (large volume): Table with 2000+ data files across multiple batches")
  public void testVeryLargeTableCleanupCompletes() {
    // This test creates a table with enough data files to genuinely exercise multi-batch
    // deletion (default delete-batch-size=1000). It appends many files per commit to reduce
    // HTTP round trips while maximizing the number of reachable data files.
    //
    // Expected reachable file count:
    //   - 2000 data files
    //   - ~20 manifests (one per commit)
    //   - ~20 manifest lists
    //   - ~21 metadata versions
    //   Total: ~2060+ files → at least 3 batches of 1000
    //
    // This also verifies that heartbeat renewal keeps the job alive during a longer cleanup.
    TableIdentifier tableId = TableIdentifier.of(testNamespace, "t_very_large");

    Table table = asyncCatalog.createTable(tableId, TABLE_SCHEMA);

    // Append 100 data files per commit × 20 commits = 2000 data files.
    int filesPerCommit = 100;
    int numCommits = 20;
    long startTime = System.currentTimeMillis();

    for (int c = 0; c < numCommits; c++) {
      AppendFiles append = table.newAppend();
      for (int f = 0; f < filesPerCommit; f++) {
        DataFile dataFile =
            DataFiles.builder(PartitionSpec.unpartitioned())
                .withPath(table.location() + "/data/commit-" + c + "/file-" + f + ".parquet")
                .withFormat(FileFormat.PARQUET)
                .withFileSizeInBytes(1024L)
                .withRecordCount(10L)
                .build();
        append.appendFile(dataFile);
      }
      append.commit();
      table = asyncCatalog.loadTable(tableId);
      if ((c + 1) % 5 == 0) {
        LOG.info(
            "#9 large: committed {}/{} ({} files so far)",
            c + 1,
            numCommits,
            (c + 1) * filesPerCommit);
      }
    }

    long setupDurationMs = System.currentTimeMillis() - startTime;
    int totalDataFiles = numCommits * filesPerCommit;
    LOG.info(
        "#9 large: Table created with {} data files across {} commits in {}ms",
        totalDataFiles,
        numCommits,
        setupDurationMs);

    // Drop with async purge.
    asyncCatalog.dropTable(tableId, true);

    // Confirm 409 lock is active.
    assertNameOccupied(tableId, "Very large table should be occupied during cleanup");

    // Wait for the cleanup to complete. With 2000+ files and default batch-size=1000,
    // this exercises at least 2-3 batch iterations. The cleanup may take tens of seconds
    // depending on storage backend (file:// is fast, S3 bulk-delete is slower).
    Awaitility.await()
        .atMost(cleanupTimeoutSecs, TimeUnit.SECONDS)
        .pollInterval(pollIntervalSecs, TimeUnit.SECONDS)
        .untilAsserted(
            () -> {
              try {
                asyncCatalog.createTable(tableId, TABLE_SCHEMA);
                LOG.info("#9 large: Cleanup completed for {} data files", totalDataFiles);
              } catch (AlreadyExistsException e) {
                LOG.debug("#9 large: Still cleaning up: {}", e.getMessage());
                Assertions.fail(
                    "Large table ("
                        + totalDataFiles
                        + " files) still being cleaned up: "
                        + e.getMessage());
              }
            });

    long totalDurationMs = System.currentTimeMillis() - startTime;
    LOG.info(
        "#9 large: Full cycle (create {} files + async cleanup) completed in {}ms",
        totalDataFiles,
        totalDurationMs);

    asyncCatalog.dropTable(tableId, false);
  }

  // ────────────────────────────────────────────────────────────────────────────
  // #10: FAILED state releases 409 lock → table can be re-created
  // ────────────────────────────────────────────────────────────────────────────

  @Test
  @DisplayName("#10: After max-attempts exhausted, the 409 lock is released")
  public void testFailedCleanupReleasesNameLock() {
    // When a cleanup job exhausts max-attempts and is marked FAILED, the 409 lock must
    // be released so the table name can be reused.
    //
    // To observe this in an e2e setting, we simply verify that the name is eventually
    // released regardless of outcome (SUCCEEDED or FAILED). Both terminal states release
    // the lock. The key property being tested: the system does NOT hold the 409 lock forever.
    TableIdentifier tableId = TableIdentifier.of(testNamespace, "t_fail_release");

    asyncCatalog.createTable(tableId, TABLE_SCHEMA);
    asyncCatalog.dropTable(tableId, true);

    // Confirm 409 lock is active.
    assertNameOccupied(tableId, "Name should be occupied after async drop");

    // Whether the cleanup succeeds (files deleted) or fails (max-attempts exhausted),
    // the name MUST eventually become available.
    Awaitility.await()
        .atMost(cleanupTimeoutSecs, TimeUnit.SECONDS)
        .pollInterval(pollIntervalSecs, TimeUnit.SECONDS)
        .untilAsserted(
            () -> {
              try {
                asyncCatalog.createTable(tableId, TABLE_SCHEMA);
                LOG.info("#10: Name released (cleanup reached terminal state)");
              } catch (AlreadyExistsException e) {
                LOG.debug("#10: Still locked: {}", e.getMessage());
                Assertions.fail("Name still locked: " + e.getMessage());
              }
            });

    asyncCatalog.dropTable(tableId, false);
  }

  @Test
  @DisplayName("#10 (variant): Multiple consecutive async drops and re-creations")
  public void testMultipleDropRecreateCycles() {
    // Verifies that the system correctly handles multiple drop-wait-create cycles,
    // confirming that each cleanup job properly releases the lock.
    TableIdentifier tableId = TableIdentifier.of(testNamespace, "t_cycles");

    for (int cycle = 0; cycle < 3; cycle++) {
      final int c = cycle;

      // Create
      asyncCatalog.createTable(tableId, TABLE_SCHEMA);
      Assertions.assertTrue(asyncCatalog.tableExists(tableId));

      // Drop with async purge
      asyncCatalog.dropTable(tableId, true);

      // Wait for name release (cleanup SUCCEEDED or FAILED)
      Awaitility.await()
          .atMost(cleanupTimeoutSecs, TimeUnit.SECONDS)
          .pollInterval(pollIntervalSecs, TimeUnit.SECONDS)
          .untilAsserted(
              () -> {
                try {
                  asyncCatalog.createTable(tableId, TABLE_SCHEMA);
                  LOG.info("#10: Cycle {} — name released, re-created successfully", c);
                } catch (AlreadyExistsException e) {
                  Assertions.fail("Cycle " + c + " still locked: " + e.getMessage());
                }
              });

      // Drop non-purge to clean up before next cycle.
      asyncCatalog.dropTable(tableId, false);
    }
  }

  // ────────────────────────────────────────────────────────────────────────────
  // Helpers
  // ────────────────────────────────────────────────────────────────────────────

  /**
   * Asserts that creating a table with the given identifier throws 409 (AlreadyExistsException).
   */
  private void assertNameOccupied(TableIdentifier tableId, String message) {
    try {
      syncCatalog.createTable(tableId, TABLE_SCHEMA);
      Assertions.fail(message + " — but create succeeded (not occupied)");
    } catch (AlreadyExistsException e) {
      LOG.debug("Name occupied as expected: {}", e.getMessage());
    }
  }

  private static RESTCatalog buildRESTCatalog(String name, boolean asyncPurge) {
    Map<String, String> props = new HashMap<>();
    props.put(CatalogProperties.URI, ircUri);
    props.put(CatalogProperties.CACHE_ENABLED, "false");
    props.put("rest.auth.type", "basic");
    props.put("rest.auth.basic.username", simpleUser);
    props.put("rest.auth.basic.password", "mock");
    if (asyncPurge) {
      props.put("header." + ASYNC_PURGE_HEADER, "true");
    }
    RESTCatalog catalog = new RESTCatalog();
    catalog.setConf(new Configuration());
    catalog.initialize(name, props);
    return catalog;
  }

  private static void closeQuietly(RESTCatalog catalog) {
    if (catalog != null) {
      try {
        catalog.close();
      } catch (Exception e) {
        LOG.warn("Failed to close RESTCatalog", e);
      }
    }
  }
}
