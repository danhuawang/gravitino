/*
 * Copyright 2024 Datastrato Pvt Ltd.
 * This software is licensed under the Apache License version 2.
 */
package org.apache.gravitino.integration.test.oauth2.authorization;

import com.google.common.collect.Lists;
import com.google.common.collect.Maps;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.Base64;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import org.apache.gravitino.Catalog;
import org.apache.gravitino.MetadataObject;
import org.apache.gravitino.MetadataObjects;
import org.apache.gravitino.NameIdentifier;
import org.apache.gravitino.authorization.Owner;
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
 * E2E tests for grant/revoke privileges on nested (multi-level) namespaces, verified through Spark
 * via IRC with user-level OAuth2 tokens.
 *
 * <p>Covers TC-18 through TC-21 from TEST_PLAN_NESTED_NAMESPACE.md:
 *
 * <ul>
 *   <li>TC-18: Grant privilege on nested namespace — user can SELECT via Spark
 *   <li>TC-19: No privilege — user denied access via Spark
 *   <li>TC-20: Revoke privilege — user loses access in next Spark session
 *   <li>TC-21: Grant on parent namespace inherited by child — user can access deeper levels
 * </ul>
 *
 * <p>Same environment as {@code SparkIcebergRestGroupBasedAccessControlIT} (env2-oauth2-auth).
 */
@DisplayName("Spark-via-IRC Nested Namespace Authorization E2E (env2-oauth2-auth)")
public class SparkIcebergNestedNamespaceAuthorizationIT {

  private static final Logger LOG =
      LoggerFactory.getLogger(SparkIcebergNestedNamespaceAuthorizationIT.class);

  private static final String METALAKE_NAME = System.getProperty("gravitino.metalake", "test");
  private static final String CATALOG_NAME =
      System.getProperty("gravitino.irc.catalog", "catalog_1");
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

  /** 3-level nested schema used by all tests in this class. */
  private static String schemaLevel1;

  private static String schemaLevel1Level2;
  private static String schemaLevel1Level2Level3;
  private static final String TABLE_NAME = "nested_auth_tbl";

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

    // Create a 3-level nested schema for all authorization tests.
    String prefix = RandomNameUtils.genRandomName("ns_auth");
    schemaLevel1 = prefix;
    schemaLevel1Level2 = prefix + ":mid";
    schemaLevel1Level2Level3 = prefix + ":mid:deep";

    catalog
        .asSchemas()
        .createSchema(schemaLevel1Level2Level3, "nested auth test schema", Collections.emptyMap());

    // Create a fixture table in the deepest level.
    Column[] columns = {
      Column.of("id", Types.LongType.get(), "row id"),
      Column.of("val", Types.StringType.get(), "value")
    };
    catalog
        .asTableCatalog()
        .createTable(
            NameIdentifier.of(schemaLevel1Level2Level3, TABLE_NAME),
            columns,
            "nested namespace auth fixture table",
            Collections.emptyMap());

    // Insert data via admin Spark session.
    SparkSession adminSpark = newSparkSessionForToken("admin-setup", adminToken());
    try {
      adminSpark.sql(
          String.format(
              "INSERT INTO %s.%s.mid.deep.%s VALUES (1, 'alpha'), (2, 'beta')",
              SPARK_CATALOG, schemaLevel1, TABLE_NAME));
    } finally {
      adminSpark.close();
    }

