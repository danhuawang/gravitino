/*
 * Copyright 2024 Datastrato Pvt Ltd.
 * This software is licensed under the Apache License version 2.
 */
package org.apache.gravitino.integration.test.oauth2.group;

import com.google.common.collect.Lists;
import com.google.common.collect.Maps;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.Collections;
import java.util.HashSet;
import java.util.Set;
import org.apache.gravitino.Catalog;
import org.apache.gravitino.NameIdentifier;
import org.apache.gravitino.authorization.Privileges;
import org.apache.gravitino.authorization.SecurableObject;
import org.apache.gravitino.authorization.SecurableObjects;
import org.apache.gravitino.client.DefaultOAuth2TokenProvider;
import org.apache.gravitino.client.GravitinoAdminClient;
import org.apache.gravitino.client.GravitinoMetalake;
import org.apache.gravitino.client.OAuth2TokenProvider;
import org.apache.gravitino.qa.common.oauth2.KeycloakAdminHelper;
import org.apache.gravitino.rel.Column;
import org.apache.gravitino.rel.types.Types;
import org.apache.gravitino.utils.RandomNameUtils;
import org.apache.spark.SparkConf;
import org.apache.spark.sql.SparkSession;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Engine-side E2E tests for group-based AC via vanilla Spark + Iceberg REST (T12, T13). Bound to
 * Env 2 oauth2-auth. Metalake/catalog are pre-seeded by {@code qa/scripts/setup-kind-env.sh}; tests
 * only create schemas/tables/users/groups and clean them up.
 *
 * <p>T12 — IRC reads JWT {@code groups} claim, authorizes via group role. T13 — IRC caches ({@code
 * scan-plan-cache}, {@code table-metadata-cache}) do not resurrect a stale ALLOW after {@code
 * revokeRolesFromGroup}.
 */
@DisplayName("Spark-via-IRC Group-Based Access Control E2E (env2-oauth2-auth)")
public class SparkIcebergRestGroupBasedAccessControlIT {

  private static final Logger LOG =
      LoggerFactory.getLogger(SparkIcebergRestGroupBasedAccessControlIT.class);

  // Metalake and catalog are pre-seeded by setup-kind-env.sh; IRC's dynamic-config-provider
  // (env2-oauth2-auth-values.yaml) routes requests to metalake=test, defaultCatalogName=catalog_1.
  private static final String METALAKE_NAME = System.getProperty("gravitino.metalake", "test");
  private static final String CATALOG_NAME =
      System.getProperty("gravitino.irc.catalog", "catalog_1");
  private static final String SCHEMA_NAME = RandomNameUtils.genRandomName("test_schema");
  private static final String TABLE_NAME = "tbl_a";
  private static final String SPARK_CATALOG = "gravitino_irc";

  private static final String DEFAULT_SCOPE = "openid profile email";
  private static final String DEFAULT_REALM = "myrealm";
  private static final String DEFAULT_ADMIN_USER = "admin";
  private static final String DEFAULT_ADMIN_PASSWORD = "admin";

  private static String gravitinoUri;
  private static String ircUri;
  private static String keycloakBaseUrl;
  private static String keycloakRealm;
  private static String keycloakClientId;
  private static String keycloakClientSecret;
  private static String keycloakAdminUser;
  private static String keycloakAdminPassword;
  private static String keycloakTokenPath;
  private static String keycloakScope;

  private static GravitinoAdminClient adminClient;
  private static GravitinoMetalake metalake;
  private static Catalog catalog;
  private static KeycloakAdminHelper keycloak;

  // Track only entities this test created; metalake/catalog are shared infrastructure.
  private final Set<String> createdMetalakeUsers = new HashSet<>();
  private final Set<String> createdMetalakeGroups = new HashSet<>();
  private final Set<String> createdMetalakeRoles = new HashSet<>();
  private final Set<String> createdUsernames = new HashSet<>();
  private final Set<String> createdGroupNames = new HashSet<>();

  private SparkSession spark;

