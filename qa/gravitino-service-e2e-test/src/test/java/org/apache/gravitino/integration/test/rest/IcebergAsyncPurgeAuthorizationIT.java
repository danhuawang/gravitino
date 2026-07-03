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
import org.apache.gravitino.MetadataObject;
import org.apache.gravitino.MetadataObjects;
import org.apache.gravitino.authorization.Owner;
import org.apache.gravitino.authorization.Privileges;
import org.apache.gravitino.client.GravitinoAdminClient;
import org.apache.gravitino.client.GravitinoMetalake;
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
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * E2E test for async purge authorization: verifies that the {@code DELETE ...?purgeRequested=true}
 * with {@code X-Gravitino-Async-Purge: true} respects Gravitino's authorization model.
 *
 * <ul>
 *   <li>A user without DROP TABLE (or TABLE::OWNER) privilege should be denied (HTTP 403).
 *   <li>A user with the appropriate privilege can perform async purge successfully.
 *   <li>After privilege is revoked, the user is denied again.
 * </ul>
 *
 * <p><b>Requirements:</b> The target Gravitino service must have authorization enabled ({@code
 * gravitino.authorization.enabled=true}) and simple authentication configured. This test is skipped
 * if authorization is not enabled (detected by the system property {@code
 * gravitino.authorization.enabled}).
 */
@DisplayName("Async Purge: DROP Table Authorization")
public class IcebergAsyncPurgeAuthorizationIT {

  private static final Logger LOG = LoggerFactory.getLogger(IcebergAsyncPurgeAuthorizationIT.class);

  private static final String ASYNC_PURGE_HEADER = "X-Gravitino-Async-Purge";

  private static final Schema TABLE_SCHEMA =
      new Schema(
          Types.NestedField.required(1, "id", Types.LongType.get()),
          Types.NestedField.optional(2, "data", Types.StringType.get()));

  private static String gravitinoUri;
  private static String ircUri;
  private static String adminUser;
  private static String metalakeName;
  private static String catalogName;
  private static long cleanupTimeoutSecs;
  private static long pollIntervalSecs;

  private static GravitinoAdminClient adminClient;
  private static GravitinoMetalake metalake;

  /** Admin RESTCatalog for setup operations. */
  private static RESTCatalog adminCatalog;

  private static String testNamespace;
  private static HttpClient httpClient;

  private static final String RESTRICTED_USER = "async_purge_restricted_user";
  private static final String ROLE_NAME = "async_purge_test_role";

  @BeforeAll
  public static void setup() {
    gravitinoUri = System.getProperty("gravitino.uri", "http://localhost:30090");
    ircUri = System.getProperty("gravitino.irc.uri", "http://localhost:30001/iceberg/");
    adminUser = System.getProperty("gravitino.simple.user", "admin");
    metalakeName = System.getProperty("gravitino.metalake", "test");
    catalogName = System.getProperty("gravitino.irc.catalog", "catalog_iceberg_s3_3");
    cleanupTimeoutSecs =
        Long.parseLong(System.getProperty("async.purge.cleanup.timeout.secs", "120"));
    pollIntervalSecs = Long.parseLong(System.getProperty("async.purge.poll.interval.secs", "2"));
    testNamespace = "async_purge_auth_" + System.currentTimeMillis();

    httpClient = HttpClient.newHttpClient();

    // Connect to Gravitino as admin to set up authorization.
    adminClient = GravitinoAdminClient.builder(gravitinoUri).withSimpleAuth(adminUser).build();
    metalake = adminClient.loadMetalake(metalakeName);

    // Create admin RESTCatalog.
    adminCatalog = buildRESTCatalog("auth-admin", adminUser, true);
    adminCatalog.createNamespace(Namespace.of(testNamespace));

    LOG.info(
        "IcebergAsyncPurgeAuthorizationIT setup: gravitinoUri={}, ircUri={}, metalake={},"
            + " catalog={}, namespace={}",
        gravitinoUri,
        ircUri,
        metalakeName,
        catalogName,
        testNamespace);
  }