    LOG.info("Setup complete: schema={}, table={}", schemaLevel1Level2Level3, TABLE_NAME);
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
    try {
      if (catalog != null) {
        catalog.asTableCatalog().dropTable(NameIdentifier.of(schemaLevel1Level2Level3, TABLE_NAME));
      }
    } catch (Exception e) {
      LOG.warn("Failed to drop fixture table", e);
    }
    try {
      if (catalog != null) {
        catalog.asSchemas().dropSchema(schemaLevel1Level2Level3, true);
        catalog.asSchemas().dropSchema(schemaLevel1Level2, true);
        catalog.asSchemas().dropSchema(schemaLevel1, true);
      }
    } catch (Exception e) {
      LOG.warn("Failed to drop fixture schemas", e);
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

  // ── TC-18: Grant privilege on nested namespace — user can SELECT ───────────

  @Test
  @DisplayName("TC-18: Grant privilege on nested namespace - Spark user can access table")
  public void testGrantPrivilegeOnNestedNamespaceUserCanSelect() throws Exception {
    String groupName = "nested_readers";
    String roleName = "r_nested_select";
    String bob = "bob_tc18";
    String bobPassword = "bob-password";

    // 1. Create Keycloak user in group.
    String groupId = keycloak.createGroup(groupName);
    createdGroupNames.add(groupName);
    String bobId = keycloak.createUser(bob, bobPassword);
    createdUsernames.add(bob);
    keycloak.addUserToGroup(bobId, groupId);

    // 2. Register in Gravitino.
    metalake.addUser(bob);
    createdMetalakeUsers.add(bob);
    metalake.addGroup(groupName);
    createdMetalakeGroups.add(groupName);

    // 3. Create role with privileges on the nested namespace.
    SecurableObject catalogObject =
        SecurableObjects.ofCatalog(CATALOG_NAME, Lists.newArrayList(Privileges.UseCatalog.allow()));
    SecurableObject schemaObject =
        SecurableObjects.ofSchema(
            catalogObject,
            schemaLevel1Level2Level3,
            Lists.newArrayList(Privileges.UseSchema.allow()));
    SecurableObject tableObject =
        SecurableObjects.ofTable(
            schemaObject, TABLE_NAME, Lists.newArrayList(Privileges.SelectTable.allow()));
    metalake.createRole(
        roleName, Maps.newHashMap(), Lists.newArrayList(catalogObject, schemaObject, tableObject));
    createdMetalakeRoles.add(roleName);

    // 4. Verify bob is denied BEFORE the role is granted.
    String tableFqn = String.format("%s.%s.mid.deep.%s", SPARK_CATALOG, schemaLevel1, TABLE_NAME);
    String bobTokenBefore = keycloak.fetchUserToken(bob, bobPassword);
    spark = newSparkSessionForToken("bob-tc18-before", bobTokenBefore);
    Assertions.assertThrows(
        Exception.class,
        () -> spark.sql("SELECT * FROM " + tableFqn).collect(),
        "bob must be denied before the role is granted to the group");
    spark.close();
    spark = null;

    // 5. Grant role to group.
    metalake.grantRolesToGroup(Lists.newArrayList(roleName), groupName);

    // 6. Obtain token for bob and create Spark session.
    String bobToken = keycloak.fetchUserToken(bob, bobPassword);
    LOG.info("Bob JWT payload (TC-18): {}", decodeJwtPayload(bobToken));
    spark = newSparkSessionForToken("bob-tc18", bobToken);

    // 7. SELECT should succeed now that the role is granted.
    List<org.apache.spark.sql.Row> rows = spark.sql("SELECT * FROM " + tableFqn).collectAsList();
    Assertions.assertEquals(2, rows.size(), "bob should see 2 rows via group role grant");
  }

  // ── TC-19: No privilege — user denied access ──────────────────────────────

  @Test
  @DisplayName("TC-19: No privilege on nested namespace - Spark user denied access")
  public void testNoPrivilegeOnNestedNamespaceUserDenied() throws Exception {
    String charlie = "charlie_tc19";
    String charliePassword = "charlie-password";

    // 1. Create Keycloak user NOT in any privileged group.
    keycloak.createUser(charlie, charliePassword);
    createdUsernames.add(charlie);

    // 2. Register in Gravitino (no role grants).
    metalake.addUser(charlie);
    createdMetalakeUsers.add(charlie);

    // 3. Obtain token for charlie and create Spark session.
    String charlieToken = keycloak.fetchUserToken(charlie, charliePassword);
    LOG.info("Charlie JWT payload (TC-19): {}", decodeJwtPayload(charlieToken));
    spark = newSparkSessionForToken("charlie-tc19", charlieToken);

    // 4. SELECT should be rejected (no privileges).
    String tableFqn = String.format("%s.%s.mid.deep.%s", SPARK_CATALOG, schemaLevel1, TABLE_NAME);
    Assertions.assertThrows(
        Exception.class,
        () -> spark.sql("SELECT * FROM " + tableFqn).collect(),
        "charlie without any role should be denied access to the nested namespace table");
    spark.close();
    spark = null;

    // 5. Set charlie as owner of the intermediate namespace (schemaLevel1Level2) and grant
    //    UseCatalog + UseSchema so the ownership-based authorization path is exercised.
    MetadataObject schemaObject =
        MetadataObjects.of(
            Arrays.asList(CATALOG_NAME, schemaLevel1Level2), MetadataObject.Type.SCHEMA);
    metalake.setOwner(schemaObject, charlie, Owner.Type.USER);

    String ownerRoleName = "r_charlie_use";
    SecurableObject catalogObject =
        SecurableObjects.ofCatalog(CATALOG_NAME, Lists.newArrayList(Privileges.UseCatalog.allow()));
    SecurableObject schemaUsable =
        SecurableObjects.ofSchema(
            catalogObject, schemaLevel1Level2, Lists.newArrayList(Privileges.UseSchema.allow()));
    metalake.createRole(
        ownerRoleName, Maps.newHashMap(), Lists.newArrayList(catalogObject, schemaUsable));
    createdMetalakeRoles.add(ownerRoleName);
    metalake.grantRolesToUser(Lists.newArrayList(ownerRoleName), charlie);

    // 6. Fresh session — charlie should now be able to SELECT via schema ownership.
    String charlieToken2 = keycloak.fetchUserToken(charlie, charliePassword);
    spark = newSparkSessionForToken("charlie-tc19-owner", charlieToken2);
    List<org.apache.spark.sql.Row> rows = spark.sql("SELECT * FROM " + tableFqn).collectAsList();
    Assertions.assertEquals(
        2, rows.size(), "charlie as schema owner should be able to SELECT the table");
  }

  // ── TC-20: Revoke privilege — user loses access ───────────────────────────

  @Test
  @DisplayName("TC-20: Revoke privilege on nested namespace - Spark user loses access")
  public void testRevokePrivilegeOnNestedNamespaceUserLosesAccess() throws Exception {
    String groupName = "revoke_readers";
    String roleName = "r_revoke_select";
    String dave = "dave_tc20";
    String davePassword = "dave-password";

    // 1. Create Keycloak user in group.
    String groupId = keycloak.createGroup(groupName);
    createdGroupNames.add(groupName);
    String daveId = keycloak.createUser(dave, davePassword);
    createdUsernames.add(dave);
    keycloak.addUserToGroup(daveId, groupId);

    // 2. Register in Gravitino.
    metalake.addUser(dave);
    createdMetalakeUsers.add(dave);
    metalake.addGroup(groupName);
    createdMetalakeGroups.add(groupName);

    // 3. Create role with privileges on the nested namespace.
    SecurableObject catalogObject =
        SecurableObjects.ofCatalog(CATALOG_NAME, Lists.newArrayList(Privileges.UseCatalog.allow()));
    SecurableObject schemaObject =
        SecurableObjects.ofSchema(
            catalogObject,
            schemaLevel1Level2Level3,
            Lists.newArrayList(Privileges.UseSchema.allow()));
    SecurableObject tableObject =
        SecurableObjects.ofTable(
            schemaObject, TABLE_NAME, Lists.newArrayList(Privileges.SelectTable.allow()));
    metalake.createRole(
        roleName, Maps.newHashMap(), Lists.newArrayList(catalogObject, schemaObject, tableObject));
    createdMetalakeRoles.add(roleName);
    metalake.grantRolesToGroup(Lists.newArrayList(roleName), groupName);

    // 4. Session 1: SELECT succeeds (primes any IRC caches).
    String daveToken1 = keycloak.fetchUserToken(dave, davePassword);
    LOG.info("Dave JWT payload (TC-20 session 1): {}", decodeJwtPayload(daveToken1));
    spark = newSparkSessionForToken("dave-tc20-s1", daveToken1);
    String tableFqn = String.format("%s.%s.mid.deep.%s", SPARK_CATALOG, schemaLevel1, TABLE_NAME);
    Assertions.assertDoesNotThrow(
        () -> spark.sql("SELECT * FROM " + tableFqn).collect(),
        "dave must be able to SELECT before revoke");
    spark.close();
    spark = null;

    // 5. Revoke the role from the group.
    metalake.revokeRolesFromGroup(Lists.newArrayList(roleName), groupName);

    // 6. Session 2: fresh token + new session — SELECT must be rejected.
    String daveToken2 = keycloak.fetchUserToken(dave, davePassword);
    LOG.info("Dave JWT payload (TC-20 session 2): {}", decodeJwtPayload(daveToken2));
    spark = newSparkSessionForToken("dave-tc20-s2", daveToken2);
    Assertions.assertThrows(
        Exception.class,
        () -> spark.sql("SELECT * FROM " + tableFqn).collect(),
        "dave must be denied after revoke; IRC cache must not resurrect stale ALLOW");
  }

  // ── TC-21: Grant on parent namespace inherited by child ───────────────────

  @Test
  @DisplayName(
      "TC-21: Grant on parent namespace inherited by child - Spark user can access deeper levels")
  public void testGrantOnParentNamespaceInheritedByChild() throws Exception {
    String groupName = "inherit_readers";
    String roleName = "r_parent_inherit";
    String eve = "eve_tc21";
    String evePassword = "eve-password";

    // 1. Create Keycloak user in group.
    String groupId = keycloak.createGroup(groupName);
    createdGroupNames.add(groupName);
    String eveId = keycloak.createUser(eve, evePassword);
    createdUsernames.add(eve);
    keycloak.addUserToGroup(eveId, groupId);

    // 2. Register in Gravitino.
    metalake.addUser(eve);
    createdMetalakeUsers.add(eve);
    metalake.addGroup(groupName);
    createdMetalakeGroups.add(groupName);

    // 3. Create role with privileges on the PARENT namespace (schemaLevel1) only,
    //    NOT on the deeper level (schemaLevel1Level2Level3).
    //    Privilege inheritance should allow access to tables in child namespaces.
    SecurableObject catalogObject =
        SecurableObjects.ofCatalog(CATALOG_NAME, Lists.newArrayList(Privileges.UseCatalog.allow()));
    SecurableObject parentSchemaObject =
        SecurableObjects.ofSchema(
            catalogObject,
            schemaLevel1,
            Lists.newArrayList(Privileges.UseSchema.allow(), Privileges.SelectTable.allow()));
    metalake.createRole(
        roleName, Maps.newHashMap(), Lists.newArrayList(catalogObject, parentSchemaObject));
    createdMetalakeRoles.add(roleName);
    metalake.grantRolesToGroup(Lists.newArrayList(roleName), groupName);

    // 4. Obtain token for eve and create Spark session.
    String eveToken = keycloak.fetchUserToken(eve, evePassword);
    LOG.info("Eve JWT payload (TC-21): {}", decodeJwtPayload(eveToken));
    spark = newSparkSessionForToken("eve-tc21", eveToken);

    // 5. SELECT on the table in the CHILD namespace should succeed via inheritance.
    String tableFqn = String.format("%s.%s.mid.deep.%s", SPARK_CATALOG, schemaLevel1, TABLE_NAME);
    Assertions.assertDoesNotThrow(
        () -> spark.sql("SELECT * FROM " + tableFqn).collect(),
        "eve should access the child namespace table via privilege inherited from parent schema");

    // 6. Verify data is correct.
    List<org.apache.spark.sql.Row> rows = spark.sql("SELECT * FROM " + tableFqn).collectAsList();
    Assertions.assertEquals(2, rows.size(), "eve should see 2 rows via inherited parent privilege");
    spark.close();
    spark = null;

    // 7. Grant a DENY on the intermediate namespace (schemaLevel1Level2) to override inheritance.
    String denyRoleName = "r_parent_deny";
    SecurableObject denyCatalogObject =
        SecurableObjects.ofCatalog(CATALOG_NAME, Lists.newArrayList(Privileges.UseCatalog.allow()));
    SecurableObject denySchemaObject =
        SecurableObjects.ofSchema(
            denyCatalogObject,
            schemaLevel1Level2,
            Lists.newArrayList(Privileges.SelectTable.deny()));
    metalake.createRole(
        denyRoleName, Maps.newHashMap(), Lists.newArrayList(denyCatalogObject, denySchemaObject));
    createdMetalakeRoles.add(denyRoleName);
    metalake.grantRolesToGroup(Lists.newArrayList(denyRoleName), groupName);

    // 8. Fresh session — eve should now be denied due to the DENY on the intermediate namespace.
    String eveToken2 = keycloak.fetchUserToken(eve, evePassword);
    spark = newSparkSessionForToken("eve-tc21-deny", eveToken2);
    Assertions.assertThrows(
        Exception.class,
        () -> spark.sql("SELECT * FROM " + tableFqn).collect(),
        "eve must be denied after SelectTable.deny() is granted on the intermediate namespace");
  }

  // ── Helpers ───────────────────────────────────────────────────────────────

  private static String requireEnv(String name) {
    String value = System.getenv(name);
    if (value == null || value.isEmpty()) {
      throw new IllegalStateException(
          "Required environment variable not set: " + name + ". Run setup-kind-env.sh first.");
    }
    return value;
  }

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
   * Fetches a service-account token via the client_credentials grant. The metalake owner is
   * "service-account-postman-client" (the identity behind the client_credentials grant), NOT the
   * Keycloak "admin" user. Using the wrong principal causes ForbiddenException on table operations.
   */
  private static String adminToken() {
    try {
      return fetchClientCredentialsToken();
    } catch (Exception e) {
      throw new RuntimeException("Failed to fetch service-account admin token", e);
    }
  }

  /**
   * Performs a client_credentials grant to obtain a service-account access token. This matches the
   * identity used by {@link #adminTokenProvider()} (service-account-postman-client).
   */
  private static String fetchClientCredentialsToken() throws Exception {
    String tokenEndpoint = keycloakBaseUrl + "/" + keycloakTokenPath;
    StringBuilder body = new StringBuilder();
    body.append("grant_type=client_credentials");
    body.append("&client_id=")
        .append(java.net.URLEncoder.encode(keycloakClientId, StandardCharsets.UTF_8));
    body.append("&client_secret=")
        .append(java.net.URLEncoder.encode(keycloakClientSecret, StandardCharsets.UTF_8));
    body.append("&scope=")
        .append(java.net.URLEncoder.encode(keycloakScope, StandardCharsets.UTF_8));

    java.net.http.HttpRequest request =
        java.net.http.HttpRequest.newBuilder()
            .uri(java.net.URI.create(tokenEndpoint))
            .header("Content-Type", "application/x-www-form-urlencoded")
            .POST(java.net.http.HttpRequest.BodyPublishers.ofString(body.toString()))
            .build();
    java.net.http.HttpResponse<String> response =
        java.net.http.HttpClient.newHttpClient()
            .send(request, java.net.http.HttpResponse.BodyHandlers.ofString());
    if (response.statusCode() / 100 != 2) {
      throw new RuntimeException(
          "client_credentials grant failed: HTTP " + response.statusCode() + " " + response.body());
    }
    // Minimal JSON extraction for access_token field
    String json = response.body();
    int idx = json.indexOf("\"access_token\"");
    if (idx < 0) {
      throw new RuntimeException("No access_token in response: " + json);
    }
    int colonIdx = json.indexOf(':', idx);
    int startQuote = json.indexOf('"', colonIdx + 1);
    int endQuote = json.indexOf('"', startQuote + 1);
    return json.substring(startQuote + 1, endQuote);
  }

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

  private static String decodeJwtPayload(String jwt) {
    String[] parts = jwt.split("\\.");
    if (parts.length < 2) {
      throw new IllegalArgumentException("Token is not a JWT (no payload segment)");
    }
    byte[] payload = Base64.getUrlDecoder().decode(parts[1]);
    return new String(payload, StandardCharsets.UTF_8);
  }
}