  @BeforeAll
  public static void startIntegrationTest() throws Exception {
    gravitinoUri = System.getProperty("gravitino.uri", "http://localhost:30090");
    ircUri = System.getProperty("gravitino.irc.uri", "http://localhost:30001/iceberg/");

    keycloakBaseUrl = requireEnv("OAUTH2_SERVER_URI");
    keycloakClientId = requireEnv("OAUTH2_CLIENT_ID");
    keycloakClientSecret = requireEnv("OAUTH2_CLIENT_SECRET");
    keycloakRealm = System.getenv().getOrDefault("OAUTH2_REALM", DEFAULT_REALM);
    keycloakScope = System.getenv().getOrDefault("OAUTH2_SCOPE", DEFAULT_SCOPE);
    keycloakAdminUser = System.getenv().getOrDefault("OAUTH2_ADMIN_USER", DEFAULT_ADMIN_USER);
    keycloakAdminPassword =
        System.getenv().getOrDefault("OAUTH2_ADMIN_PASSWORD", DEFAULT_ADMIN_PASSWORD);
    keycloakTokenPath =
        System.getenv()
            .getOrDefault(
                "OAUTH2_TOKEN_PATH",
                String.format("realms/%s/protocol/openid-connect/token", keycloakRealm));
    if (keycloakTokenPath.startsWith("/")) {
      keycloakTokenPath = keycloakTokenPath.substring(1);
    }

    keycloak =
        KeycloakAdminHelper.builder()
            .withBaseUrl(keycloakBaseUrl)
            .withRealm(keycloakRealm)
            .withMasterAdminPassword(keycloakAdminUser, keycloakAdminPassword)
            .withUserTokenClient(keycloakClientId, keycloakClientSecret)
            .build();
    keycloak.authenticate();
    keycloak.ensureGroupsMapperOnClient(keycloakClientId);

    adminClient =
        GravitinoAdminClient.builder(gravitinoUri).withOAuth(adminTokenProvider()).build();
    metalake = adminClient.loadMetalake(METALAKE_NAME);
    catalog = metalake.loadCatalog(CATALOG_NAME);

    catalog.asSchemas().createSchema(SCHEMA_NAME, "comment", Collections.emptyMap());
    Column[] columns = {Column.of("id", Types.LongType.get(), "row id")};
    catalog
        .asTableCatalog()
        .createTable(
            NameIdentifier.of(SCHEMA_NAME, TABLE_NAME),
            columns,
            "group-based AC fixture table",
            Collections.emptyMap());
  }

  @BeforeEach
  public void resetTrackers() {
    createdMetalakeUsers.clear();
    createdMetalakeGroups.clear();
    createdMetalakeRoles.clear();
    createdUsernames.clear();
    createdGroupNames.clear();
  }

  @AfterEach
  public void cleanup() {
    if (spark != null) {
      try {
        spark.close();
      } catch (Exception e) {
        LOG.warn("Failed to close SparkSession", e);
      }
      spark = null;
    }

    // Drop only what this test created; roles first to avoid dangling references.
    for (String roleName : createdMetalakeRoles) {
      try {
        metalake.deleteRole(roleName);
      } catch (Exception e) {
        LOG.warn("Failed to delete role '{}': {}", roleName, e.getMessage());
      }
    }
    for (String userName : createdMetalakeUsers) {
      try {
        metalake.removeUser(userName);
      } catch (Exception e) {
        LOG.warn("Failed to remove user '{}': {}", userName, e.getMessage());
      }
    }
    for (String groupName : createdMetalakeGroups) {
      try {
        metalake.removeGroup(groupName);
      } catch (Exception e) {
        LOG.warn("Failed to remove group '{}': {}", groupName, e.getMessage());
      }
    }
    for (String username : createdUsernames) {
      try {
        String userId = keycloak.getUserId(username);
        if (userId != null) {
          keycloak.deleteUser(userId);
        }
      } catch (Exception e) {
        LOG.warn("Failed to delete Keycloak user '{}': {}", username, e.getMessage());
      }
    }
    for (String groupName : createdGroupNames) {
      try {
        String groupId = keycloak.getGroupId(groupName);
        if (groupId != null) {
          keycloak.deleteGroup(groupId);
        }
      } catch (Exception e) {
        LOG.warn("Failed to delete Keycloak group '{}': {}", groupName, e.getMessage());
      }
    }
  }

  @AfterAll
  public static void stopIntegrationTest() {
    // Drop only the fixture table/schema; metalake/catalog are shared infrastructure.
    try {
      if (catalog != null) {
        catalog.asTableCatalog().dropTable(NameIdentifier.of(SCHEMA_NAME, TABLE_NAME));
      }
    } catch (Exception e) {
      LOG.warn("Failed to drop fixture table '{}.{}'", SCHEMA_NAME, TABLE_NAME, e);
    }
    try {
      if (catalog != null) {
        catalog.asSchemas().dropSchema(SCHEMA_NAME, true);
      }
    } catch (Exception e) {
      LOG.warn("Failed to drop fixture schema '{}'", SCHEMA_NAME, e);
    }
    try {
      if (keycloak != null) {
        keycloak.close();
      }
    } catch (Exception e) {
      LOG.warn("Failed to close KeycloakAdminHelper", e);
    }
    try {
      if (adminClient != null) {
        adminClient.close();
      }
    } catch (Exception e) {
      LOG.warn("Failed to close admin client", e);
    }
  }