  @AfterAll
  public static void teardown() {
    // Clean up: remove user, role, namespace.
    try {
      metalake.removeUser(RESTRICTED_USER);
    } catch (Exception e) {
      LOG.debug("Failed to remove user: {}", e.getMessage());
    }
    try {
      metalake.deleteRole(ROLE_NAME);
    } catch (Exception e) {
      LOG.debug("Failed to delete role: {}", e.getMessage());
    }
    try {
      if (adminCatalog != null) {
        adminCatalog
            .listTables(Namespace.of(testNamespace))
            .forEach(id -> adminCatalog.dropTable(id, false));
        adminCatalog.dropNamespace(Namespace.of(testNamespace));
      }
    } catch (Exception e) {
      LOG.warn("Namespace cleanup failed", e);
    }
    closeQuietly(adminCatalog);
    if (adminClient != null) {
      adminClient.close();
    }
  }

  // ────────────────────────────────────────────────────────────────────────────
  // Test: Unauthorized user cannot perform async purge drop
  // ────────────────────────────────────────────────────────────────────────────

  @Test
  @DisplayName("User without DROP TABLE privilege is denied async purge (HTTP 403)")
  public void testUnauthorizedUserDeniedAsyncPurgeDrop() throws Exception {
    if (!isAuthorizationEnabled()) {
      LOG.info(
          "Skipping authorization test: authorization not enabled. "
              + "Set -Dgravitino.authorization.enabled=true to run.");
      return;
    }

    String tableName = "t_auth_deny";
    TableIdentifier tableId = TableIdentifier.of(testNamespace, tableName);

    // Step 1: Admin creates a table.
    adminCatalog.createTable(tableId, TABLE_SCHEMA);

    // Step 2: Create a restricted user with only USE_CATALOG and USE_SCHEMA (no DROP).
    setupRestrictedUser();

    // Step 3: Restricted user tries to async-purge drop the table → should get 403.
    int statusCode = sendDropTableRequest(RESTRICTED_USER, testNamespace, tableName, true, true);
    LOG.info("Restricted user DROP response status: {}", statusCode);
    Assertions.assertEquals(
        403,
        statusCode,
        "User without DROP TABLE privilege should receive HTTP 403 on async purge drop");

    // Step 4: Table should still exist (not dropped).
    Assertions.assertTrue(
        adminCatalog.tableExists(tableId), "Table should still exist after denied drop");

    // Clean up.
    adminCatalog.dropTable(tableId, false);
  }

  @Test
  @DisplayName("User with DROP TABLE privilege can perform async purge successfully")
  public void testAuthorizedUserCanAsyncPurgeDrop() throws Exception {
    if (!isAuthorizationEnabled()) {
      LOG.info("Skipping authorization test: authorization not enabled.");
      return;
    }

    String tableName = "t_auth_allow";
    TableIdentifier tableId = TableIdentifier.of(testNamespace, tableName);

    // Step 1: Admin creates a table.
    adminCatalog.createTable(tableId, TABLE_SCHEMA);

    // Step 2: Set up user with DROP permission.
    setupRestrictedUser();
    grantDropTablePrivilege();

    // Step 3: Authorized user performs async purge drop → should succeed (204).
    int statusCode = sendDropTableRequest(RESTRICTED_USER, testNamespace, tableName, true, true);
    LOG.info("Authorized user DROP response status: {}", statusCode);
    Assertions.assertEquals(
        204,
        statusCode,
        "User with DROP TABLE privilege should receive HTTP 204 on async purge drop");

    // Step 4: Table should be gone from LIST.
    Assertions.assertFalse(
        adminCatalog.tableExists(tableId), "Table should not exist after authorized drop");

    // Step 5: Name should be occupied by cleanup job (409 on re-create).
    try {
      adminCatalog.createTable(tableId, TABLE_SCHEMA);
      // If it succeeds immediately, cleanup was instant — still valid.
      LOG.info("Table re-created immediately (cleanup was fast)");
      adminCatalog.dropTable(tableId, false);
    } catch (AlreadyExistsException e) {
      LOG.info("Name occupied as expected during async cleanup: {}", e.getMessage());
      // Wait for cleanup to finish.
      waitForNameRelease(tableId);
    }
  }

