/*
 * Copyright 2024 Datastrato Pvt Ltd.
 * This software is licensed under the Apache License version 2.
 */
package org.apache.gravitino.integration.test.rest;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
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
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * E2E tests for async table purge — Test Cases #5, #6, #7, #8 from async-purge-test-plan.
 *
 * <ul>
 *   <li>#5: Header name case variants must all trigger async behavior.
 *   <li>#6: Header value not exactly {@code "true"} must fall back to synchronous purge.
 *   <li>#7: Async purge header sent in standalone mode is ignored (synchronous purge used).
 *   <li>#8: {@code purgeRequested=false} with async header present only drops the catalog entry
 *       without triggering any cleanup job.
 * </ul>
 *
 * <p>Tests #5 and #6 send raw HTTP DELETE requests to control the header precisely, since the
 * {@link RESTCatalog} client only supports a single fixed header value per property key.
 *
 * <p>Test #7 requires a standalone Iceberg REST server (no Gravitino server). It is skipped by
 * default and enabled by setting the system property {@code iceberg.standalone.uri} to the
 * standalone server's base URI.
 */
@DisplayName("Async Purge: Header Parsing & Mode Boundaries (#5, #6, #7, #8)")
public class IcebergAsyncPurgeHeaderIT {

  private static final Logger LOG = LoggerFactory.getLogger(IcebergAsyncPurgeHeaderIT.class);

  private static final String ASYNC_PURGE_HEADER = "X-Gravitino-Async-Purge";

  private static final Schema TABLE_SCHEMA =
      new Schema(
          Types.NestedField.required(1, "id", Types.LongType.get()),
          Types.NestedField.optional(2, "data", Types.StringType.get()));

  private static long cleanupTimeoutSecs;
  private static long pollIntervalSecs;

  private static String ircUri;
  private static String simpleUser;
  private static String catalogName;

  /** RESTCatalog with async purge header for setup/teardown operations. */
  private static RESTCatalog asyncCatalog;

  /** RESTCatalog without async purge header. */
  private static RESTCatalog syncCatalog;

  private static String testNamespace;
  private static HttpClient httpClient;