  // ──────────────────────────────────────────────────────────────────────────
  //  T12 — Spark-via-IRC SELECT via group-inherited privilege
  // ──────────────────────────────────────────────────────────────────────────
  @Test
  @DisplayName(
      "T12: Spark-via-IRC SELECT succeeds through privileges inherited from the user's group")
  public void testSparkIrcSelectViaGroupRole() throws Exception {
    String groupName = "dev_team";
    String roleName = "r_select";
    String alice = "alice";
    String alicePassword = "alice-password";
    String eve = "eve";
    String evePassword = "eve-password";

    // Keycloak: alice joins dev_team; eve does not.
    String groupId = keycloak.createGroup(groupName);
    createdGroupNames.add(groupName);
    String aliceId = keycloak.createUser(alice, alicePassword);
    createdUsernames.add(alice);
    keycloak.addUserToGroup(aliceId, groupId);
    keycloak.createUser(eve, evePassword);
    createdUsernames.add(eve);

    metalake.addUser(alice);
    createdMetalakeUsers.add(alice);
    metalake.addUser(eve);
    createdMetalakeUsers.add(eve);
    metalake.addGroup(groupName);
    createdMetalakeGroups.add(groupName);

    SecurableObject catalogObject =
        SecurableObjects.ofCatalog(CATALOG_NAME, Lists.newArrayList(Privileges.UseCatalog.allow()));
    SecurableObject schemaObject =
        SecurableObjects.ofSchema(
            catalogObject, SCHEMA_NAME, Lists.newArrayList(Privileges.UseSchema.allow()));
    SecurableObject tableObject =
        SecurableObjects.ofTable(
            schemaObject, TABLE_NAME, Lists.newArrayList(Privileges.SelectTable.allow()));
    metalake.createRole(
        roleName, Maps.newHashMap(), Lists.newArrayList(catalogObject, schemaObject, tableObject));
    createdMetalakeRoles.add(roleName);
    metalake.grantRolesToGroup(Lists.newArrayList(roleName), groupName);

    String tableFqn = String.format("%s.%s.%s", SPARK_CATALOG, SCHEMA_NAME, TABLE_NAME);

    // alice (dev_team): SELECT must succeed.
    String aliceToken = keycloak.fetchUserToken(alice, alicePassword);
    LOG.info("Alice JWT payload (T12): {}", decodeJwtPayload(aliceToken));
    Assertions.assertTrue(
        decodeJwtPayload(aliceToken).contains("dev_team"),
        "Alice's JWT must place her in 'dev_team' for T12 to be meaningful.");
    spark = newSparkSessionForToken("alice-t12", aliceToken);
    Assertions.assertDoesNotThrow(
        () -> spark.sql("SELECT * FROM " + tableFqn).collect(),
        "alice (dev_team) must be able to SELECT through Spark + IRC");
    spark.close();
    spark = null;

    // eve (no dev_team): SELECT must be rejected.
    String eveToken = keycloak.fetchUserToken(eve, evePassword);
    LOG.info("Eve JWT payload (T12): {}", decodeJwtPayload(eveToken));
    Assertions.assertFalse(
        decodeJwtPayload(eveToken).contains("dev_team"),
        "Eve's JWT must not place her in 'dev_team' for T12 to be meaningful.");
    spark = newSparkSessionForToken("eve-t12", eveToken);
    Assertions.assertThrows(
        Exception.class,
        () -> spark.sql("SELECT * FROM " + tableFqn).collect(),
        "eve has no dev_team membership; Spark + IRC must reject the SELECT");
  }

