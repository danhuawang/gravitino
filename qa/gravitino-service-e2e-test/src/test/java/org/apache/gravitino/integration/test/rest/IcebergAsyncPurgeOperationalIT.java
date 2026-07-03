/*
 * Copyright 2024 Datastrato Pvt Ltd.
 * This software is licensed under the Apache License version 2.
 */
package org.apache.gravitino.integration.test.rest;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;
import org.apache.hadoop.conf.Configuration;
import org.apache.iceberg.CatalogProperties;
import org.apache.iceberg.Schema;
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
 * E2E tests for async purge operational scenarios — covers test plan items #11, #12, #13, #14, #15:
 *
 * <ul>
 *   <li>#11: Retention pruning of terminal rows does not affect in-progress or future tasks.
 *   <li>#12: Server remains stable and responsive with default poll-interval configuration.
 *   <li>#13: Single-threaded worker pools can process multiple cleanup jobs without deadlock.
 *   <li>#14: Cleanup tasks persist across time (simulating service restart behavior).
 *   <li>#15: LIST tables is consistent during async deletion — dropped table disappears
 *       immediately.
 * </ul>
 *
 * <p>These tests verify externally observable behavior through the Iceberg REST API. Some scenarios
 * (#11 pruning, #12 extreme configs, #14 restart) cannot be fully tested from an external client
 * alone but their observable guarantees (no leaked locks, eventual completion, LIST consistency)
 * are verified.
 */
@DisplayName("Async Purge: Configuration & Observability (#11, #12, #13, #14, #15)")
public class IcebergAsyncPurgeOperationalIT {

  private static final Logger LOG = LoggerFactory.getLogger(IcebergAsyncPurgeOperationalIT.class);

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
    testNamespace = "async_purge_ops_" + System.currentTimeMillis();

    asyncCatalog = buildRESTCatalog("ops-async", true);
    syncCatalog = buildRESTCatalog("ops-sync", false);

    asyncCatalog.createNamespace(Namespace.of(testNamespace));
    LOG.info(
        "IcebergAsyncPurgeOperationalIT setup: ircUri={}, namespace={}, cleanupTimeoutSecs={}",
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
  // #11: Retention pruning does not affect in-progress or future tasks
  // ────────────────────────────────────────────────────────────────────────────

  @Test
  @DisplayName("#11: Completed cleanup does not leave residual state blocking future operations")
  public void testCompletedCleanupDoesNotBlockFutureOperations() {
    // Retention pruning removes SUCCEEDED/FAILED rows after retention-hours (default 720h).
    // This test verifies the observable guarantee: after cleanup completes, the same table
    // name can be used for a brand new create → drop → create cycle without any residual
    // state from the first cleanup job interfering.
    TableIdentifier tableId = TableIdentifier.of(testNamespace, "t_retention");

    // Cycle 1: create → async drop → wait for cleanup to complete
    asyncCatalog.createTable(tableId, TABLE_SCHEMA);
    asyncCatalog.dropTable(tableId, true);
    waitForNameRelease(tableId, "#11 cycle 1");

    // Cycle 2: same name — must succeed without interference from the old SUCCEEDED row.
    asyncCatalog.createTable(tableId, TABLE_SCHEMA);
    Assertions.assertTrue(asyncCatalog.tableExists(tableId), "Cycle 2 create should succeed");
    asyncCatalog.dropTable(tableId, true);
    waitForNameRelease(tableId, "#11 cycle 2");

    // Cycle 3: third time — proves no accumulation of stale rows causes issues.
    asyncCatalog.createTable(tableId, TABLE_SCHEMA);
    Assertions.assertTrue(asyncCatalog.tableExists(tableId), "Cycle 3 create should succeed");
    asyncCatalog.dropTable(tableId, false);
    LOG.info("#11: Three create-drop cycles on same name completed — no residual state");
  }

  @Test
  @DisplayName("#11 (variant): In-progress cleanup is not affected by other completed cleanups")
  public void testInProgressCleanupNotAffectedByCompletedOnes() {
    // Verifies that a cleanup job in progress on table A is not disturbed by the
    // completion (and eventual pruning) of a cleanup job on table B.
    TableIdentifier tableA = TableIdentifier.of(testNamespace, "t_retention_a");
    TableIdentifier tableB = TableIdentifier.of(testNamespace, "t_retention_b");

    // Drop both tables with async purge.
    asyncCatalog.createTable(tableA, TABLE_SCHEMA);
    asyncCatalog.createTable(tableB, TABLE_SCHEMA);
    asyncCatalog.dropTable(tableA, true);
    asyncCatalog.dropTable(tableB, true);

    // Both names should be occupied.
    assertNameOccupied(tableA, "Table A should be occupied during cleanup");
    assertNameOccupied(tableB, "Table B should be occupied during cleanup");

    // Wait for both to complete.
    waitForNameRelease(tableA, "#11 table A");
    waitForNameRelease(tableB, "#11 table B");

    // Both can be re-created — one completing didn't corrupt the other.
    asyncCatalog.createTable(tableA, TABLE_SCHEMA);
    asyncCatalog.createTable(tableB, TABLE_SCHEMA);
    Assertions.assertTrue(asyncCatalog.tableExists(tableA));
    Assertions.assertTrue(asyncCatalog.tableExists(tableB));
    asyncCatalog.dropTable(tableA, false);
    asyncCatalog.dropTable(tableB, false);
    LOG.info("#11: Concurrent cleanup jobs did not interfere with each other");
  }

  // ────────────────────────────────────────────────────────────────────────────
  // #12: Server stability with default poll-interval
  // ────────────────────────────────────────────────────────────────────────────

  @Test
  @DisplayName("#12: Server remains responsive under normal async purge load")
  public void testServerResponsiveUnderAsyncPurgeLoad() {
    // Verifies that the background cleanup workers don't starve the server's request
    // handling threads. Creates and drops several tables in rapid succession, then verifies
    // the server remains responsive (can still serve LIST, create, etc.).
    int numTables = 5;
    TableIdentifier[] tables = new TableIdentifier[numTables];

    // Create and async-drop multiple tables rapidly.
    for (int i = 0; i < numTables; i++) {
      tables[i] = TableIdentifier.of(testNamespace, "t_load_" + i);
      asyncCatalog.createTable(tables[i], TABLE_SCHEMA);
    }
    for (int i = 0; i < numTables; i++) {
      asyncCatalog.dropTable(tables[i], true);
    }

    // The server should still be responsive — LIST should work immediately.
    List<TableIdentifier> listed = asyncCatalog.listTables(Namespace.of(testNamespace));
    LOG.info("#12: LIST returned {} tables after {} rapid async drops", listed.size(), numTables);
    // None of the dropped tables should appear in LIST.
    for (int i = 0; i < numTables; i++) {
      Assertions.assertFalse(
          listed.contains(tables[i]), "Dropped table " + tables[i] + " should not appear in LIST");
    }

    // Creating a new unrelated table should succeed immediately (server not starved).
    TableIdentifier unrelated = TableIdentifier.of(testNamespace, "t_load_unrelated");
    Assertions.assertDoesNotThrow(
        () -> asyncCatalog.createTable(unrelated, TABLE_SCHEMA),
        "Server should remain responsive — creating an unrelated table should succeed");
    asyncCatalog.dropTable(unrelated, false);

    // Wait for all cleanup jobs to finish.
    for (int i = 0; i < numTables; i++) {
      waitForNameRelease(tables[i], "#12 table " + i);
    }
    LOG.info(
        "#12: All {} cleanup jobs completed, server remained responsive throughout", numTables);
  }

  // ────────────────────────────────────────────────────────────────────────────
  // #13: Multiple cleanup jobs processed without deadlock or starvation
  // ────────────────────────────────────────────────────────────────────────────

  @Test
  @DisplayName("#13: Multiple concurrent cleanup jobs all complete (no deadlock/starvation)")
  public void testMultipleConcurrentCleanupJobsAllComplete() {
    // Exercises the worker pool (which may be single-threaded if configured with
    // worker-threads=1) by enqueuing several cleanup jobs and verifying all complete.
    int numJobs = 5;
    TableIdentifier[] tables = new TableIdentifier[numJobs];

    for (int i = 0; i < numJobs; i++) {
      tables[i] = TableIdentifier.of(testNamespace, "t_concurrent_" + i);
      asyncCatalog.createTable(tables[i], TABLE_SCHEMA);
    }

    // Drop all with async purge — creates numJobs cleanup jobs.
    for (int i = 0; i < numJobs; i++) {
      asyncCatalog.dropTable(tables[i], true);
    }

    // All names should initially be occupied.
    for (int i = 0; i < numJobs; i++) {
      assertNameOccupied(tables[i], "Table " + i + " should be occupied");
    }

    // ALL jobs must eventually complete — if the worker pool deadlocks or starves,
    // this will time out.
    for (int i = 0; i < numJobs; i++) {
      final int idx = i;
      Awaitility.await()
          .atMost(cleanupTimeoutSecs, TimeUnit.SECONDS)
          .pollInterval(pollIntervalSecs, TimeUnit.SECONDS)
          .untilAsserted(
              () -> {
                try {
                  asyncCatalog.createTable(tables[idx], TABLE_SCHEMA);
                  asyncCatalog.dropTable(tables[idx], false);
                } catch (AlreadyExistsException e) {
                  Assertions.fail("Job " + idx + " still running: " + e.getMessage());
                }
              });
      LOG.info("#13: Cleanup job {} of {} completed", i + 1, numJobs);
    }
    LOG.info("#13: All {} concurrent cleanup jobs completed without deadlock", numJobs);
  }

  // ────────────────────────────────────────────────────────────────────────────
  // #14: Tasks persist and complete across time (service restart observable behavior)
  // ────────────────────────────────────────────────────────────────────────────

  @Test
  @DisplayName("#14: Cleanup task persists and completes (observable restart guarantee)")
  public void testCleanupTaskPersistsAndCompletes() {
    // The cleanup job is stored in the shared relational backend. If the service restarts,
    // a new worker claims it. From the client's perspective, this means: even if there's
    // a delay (covering poll-interval + heartbeat-timeout for reclaim), the job WILL complete.
    //
    // This test verifies the observable guarantee by dropping a table, waiting briefly (to
    // simulate a window where a restart could have happened), and then confirming the
    // cleanup eventually completes.
    TableIdentifier tableId = TableIdentifier.of(testNamespace, "t_persist");

    asyncCatalog.createTable(tableId, TABLE_SCHEMA);
    asyncCatalog.dropTable(tableId, true);

    // Confirm 409 lock is active immediately after drop.
    assertNameOccupied(tableId, "Name should be occupied right after async drop");

    // Introduce a deliberate delay to simulate the window during which a service restart
    // could occur. The job state persists in the database, so after the "restart" (or just
    // after this delay in reality), a worker picks it up.
    try {
      long delayMs =
          Long.parseLong(System.getProperty("async.purge.restart.delay.secs", "5")) * 1000L;
      LOG.info("#14: Waiting {}ms to simulate restart window...", delayMs);
      Thread.sleep(delayMs);
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
    }

    // The cleanup should still complete — the job survived in the DB.
    Awaitility.await()
        .atMost(cleanupTimeoutSecs, TimeUnit.SECONDS)
        .pollInterval(pollIntervalSecs, TimeUnit.SECONDS)
        .untilAsserted(
            () -> {
              try {
                asyncCatalog.createTable(tableId, TABLE_SCHEMA);
                LOG.info("#14: Name released — job completed after simulated delay");
              } catch (AlreadyExistsException e) {
                LOG.debug("#14: Still occupied: {}", e.getMessage());
                Assertions.fail("Name still occupied: " + e.getMessage());
              }
            });

    asyncCatalog.dropTable(tableId, false);
  }

  // ────────────────────────────────────────────────────────────────────────────
  // #15: LIST tables consistency during async deletion
  // ────────────────────────────────────────────────────────────────────────────

  @Test
  @DisplayName("#15: Dropped table disappears from LIST immediately after async drop")
  public void testDroppedTableDisappearsFromListImmediately() {
    TableIdentifier tableId = TableIdentifier.of(testNamespace, "t_list_consistency");

    asyncCatalog.createTable(tableId, TABLE_SCHEMA);

    // Confirm table is in LIST before drop.
    List<TableIdentifier> beforeDrop = asyncCatalog.listTables(Namespace.of(testNamespace));
    Assertions.assertTrue(beforeDrop.contains(tableId), "Table should appear in LIST before drop");

    // Drop with async purge.
    asyncCatalog.dropTable(tableId, true);

    // Immediately after drop, the table must NOT appear in LIST.
    List<TableIdentifier> afterDrop = asyncCatalog.listTables(Namespace.of(testNamespace));
    Assertions.assertFalse(
        afterDrop.contains(tableId),
        "Table must disappear from LIST immediately after async drop, but it was still present");
    LOG.info("#15: Table correctly absent from LIST immediately after async drop");

    // tableExists should also return false.
    Assertions.assertFalse(
        asyncCatalog.tableExists(tableId),
        "tableExists() must return false immediately after async drop");

    // Wait for cleanup to complete so the test doesn't leave lingering state.
    waitForNameRelease(tableId, "#15");
  }

  @Test
  @DisplayName("#15 (variant): LIST consistency with multiple tables, only dropped ones disappear")
  public void testListConsistencyWithMultipleTables() {
    TableIdentifier keepTable = TableIdentifier.of(testNamespace, "t_list_keep");
    TableIdentifier dropTable = TableIdentifier.of(testNamespace, "t_list_drop");

    asyncCatalog.createTable(keepTable, TABLE_SCHEMA);
    asyncCatalog.createTable(dropTable, TABLE_SCHEMA);

    // Both in LIST.
    List<TableIdentifier> before = asyncCatalog.listTables(Namespace.of(testNamespace));
    Assertions.assertTrue(before.contains(keepTable), "Keep-table should be in LIST");
    Assertions.assertTrue(before.contains(dropTable), "Drop-table should be in LIST");

    // Async drop only one.
    asyncCatalog.dropTable(dropTable, true);

    // LIST should still contain keepTable, but NOT dropTable.
    List<TableIdentifier> after = asyncCatalog.listTables(Namespace.of(testNamespace));
    Assertions.assertTrue(
        after.contains(keepTable), "Keep-table must remain in LIST after sibling drop");
    Assertions.assertFalse(
        after.contains(dropTable),
        "Drop-table must disappear from LIST immediately after async drop");

    // The kept table is still fully functional.
    Assertions.assertTrue(asyncCatalog.tableExists(keepTable));

    // Clean up.
    asyncCatalog.dropTable(keepTable, false);
    waitForNameRelease(dropTable, "#15 variant");
    LOG.info("#15: LIST correctly shows only non-dropped tables");
  }

  @Test
  @DisplayName(
      "#15 (variant): Repeated LIST during cleanup is consistently empty for dropped table")
  public void testRepeatedListDuringCleanupIsConsistent() {
    TableIdentifier tableId = TableIdentifier.of(testNamespace, "t_list_repeated");

    asyncCatalog.createTable(tableId, TABLE_SCHEMA);
    asyncCatalog.dropTable(tableId, true);

    // Poll LIST multiple times during the cleanup window — the table must NEVER re-appear.
    for (int i = 0; i < 5; i++) {
      List<TableIdentifier> tables = asyncCatalog.listTables(Namespace.of(testNamespace));
      List<String> tableNames =
          tables.stream().map(TableIdentifier::name).collect(Collectors.toList());
      Assertions.assertFalse(
          tableNames.contains("t_list_repeated"),
          "Poll " + i + ": dropped table must not re-appear in LIST during cleanup");
      try {
        Thread.sleep(500);
      } catch (InterruptedException e) {
        Thread.currentThread().interrupt();
        break;
      }
    }
    LOG.info("#15: Dropped table never re-appeared in LIST across 5 polls during cleanup");

    waitForNameRelease(tableId, "#15 repeated");
  }

  // ────────────────────────────────────────────────────────────────────────────
  // Helpers
  // ────────────────────────────────────────────────────────────────────────────

  private void assertNameOccupied(TableIdentifier tableId, String message) {
    try {
      syncCatalog.createTable(tableId, TABLE_SCHEMA);
      Assertions.fail(message + " — but create succeeded (name not occupied)");
    } catch (AlreadyExistsException e) {
      LOG.debug("Name occupied as expected: {}", e.getMessage());
    }
  }

  private void waitForNameRelease(TableIdentifier tableId, String label) {
    Awaitility.await()
        .atMost(cleanupTimeoutSecs, TimeUnit.SECONDS)
        .pollInterval(pollIntervalSecs, TimeUnit.SECONDS)
        .untilAsserted(
            () -> {
              try {
                syncCatalog.createTable(tableId, TABLE_SCHEMA);
                syncCatalog.dropTable(tableId, false);
              } catch (AlreadyExistsException e) {
                LOG.debug("{}: name still occupied: {}", label, e.getMessage());
                Assertions.fail(label + ": name still occupied: " + e.getMessage());
              }
            });
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
