/*
 * Copyright 2024 Datastrato Pvt Ltd.
 * This software is licensed under the Apache License version 2.
 */
package org.apache.gravitino.integration.test.rest;

import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.TimeUnit;
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
 * E2E test for async table purge — Test Case #1 from the async-purge-test-plan.
 *
 * <p>Verifies that creating (or registering) a table with the same name immediately after an async
 * drop returns {@code 409 Conflict} (manifested as {@link AlreadyExistsException}) until the
 * background file cleanup finishes. Once cleanup completes, the name is released and a new table
 * with the same identifier can be created successfully.
 *
 * <p>This test connects to a running Gravitino service with Iceberg REST catalog (IRC) configured
 * with async purge enabled. It uses a {@link RESTCatalog} client that sends the {@code
 * X-Gravitino-Async-Purge: true} header on every request.
 *
 * <p><b>Multi-node considerations:</b> The cleanup job state lives in a shared relational backend
 * table, so 409 enforcement is correct regardless of which node handles a given request. However,
 * in a multi-node deployment the cleanup may take longer (e.g., heartbeat timeout reclaim after a
 * worker failure). Use the system property {@code async.purge.cleanup.timeout.secs} to raise the
 * maximum wait time when running against multi-node clusters.
 */
@DisplayName("Async Purge: Create after drop returns 409 until cleanup completes")
public class IcebergAsyncPurgeConflictIT {

  private static final Logger LOG = LoggerFactory.getLogger(IcebergAsyncPurgeConflictIT.class);

  /** Header that opts a drop purge request into asynchronous file cleanup. */
  private static final String ASYNC_PURGE_HEADER = "X-Gravitino-Async-Purge";

  private static final Schema TABLE_SCHEMA =
      new Schema(
          Types.NestedField.required(1, "id", Types.LongType.get()),
          Types.NestedField.optional(2, "data", Types.StringType.get()));

  /**
   * Maximum seconds to wait for the background cleanup to finish and release the table name. In a
   * single-node deployment, 120 s is more than enough. In multi-node deployments where a worker may
   * need to be reclaimed after heartbeat timeout (default 300 s), set the system property {@code
   * async.purge.cleanup.timeout.secs} to a larger value (e.g., 600).
   */
  private static long cleanupTimeoutSecs;

  /**
   * Polling interval in seconds when waiting for cleanup completion. A shorter interval gives
   * faster feedback; a longer one reduces load on the shared backend. Default 2 s works for most
   * setups.
   */
  private static long pollIntervalSecs;

  private static String ircUri;
  private static String simpleUser;

  /** RESTCatalog client that sends the async purge header on every request. */
  private static RESTCatalog asyncCatalog;

  /** RESTCatalog client without the async purge header (standard synchronous behavior). */
  private static RESTCatalog syncCatalog;

  private static String testNamespace;

  @BeforeAll
  public static void setup() {
    ircUri = System.getProperty("gravitino.irc.uri", "http://localhost:30001/iceberg/");
    simpleUser = System.getProperty("gravitino.simple.user", "admin");
    cleanupTimeoutSecs =
        Long.parseLong(System.getProperty("async.purge.cleanup.timeout.secs", "120"));
    pollIntervalSecs = Long.parseLong(System.getProperty("async.purge.poll.interval.secs", "2"));
    testNamespace = "async_purge_conflict_" + System.currentTimeMillis();

    asyncCatalog = buildRESTCatalog("async-purge-client", true);
    syncCatalog = buildRESTCatalog("sync-client", false);

    // Create the test namespace.
    asyncCatalog.createNamespace(Namespace.of(testNamespace));
    LOG.info(
        "IcebergAsyncPurgeConflictIT setup complete: ircUri={}, namespace={},"
            + " cleanupTimeoutSecs={}, pollIntervalSecs={}",
        ircUri,
        testNamespace,
        cleanupTimeoutSecs,
        pollIntervalSecs);
  }