  // ──────────────────────────────────────────────────────────────────────────
  //  T13 — Spark-via-IRC revoke role from group → next session denied
  // ──────────────────────────────────────────────────────────────────────────
  @Test
  @DisplayName("T13: revokeRolesFromGroup is reflected in the next Spark session (IRC cache guard)")
  public void testSparkIrcRevokeRoleFromGroup() throws Exception {
    String groupName = "dev_team";
    String roleName = "r_select";
    String alice = "alice";
    String alicePassword = "alice-password";

    String groupId = keycloak.createGroup(groupName);
    createdGroupNames.add(groupName);
    String aliceId = keycloak.createUser(alice, alicePassword);
    createdUsernames.add(alice);
    keycloak.addUserToGroup(aliceId, groupId);
    metalake.addUser(alice);
    createdMetalakeUsers.add(alice);
    metalake.addGroup(groupName);
    createdMetalakeGroups.add(groupName);

    SecurableObject catalogObject =
        SecurableObjects.ofCatalog(CATALOG_NAME, Lists.newArrayList(Privileges.UseCatalog.allow()));
    SecurableObject schemaObject =
        SecurableObjects.ofSchema(
            catalogObject, SCHEMA_NAME, Lists.newArrayList(Privileges.UseSchema.allow()));
    SecurableObject tableObject =
        SecurableObjects.ofTable(
            schemaObject, TABLE_NAME, Lists.newArrayList(Privileges.SelectTable.allow()));
    metalake.createRole(
        roleName, Maps.newHashMap(), Lists.newArrayList(catalogObject, schemaObject, tableObject));
    createdMetalakeRoles.add(roleName);
    metalake.grantRolesToGroup(Lists.newArrayList(roleName), groupName);

    String tableFqn = String.format("%s.%s.%s", SPARK_CATALOG, SCHEMA_NAME, TABLE_NAME);

    // Session 1: SELECT succeeds (primes IRC caches).
    String aliceToken1 = keycloak.fetchUserToken(alice, alicePassword);
    LOG.info("Alice JWT payload (T13 session 1): {}", decodeJwtPayload(aliceToken1));
    Assertions.assertTrue(
        decodeJwtPayload(aliceToken1).contains("dev_team"),
        "Alice's JWT must place her in 'dev_team' for T13 to be meaningful.");
    spark = newSparkSessionForToken("alice-t13-s1", aliceToken1);
    Assertions.assertDoesNotThrow(
        () -> spark.sql("SELECT * FROM " + tableFqn).collect(),
        "alice must be able to SELECT before revoke");
    spark.close();
    spark = null;

    metalake.revokeRolesFromGroup(Lists.newArrayList(roleName), groupName);

    // Session 2: fresh token + new session → IRC caches must not resurrect the stale ALLOW.
    String aliceToken2 = keycloak.fetchUserToken(alice, alicePassword);
    LOG.info("Alice JWT payload (T13 session 2): {}", decodeJwtPayload(aliceToken2));
    spark = newSparkSessionForToken("alice-t13-s2", aliceToken2);
    Assertions.assertThrows(
        Exception.class,
        () -> spark.sql("SELECT * FROM " + tableFqn).collect(),
        "IRC must reject after revoke; no IRC cache must resurrect the stale ALLOW");

    Assertions.assertFalse(
        metalake.getGroup(groupName).roles().contains(roleName),
        "Group 'dev_team' must no longer hold 'r_select' after revokeRolesFromGroup");
  }

  // ──────────────────────────────────────────────────────────────────────────
  //  Helpers
  // ──────────────────────────────────────────────────────────────────────────

  /** Required env var; fails fast at @BeforeAll if missing. */
  private static String requireEnv(String name) {
    String value = System.getenv(name);
    if (value == null || value.isEmpty()) {
      throw new IllegalStateException(
          "Required environment variable not set: " + name + ". Run setup-kind-env.sh first.");
    }
    return value;
  }

  /** Bootstrap admin token provider (Keycloak postman-client, client_credentials grant). */
  private static OAuth2TokenProvider adminTokenProvider() {
    String credential = keycloakClientId + ":" + keycloakClientSecret;
    return DefaultOAuth2TokenProvider.builder()
        .withUri(keycloakBaseUrl)
        .withCredential(credential)
        .withScope(keycloakScope)
        .withPath(keycloakTokenPath)
        .build();
  }

  /**
   * SparkSession with Iceberg REST catalog pointed at the IRC endpoint, using {@code oidcToken} as
   * a static bearer. {@code token-exchange-enabled=false} prevents the Iceberg client from swapping
   * the user token for a service-account token.
   */
  private static SparkSession newSparkSessionForToken(String app, String oidcToken) {
    String base = "spark.sql.catalog." + SPARK_CATALOG;
    SparkConf conf =
        new SparkConf()
            .set(
                "spark.sql.extensions",
                "org.apache.iceberg.spark.extensions.IcebergSparkSessionExtensions")
            .set("spark.sql.defaultCatalog", SPARK_CATALOG)
            .set(base, "org.apache.iceberg.spark.SparkCatalog")
            .set(base + ".type", "rest")
            .set(base + ".uri", ircUri)
            .set(base + ".cache-enabled", "false")
            .set(base + ".rest.auth.type", "oauth2")
            .set(base + ".token", oidcToken)
            .set(base + ".token-exchange-enabled", "false")
            .set("spark.locality.wait.node", "0");
    return SparkSession.builder().master("local[2]").appName(app).config(conf).getOrCreate();
  }

  /** Decodes a JWT payload without signature verification. Diagnostic aid only. */
  private static String decodeJwtPayload(String jwt) {
    String[] parts = jwt.split("\\.");
    if (parts.length < 2) {
      throw new IllegalArgumentException("Token is not a JWT (no payload segment)");
    }
    byte[] payload = Base64.getUrlDecoder().decode(parts[1]);
    return new String(payload, StandardCharsets.UTF_8);
  }
}