  @Test
  @DisplayName("After revoking DROP privilege, user is denied async purge again")
  public void testRevokedPrivilegeDeniesAsyncPurge() throws Exception {
    if (!isAuthorizationEnabled()) {
      LOG.info("Skipping authorization test: authorization not enabled.");
      return;
    }

    String tableName = "t_auth_revoke";
    TableIdentifier tableId = TableIdentifier.of(testNamespace, tableName);

    // Setup: user with DROP privilege.
    adminCatalog.createTable(tableId, TABLE_SCHEMA);
    setupRestrictedUser();
    grantDropTablePrivilege();

    // Verify user can drop (sanity check).
    int allowedStatus = sendDropTableRequest(RESTRICTED_USER, testNamespace, tableName, true, true);
    Assertions.assertEquals(204, allowedStatus, "User should be able to drop before revoke");
    waitForNameRelease(tableId);

    // Re-create table for the revoke test.
    adminCatalog.createTable(tableId, TABLE_SCHEMA);

    // Revoke the DROP privilege.
    revokeDropTablePrivilege();

    // Now the user should be denied.
    int deniedStatus = sendDropTableRequest(RESTRICTED_USER, testNamespace, tableName, true, true);
    LOG.info("After revoke, DROP response status: {}", deniedStatus);
    Assertions.assertEquals(
        403, deniedStatus, "After revoking DROP privilege, user should get HTTP 403");

    // Table still exists.
    Assertions.assertTrue(adminCatalog.tableExists(tableId), "Table should still exist");

    // Clean up.
    adminCatalog.dropTable(tableId, false);
  }

  @Test
  @DisplayName("Non-purge drop (purgeRequested=false) also requires authorization")
  public void testNonPurgeDropAlsoRequiresAuth() throws Exception {
    if (!isAuthorizationEnabled()) {
      LOG.info("Skipping authorization test: authorization not enabled.");
      return;
    }

    String tableName = "t_auth_plain_drop";
    TableIdentifier tableId = TableIdentifier.of(testNamespace, tableName);

    adminCatalog.createTable(tableId, TABLE_SCHEMA);
    setupRestrictedUser();

    // Non-purge drop without DROP privilege → 403.
    int statusCode = sendDropTableRequest(RESTRICTED_USER, testNamespace, tableName, false, false);
    Assertions.assertEquals(
        403, statusCode, "Non-purge drop without DROP TABLE privilege should also return 403");

    // Table still exists.
    Assertions.assertTrue(adminCatalog.tableExists(tableId));
    adminCatalog.dropTable(tableId, false);
  }

  // ────────────────────────────────────────────────────────────────────────────
  // Authorization setup helpers
  // ────────────────────────────────────────────────────────────────────────────

  private void setupRestrictedUser() {
    try {
      metalake.addUser(RESTRICTED_USER);
    } catch (Exception e) {
      LOG.debug("User already exists: {}", e.getMessage());
    }

    try {
      metalake.deleteRole(ROLE_NAME);
    } catch (Exception e) {
      LOG.debug("Role does not exist yet: {}", e.getMessage());
    }

    // Create role with USE_CATALOG and USE_SCHEMA only (no DROP).
    metalake.createRole(ROLE_NAME, new HashMap<>(), java.util.Collections.emptyList());

    MetadataObject catalogObj = MetadataObjects.of(null, catalogName, MetadataObject.Type.CATALOG);
    metalake.grantPrivilegesToRole(
        ROLE_NAME,
        catalogObj,
        com.google.common.collect.Sets.newHashSet(Privileges.UseCatalog.allow()));

    MetadataObject schemaObj =
        MetadataObjects.of(catalogName, testNamespace, MetadataObject.Type.SCHEMA);
    metalake.grantPrivilegesToRole(
        ROLE_NAME,
        schemaObj,
        com.google.common.collect.Sets.newHashSet(Privileges.UseSchema.allow()));

    metalake.grantRolesToUser(
        com.google.common.collect.Lists.newArrayList(ROLE_NAME), RESTRICTED_USER);
  }