  @AfterEach
  public void cleanTables() {
    try {
      asyncCatalog
          .listTables(Namespace.of(testNamespace))
          .forEach(id -> asyncCatalog.dropTable(id, false));
    } catch (Exception e) {
      LOG.warn("Per-test table cleanup failed", e);
    }
  }

  @AfterAll
  public static void teardown() {
    try {
      if (asyncCatalog != null) {
        asyncCatalog.dropNamespace(Namespace.of(testNamespace));
      }
    } catch (Exception e) {
      LOG.warn("Namespace cleanup failed", e);
    }
    closeQuietly(asyncCatalog);
    closeQuietly(syncCatalog);
  }

  // ────────────────────────────────────────────────────────────────────────────
  // Test #1: createTable immediately after async drop returns 409
  // ────────────────────────────────────────────────────────────────────────────

  @Test
  @DisplayName("createTable with same name during async purge should return 409 Conflict")
  public void testCreateTableBlockedDuringAsyncPurge() {
    TableIdentifier tableId = TableIdentifier.of(testNamespace, "t_conflict_create");

    // Step 1: Create and populate a table so there are files to clean up.
    asyncCatalog.createTable(tableId, TABLE_SCHEMA);
    Assertions.assertTrue(asyncCatalog.tableExists(tableId), "Table should exist after creation");

    // Step 2: Drop the table with purge using the async-purge client.
    // The async purge header causes the server to enqueue a background cleanup job and return
    // immediately. The table is gone from LIST but the name is "occupied" by the cleanup job.
    boolean dropped = asyncCatalog.dropTable(tableId, true);
    Assertions.assertTrue(dropped, "dropTable should return true");

    // Step 3: Immediately try to create a table with the same name.
    // This should fail with AlreadyExistsException containing "being purged".
    AlreadyExistsException conflict =
        Assertions.assertThrows(
            AlreadyExistsException.class,
            () -> asyncCatalog.createTable(tableId, TABLE_SCHEMA),
            "Creating a table with the same name during async purge should throw 409");
    LOG.info("Got expected conflict: {}", conflict.getMessage());
    Assertions.assertTrue(
        conflict.getMessage().contains("being purged")
            || conflict.getMessage().contains("purge")
            || conflict.getMessage().contains("cleanup"),
        "Error message should indicate purge in progress, got: " + conflict.getMessage());

    // Step 4: The table should not be visible in LIST.
    Assertions.assertFalse(
        asyncCatalog.tableExists(tableId),
        "Table should not be visible in LIST during async purge");

    // Step 5: Eventually the background cleanup finishes and the name becomes available.
    Awaitility.await()
        .atMost(cleanupTimeoutSecs, TimeUnit.SECONDS)
        .pollInterval(pollIntervalSecs, TimeUnit.SECONDS)
        .untilAsserted(
            () -> {
              // Attempting to create should succeed once cleanup releases the name.
              try {
                asyncCatalog.createTable(tableId, TABLE_SCHEMA);
                LOG.info("Table re-creation succeeded — cleanup has completed");
              } catch (AlreadyExistsException e) {
                LOG.debug(
                    "Name still occupied (waiting for cleanup worker to finish): {}",
                    e.getMessage());
                Assertions.fail("Name still occupied by cleanup: " + e.getMessage());
              }
            });

    // Verify the newly created table is functional.
    Assertions.assertTrue(asyncCatalog.tableExists(tableId));

    // Cleanup.
    asyncCatalog.dropTable(tableId, false);
  }