  @BeforeAll
  public static void setup() {
    ircUri = System.getProperty("gravitino.irc.uri", "http://localhost:30001/iceberg/");
    simpleUser = System.getProperty("gravitino.simple.user", "admin");
    catalogName = System.getProperty("gravitino.irc.catalog", "catalog_iceberg_s3_3");
    cleanupTimeoutSecs =
        Long.parseLong(System.getProperty("async.purge.cleanup.timeout.secs", "120"));
    pollIntervalSecs = Long.parseLong(System.getProperty("async.purge.poll.interval.secs", "2"));
    testNamespace = "async_purge_header_" + System.currentTimeMillis();

    asyncCatalog = buildRESTCatalog("header-test-async", true);
    syncCatalog = buildRESTCatalog("header-test-sync", false);
    httpClient = HttpClient.newHttpClient();

    asyncCatalog.createNamespace(Namespace.of(testNamespace));
    LOG.info(
        "IcebergAsyncPurgeHeaderIT setup: ircUri={}, catalog={}, namespace={}",
        ircUri,
        catalogName,
        testNamespace);
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
    // Wait briefly for any lingering cleanup jobs to finish so tests are independent.
    waitForCleanupToFinish();
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
  // Test #5: Header name case variants
  // ────────────────────────────────────────────────────────────────────────────

  @ParameterizedTest(name = "Header \"{0}: true\" should trigger async purge")
  @ValueSource(
      strings = {
        "X-Gravitino-Async-Purge",
        "x-gravitino-async-purge",
        "X-GRAVITINO-ASYNC-PURGE",
        "x-Gravitino-Async-Purge",
        "X-gravitino-ASYNC-purge"
      })
  @DisplayName("#5: Header name case variants all trigger async purge")
  public void testHeaderNameCaseVariantsAllTriggerAsyncPurge(String headerName) throws Exception {
    String tableName = "t_case_" + headerName.hashCode();
    // Ensure uniqueness by using absolute value.
    tableName = tableName.replace('-', '_');
    TableIdentifier tableId = TableIdentifier.of(testNamespace, tableName);

    // Create a table via the normal catalog.
    syncCatalog.createTable(tableId, TABLE_SCHEMA);
    Assertions.assertTrue(syncCatalog.tableExists(tableId));

    // Send a raw HTTP DELETE with the specific header name casing and value "true".
    int status = sendDropTableRequest(headerName, "true", testNamespace, tableName);
    Assertions.assertEquals(
        204, status, "DROP should return 204 regardless of header casing: " + headerName);

    // If async purge was triggered, the name should be occupied (409 on create).
    // If it fell through to sync purge, create would succeed immediately.
    try {
      syncCatalog.createTable(tableId, TABLE_SCHEMA);
      // If we get here, it was synchronous — which means the header casing was NOT recognized.
      Assertions.fail(
          "Header name '"
              + headerName
              + "' should trigger async purge (create should have thrown 409), "
              + "but create succeeded immediately, indicating synchronous purge was used.");
    } catch (AlreadyExistsException e) {
      // Expected: the async cleanup job is occupying the name.
      LOG.info(
          "Header '{}' correctly triggered async purge, got 409: {}", headerName, e.getMessage());
      Assertions.assertTrue(
          e.getMessage().contains("purge") || e.getMessage().contains("cleanup"),
          "Error message should indicate cleanup in progress, got: " + e.getMessage());
    }

    // Wait for cleanup to release the name.
    waitForNameRelease(tableId);
  }

  // ────────────────────────────────────────────────────────────────────────────
  // Test #6: Header value is not exactly "true"
  // ────────────────────────────────────────────────────────────────────────────

  @ParameterizedTest(name = "Header value \"{0}\" should NOT trigger async purge")
  @ValueSource(strings = {"True", "TRUE", "1", "yes", "false", "", "tru"})
  @DisplayName("#6: Non-exact 'true' header values fall back to synchronous purge")
  public void testNonTrueHeaderValuesFallBackToSyncPurge(String headerValue) throws Exception {
    String tableName = "t_val_" + Math.abs(headerValue.hashCode());
    TableIdentifier tableId = TableIdentifier.of(testNamespace, tableName);

    // Create a table.
    syncCatalog.createTable(tableId, TABLE_SCHEMA);
    Assertions.assertTrue(syncCatalog.tableExists(tableId));

    // Send raw HTTP DELETE with standard header name but non-"true" value.
    int status = sendDropTableRequest(ASYNC_PURGE_HEADER, headerValue, testNamespace, tableName);
    Assertions.assertEquals(
        204, status, "DROP should return 204 even with non-true header value: " + headerValue);

    // If it was synchronous purge, the name is immediately available for re-creation.
    // If it was async, create would throw 409.
    Assertions.assertDoesNotThrow(
        () -> syncCatalog.createTable(tableId, TABLE_SCHEMA),
        "Header value '"
            + headerValue
            + "' should NOT trigger async purge — re-create should succeed immediately "
            + "because synchronous purge already deleted everything.");
    LOG.info(
        "Header value '{}' correctly fell back to synchronous purge (create succeeded)",
        headerValue);

    // Clean up.
    syncCatalog.dropTable(tableId, false);
  }

  // ────────────────────────────────────────────────────────────────────────────
  // Test #6 (edge case): Value " true" with surrounding whitespace DOES trigger
  // async purge because the implementation trims the value before comparison.
  // ────────────────────────────────────────────────────────────────────────────

  @ParameterizedTest(name = "Header value \"{0}\" triggers async purge (trim behavior)")
  @ValueSource(strings = {" true", "true ", " true "})
  @DisplayName("#6 edge: Whitespace-padded 'true' triggers async purge (impl trims)")
  public void testTrimmedTrueValuesDoTriggerAsyncPurge(String headerValue) throws Exception {
    String tableName = "t_trim_" + Math.abs(headerValue.hashCode());
    TableIdentifier tableId = TableIdentifier.of(testNamespace, tableName);

    syncCatalog.createTable(tableId, TABLE_SCHEMA);

    int status = sendDropTableRequest(ASYNC_PURGE_HEADER, headerValue, testNamespace, tableName);
    Assertions.assertEquals(204, status, "DROP should return 204");

    // The implementation trims the header value, so " true" matches "true" and triggers async.
    try {
      syncCatalog.createTable(tableId, TABLE_SCHEMA);
      // If create succeeds, it means sync purge was used — unexpected for trimmed "true".
      Assertions.fail(
          "Header value '"
              + headerValue
              + "' should trigger async purge after trim, "
              + "but create succeeded immediately (sync purge was used).");
    } catch (AlreadyExistsException e) {
      LOG.info(
          "Header value '{}' correctly triggers async purge (trim behavior): {}",
          headerValue,
          e.getMessage());
    }

    waitForNameRelease(tableId);
  }

  // ────────────────────────────────────────────────────────────────────────────
  // Test #7: Standalone mode ignores async purge header
  // ────────────────────────────────────────────────────────────────────────────

  @Test
  @DisplayName("#7: Async purge header in standalone mode is ignored (synchronous purge used)")
  public void testStandaloneModeIgnoresAsyncPurgeHeader() throws Exception {
    String standaloneUri = System.getProperty("iceberg.standalone.uri");
    if (standaloneUri == null || standaloneUri.isEmpty()) {
      LOG.info(
          "Skipping standalone mode test: set -Diceberg.standalone.uri to enable. "
              + "This test requires a standalone Iceberg REST server (not Gravitino auxiliary).");
      return;
    }

    // Build a RESTCatalog pointing at the standalone server with async purge header.
    String ns = "standalone_test_" + System.currentTimeMillis();
    RESTCatalog standaloneCatalog = buildStandaloneCatalog(standaloneUri);
    try {
      standaloneCatalog.createNamespace(Namespace.of(ns));
      TableIdentifier tableId = TableIdentifier.of(ns, "t_standalone");
      standaloneCatalog.createTable(tableId, TABLE_SCHEMA);

      // Drop with purge — in standalone mode the header should be ignored.
      standaloneCatalog.dropTable(tableId, true);

      // If standalone correctly ignores the header (synchronous purge), the name is free.
      RESTCatalog finalCatalog = standaloneCatalog;
      Assertions.assertDoesNotThrow(
          () -> finalCatalog.createTable(tableId, TABLE_SCHEMA),
          "Standalone mode should ignore X-Gravitino-Async-Purge header and purge synchronously. "
              + "Re-creating the table should succeed immediately.");
      LOG.info("Standalone mode correctly used synchronous purge despite async header");

      standaloneCatalog.dropTable(tableId, false);
    } finally {
      try {
        standaloneCatalog.dropNamespace(Namespace.of(ns));
      } catch (Exception ignored) {
      }
      closeQuietly(standaloneCatalog);
    }
  }

  // ────────────────────────────────────────────────────────────────────────────
  // Test #8: purgeRequested=false with async header does NOT trigger cleanup
  // ────────────────────────────────────────────────────────────────────────────

  @Test
  @DisplayName("#8: purgeRequested=false + async header = plain drop, no cleanup job")
  public void testNoPurgeRequestedWithAsyncHeaderDoesNotBlockCreate() {
    TableIdentifier tableId = TableIdentifier.of(testNamespace, "t_no_purge");

    // Create a table using the async-header client.
    asyncCatalog.createTable(tableId, TABLE_SCHEMA);
    Assertions.assertTrue(asyncCatalog.tableExists(tableId));

    // Drop WITHOUT purge (purgeRequested=false) using the async-header client.
    // Even though the async purge header is present, purgeRequested=false should mean:
    // - Only the catalog entry is removed (plain drop).
    // - No cleanup job is created.
    // - Files remain on storage (but that's by design for non-purge drops).
    boolean dropped = asyncCatalog.dropTable(tableId, false);
    Assertions.assertTrue(dropped, "dropTable(purge=false) should return true");

    // The name should be immediately available — no cleanup job should occupy it.
    Assertions.assertDoesNotThrow(
        () -> asyncCatalog.createTable(tableId, TABLE_SCHEMA),
        "purgeRequested=false should not trigger any cleanup job, so re-creating the table "
            + "with the same name should succeed immediately even with async header present.");
    LOG.info("purgeRequested=false + async header correctly did a plain drop (no 409)");

    // Clean up.
    asyncCatalog.dropTable(tableId, false);
  }

  @Test
  @DisplayName("#8 (variant): purgeRequested=false via raw HTTP + async header")
  public void testNoPurgeRequestedViaHttpWithAsyncHeader() throws Exception {
    String tableName = "t_no_purge_http";
    TableIdentifier tableId = TableIdentifier.of(testNamespace, tableName);

    syncCatalog.createTable(tableId, TABLE_SCHEMA);

    // Send raw HTTP DELETE with purgeRequested=false AND the async purge header.
    String dropUrl = buildDropTableUrl(testNamespace, tableName, false);
    HttpRequest request =
        HttpRequest.newBuilder()
            .uri(URI.create(dropUrl))
            .header(ASYNC_PURGE_HEADER, "true")
            .header("Authorization", basicAuth())
            .DELETE()
            .build();
    HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
    Assertions.assertEquals(
        204, response.statusCode(), "DROP with purgeRequested=false should return 204");

    // Name should be immediately free.
    Assertions.assertDoesNotThrow(
        () -> syncCatalog.createTable(tableId, TABLE_SCHEMA),
        "purgeRequested=false via raw HTTP + async header should not create a cleanup job.");

    syncCatalog.dropTable(tableId, false);
  }

  // ────────────────────────────────────────────────────────────────────────────
  // Helpers
  // ────────────────────────────────────────────────────────────────────────────

  /**
   * Sends a raw HTTP DELETE to the Iceberg REST drop-table endpoint with the specified header name
   * and value, and {@code purgeRequested=true}.
   */
  private int sendDropTableRequest(
      String headerName, String headerValue, String namespace, String table) throws Exception {
    String url = buildDropTableUrl(namespace, table, true);
    HttpRequest.Builder builder =
        HttpRequest.newBuilder().uri(URI.create(url)).header("Authorization", basicAuth()).DELETE();
    // Add the async purge header with the specified name and value.
    builder.header(headerName, headerValue);
    HttpRequest request = builder.build();
    HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
    LOG.debug(
        "DROP {} with header {}={} -> status={}, body={}",
        url,
        headerName,
        headerValue,
        response.statusCode(),
        response.body());
    return response.statusCode();
  }

  /**
   * Builds the Iceberg REST API URL for dropping a table.
   *
   * <p>URL pattern: {@code
   * {ircUri}v1/{catalogName}/namespaces/{ns}/tables/{table}?purgeRequested=X}
   */
  private static String buildDropTableUrl(String namespace, String table, boolean purgeRequested) {
    String base = ircUri;
    if (!base.endsWith("/")) {
      base += "/";
    }
    return String.format(
        "%sv1/%s/namespaces/%s/tables/%s?purgeRequested=%s",
        base, catalogName, namespace, table, purgeRequested);
  }

  private static String basicAuth() {
    String credentials = simpleUser + ":mock";
    return "Basic "
        + Base64.getEncoder().encodeToString(credentials.getBytes(StandardCharsets.UTF_8));
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

  private static RESTCatalog buildStandaloneCatalog(String uri) {
    Map<String, String> props = new HashMap<>();
    props.put(CatalogProperties.URI, uri);
    props.put(CatalogProperties.CACHE_ENABLED, "false");
    props.put("header." + ASYNC_PURGE_HEADER, "true");
    RESTCatalog catalog = new RESTCatalog();
    catalog.setConf(new Configuration());
    catalog.initialize("standalone-test", props);
    return catalog;
  }

  /** Waits for any cleanup job to release a specific table name. */
  private void waitForNameRelease(TableIdentifier tableId) {
    Awaitility.await()
        .atMost(cleanupTimeoutSecs, TimeUnit.SECONDS)
        .pollInterval(pollIntervalSecs, TimeUnit.SECONDS)
        .pollInSameThread()
        .untilAsserted(
            () -> {
              try {
                syncCatalog.createTable(tableId, TABLE_SCHEMA);
                syncCatalog.dropTable(tableId, false);
              } catch (AlreadyExistsException e) {
                LOG.debug("Name still occupied: {}", e.getMessage());
                Assertions.fail("Name still occupied by cleanup: " + e.getMessage());
              }
            });
  }

  /** Best-effort wait for any lingering cleanup jobs in the test namespace. */
  private void waitForCleanupToFinish() {
    try {
      Thread.sleep(1000);
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
    }
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