  private void grantDropTablePrivilege() {
    // The dropTable authorization requires ownership or specific owner-level roles.
    // The simplest way to grant drop access: make the user the owner of the schema,
    // which satisfies SCHEMA_OWNER_WITH_USE_CATALOG in the authorization expression.
    MetadataObject schemaObj =
        MetadataObjects.of(catalogName, testNamespace, MetadataObject.Type.SCHEMA);
    metalake.setOwner(schemaObj, RESTRICTED_USER, Owner.Type.USER);
  }

  private void revokeDropTablePrivilege() {
    // Reset schema ownership back to admin to revoke the user's implicit drop right.
    MetadataObject schemaObj =
        MetadataObjects.of(catalogName, testNamespace, MetadataObject.Type.SCHEMA);
    metalake.setOwner(schemaObj, adminUser, Owner.Type.USER);
  }

  // ────────────────────────────────────────────────────────────────────────────
  // HTTP helpers
  // ────────────────────────────────────────────────────────────────────────────

  /**
   * Sends a raw HTTP DELETE to drop a table, authenticated as the given user.
   *
   * @param username the user to authenticate as (simple auth)
   * @param namespace the table namespace
   * @param table the table name
   * @param purgeRequested whether to set purgeRequested=true
   * @param asyncPurge whether to include the X-Gravitino-Async-Purge header
   * @return the HTTP response status code
   */
  private int sendDropTableRequest(
      String username, String namespace, String table, boolean purgeRequested, boolean asyncPurge)
      throws Exception {
    String base = ircUri.endsWith("/") ? ircUri : ircUri + "/";
    String url =
        String.format(
            "%sv1/%s/namespaces/%s/tables/%s?purgeRequested=%s",
            base, catalogName, namespace, table, purgeRequested);

    HttpRequest.Builder builder =
        HttpRequest.newBuilder()
            .uri(URI.create(url))
            .header("Authorization", basicAuth(username))
            .DELETE();
    if (asyncPurge) {
      builder.header(ASYNC_PURGE_HEADER, "true");
    }

    HttpResponse<String> response =
        httpClient.send(builder.build(), HttpResponse.BodyHandlers.ofString());
    LOG.debug(
        "DROP {} as user={} purge={} async={} -> status={} body={}",
        url,
        username,
        purgeRequested,
        asyncPurge,
        response.statusCode(),
        response.body());
    return response.statusCode();
  }

  private static String basicAuth(String username) {
    String credentials = username + ":mock";
    return "Basic "
        + Base64.getEncoder().encodeToString(credentials.getBytes(StandardCharsets.UTF_8));
  }

  private static boolean isAuthorizationEnabled() {
    String enabled = System.getProperty("gravitino.authorization.enabled", "false");
    return "true".equalsIgnoreCase(enabled);
  }

  private void waitForNameRelease(TableIdentifier tableId) {
    Awaitility.await()
        .atMost(cleanupTimeoutSecs, TimeUnit.SECONDS)
        .pollInterval(pollIntervalSecs, TimeUnit.SECONDS)
        .untilAsserted(
            () -> {
              try {
                adminCatalog.createTable(tableId, TABLE_SCHEMA);
                adminCatalog.dropTable(tableId, false);
              } catch (AlreadyExistsException e) {
                Assertions.fail("Name still occupied: " + e.getMessage());
              }
            });
  }

  private static RESTCatalog buildRESTCatalog(String name, String username, boolean asyncPurge) {
    Map<String, String> props = new HashMap<>();
    props.put(CatalogProperties.URI, ircUri);
    props.put(CatalogProperties.CACHE_ENABLED, "false");
    props.put("rest.auth.type", "basic");
    props.put("rest.auth.basic.username", username);
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