  @Test
  @DisplayName(
      "createTable with same name via sync client during async purge should also return 409")
  public void testCreateTableViaSyncClientBlockedDuringAsyncPurge() {
    TableIdentifier tableId = TableIdentifier.of(testNamespace, "t_conflict_sync");

    // Create and drop with async purge.
    asyncCatalog.createTable(tableId, TABLE_SCHEMA);
    asyncCatalog.dropTable(tableId, true);

    // Even a sync client (no async header) should be blocked from creating the same name
    // because the 409 check is on the create path, not the drop path.
    AlreadyExistsException conflict =
        Assertions.assertThrows(
            AlreadyExistsException.class,
            () -> syncCatalog.createTable(tableId, TABLE_SCHEMA),
            "Sync client should also get 409 when name is occupied by async cleanup");
    LOG.info("Sync client got expected conflict: {}", conflict.getMessage());
    Assertions.assertTrue(
        conflict.getMessage().contains("being purged")
            || conflict.getMessage().contains("purge")
            || conflict.getMessage().contains("cleanup"),
        "Error message should indicate purge in progress, got: " + conflict.getMessage());

    // Wait for cleanup to finish.
    Awaitility.await()
        .atMost(cleanupTimeoutSecs, TimeUnit.SECONDS)
        .pollInterval(pollIntervalSecs, TimeUnit.SECONDS)
        .untilAsserted(
            () -> {
              try {
                syncCatalog.createTable(tableId, TABLE_SCHEMA);
              } catch (AlreadyExistsException e) {
                LOG.debug("Name still occupied (sync client): {}", e.getMessage());
                Assertions.fail("Name still occupied: " + e.getMessage());
              }
            });

    syncCatalog.dropTable(tableId, false);
  }

  @Test
  @DisplayName("Multiple rapid drop-create cycles with async purge")
  public void testRapidDropCreateCyclesDuringAsyncPurge() {
    TableIdentifier tableId = TableIdentifier.of(testNamespace, "t_conflict_rapid");

    // Create the initial table.
    asyncCatalog.createTable(tableId, TABLE_SCHEMA);

    // Drop with async purge.
    asyncCatalog.dropTable(tableId, true);

    // Rapid re-create attempts should all fail with 409 while cleanup is in progress.
    for (int i = 0; i < 3; i++) {
      final int attempt = i;
      try {
        asyncCatalog.createTable(tableId, TABLE_SCHEMA);
        // If it succeeds, cleanup already finished — that's fine, break out.
        LOG.info("Create succeeded on attempt {} — cleanup already done", attempt);
        asyncCatalog.dropTable(tableId, false);
        return;
      } catch (AlreadyExistsException e) {
        LOG.info("Attempt {} correctly blocked: {}", attempt, e.getMessage());
        Assertions.assertTrue(
            e.getMessage().contains("being purged")
                || e.getMessage().contains("purge")
                || e.getMessage().contains("cleanup"),
            "Attempt " + attempt + " error should indicate purge, got: " + e.getMessage());
      }
    }

    // Eventually it should succeed.
    Awaitility.await()
        .atMost(cleanupTimeoutSecs, TimeUnit.SECONDS)
        .pollInterval(pollIntervalSecs, TimeUnit.SECONDS)
        .untilAsserted(
            () -> {
              try {
                asyncCatalog.createTable(tableId, TABLE_SCHEMA);
              } catch (AlreadyExistsException e) {
                LOG.debug("Rapid-cycle: name still occupied: {}", e.getMessage());
                Assertions.fail("Still blocked: " + e.getMessage());
              }
            });

    asyncCatalog.dropTable(tableId, false);
  }

  @Test
  @DisplayName("Synchronous purge (no async header) does not block subsequent create")
  public void testSyncPurgeDoesNotBlockCreate() {
    TableIdentifier tableId = TableIdentifier.of(testNamespace, "t_sync_purge");

    // Create and drop with synchronous purge (using the sync client).
    syncCatalog.createTable(tableId, TABLE_SCHEMA);
    syncCatalog.dropTable(tableId, true);

    // Immediately re-creating should succeed because synchronous purge deletes everything
    // before the DROP returns — no cleanup job occupies the name.
    Assertions.assertDoesNotThrow(
        () -> syncCatalog.createTable(tableId, TABLE_SCHEMA),
        "After synchronous purge, re-creating the table should succeed immediately");

    syncCatalog.dropTable(tableId, false);
  }

  // ────────────────────────────────────────────────────────────────────────────
  // Helpers
  // ────────────────────────────────────────────────────────────────────────────

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
