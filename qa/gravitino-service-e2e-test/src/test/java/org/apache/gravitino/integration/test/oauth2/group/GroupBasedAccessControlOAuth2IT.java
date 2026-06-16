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
import java.util.Map;
import java.util.Set;
import org.apache.gravitino.Catalog;
import org.apache.gravitino.MetadataObject;
import org.apache.gravitino.MetadataObjects;
import org.apache.gravitino.NameIdentifier;
import org.apache.gravitino.SchemaChange;
import org.apache.gravitino.authorization.Group;
import org.apache.gravitino.authorization.Owner;
import org.apache.gravitino.authorization.Privileges;
import org.apache.gravitino.authorization.SecurableObject;
import org.apache.gravitino.authorization.SecurableObjects;
import org.apache.gravitino.client.DefaultOAuth2TokenProvider;
import org.apache.gravitino.client.GravitinoAdminClient;
import org.apache.gravitino.client.GravitinoClient;
import org.apache.gravitino.client.GravitinoMetalake;
import org.apache.gravitino.client.OAuth2TokenProvider;
import org.apache.gravitino.exceptions.ForbiddenException;
import org.apache.gravitino.exceptions.NoSuchGroupException;
import org.apache.gravitino.qa.common.oauth2.KeycloakAdminHelper;
import org.apache.gravitino.qa.common.oauth2.PasswordGrantTokenProvider;
import org.apache.gravitino.rel.Column;
import org.apache.gravitino.rel.types.Types;
import org.apache.gravitino.utils.RandomNameUtils;
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
 * E2E tests for the REST-API surface of group-based access control under OAuth2/OIDC authentication
 * (EPIC apache/gravitino#10404). OAuth2 counterpart of {@link
 * org.apache.gravitino.integration.test.authorization.group.GroupBasedAccessControlIT}, bound to
 * Env 2 oauth2-auth ({@code qa/k8s/helm-values/env2-oauth2-auth-values.yaml}).
 *
 * <p>Group membership is sourced from the JWT {@code groups} claim — tests do <b>not</b> call
 * {@code metalake.addUserToGroup}. Each test self-bootstraps Keycloak users / groups via {@link
 * KeycloakAdminHelper} and tears them down in {@link #cleanupKeycloak()}. The bootstrap admin uses
 * the {@code postman-client} service account ({@code client_credentials}); end-user clients use
 * {@link PasswordGrantTokenProvider} with a JWT fetched <i>after</i> any Keycloak group change so
 * the claim reflects current state.
 *
 * <p>Covers T1–T11 (mirrors the simple-auth original, plus T2/T3 — the OAuth2 group-claim
 * extraction path from subtask apache/gravitino#10410).
 *
 * <p>Required env vars: {@code OAUTH2_SERVER_URI}, {@code OAUTH2_CLIENT_ID}, {@code
 * OAUTH2_CLIENT_SECRET}. Optional: {@code OAUTH2_REALM} (default {@code myrealm}), {@code
 * OAUTH2_TOKEN_PATH}, {@code OAUTH2_SCOPE} (default {@code openid profile email}), {@code
 * OAUTH2_ADMIN_USER} / {@code OAUTH2_ADMIN_PASSWORD} (default {@code admin}/{@code admin}).
 */
@DisplayName("Group-Based Access Control REST E2E (env2-oauth2-auth)")
public class GroupBasedAccessControlOAuth2IT {

  private static final Logger LOG = LoggerFactory.getLogger(GroupBasedAccessControlOAuth2IT.class);

  private static final String METALAKE_NAME = RandomNameUtils.genRandomName("metalake");
  private static final String CATALOG_NAME = "hive_catalog";
  private static final String SCHEMA_NAME = RandomNameUtils.genRandomName("test_schema");
  private static final String TABLE_NAME = "tbl_a";

  private static final String DEFAULT_SCOPE = "openid profile email";
  private static final String DEFAULT_REALM = "myrealm";
  private static final String DEFAULT_ADMIN_USER = "admin";
  private static final String DEFAULT_ADMIN_PASSWORD = "admin";

  // Keycloak / Gravitino wiring resolved in @BeforeAll.
  private static String gravitinoUri;
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

  // Names of Keycloak entities created by the *current* test, removed in @AfterEach.
  private final Set<String> createdUsernames = new HashSet<>();
  private final Set<String> createdGroupNames = new HashSet<>();

  @BeforeAll
  public static void startIntegrationTest() throws Exception {
    gravitinoUri = System.getProperty("gravitino.uri", "http://localhost:30090");
    String hiveMetastoreUri = System.getProperty("hive.metastore.uri", "thrift://localhost:30083");

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

    // Keycloak admin helper — admin tokens via master/admin-cli (postman-client has no
    // realm-management roles); end-user tokens still use postman-client.
    keycloak =
        KeycloakAdminHelper.builder()
            .withBaseUrl(keycloakBaseUrl)
            .withRealm(keycloakRealm)
            .withMasterAdminPassword(keycloakAdminUser, keycloakAdminPassword)
            .withUserTokenClient(keycloakClientId, keycloakClientSecret)
            .build();
    keycloak.authenticate();

    // The seeded `myrealm` does not bake a Group Membership mapper into `postman-client`,
    // so end-user tokens via the password grant carry no `groups` claim by default.
    // Provision it here (idempotent) so subtask #10410's group-claim extraction has data.
    keycloak.ensureGroupsMapperOnClient(keycloakClientId);

    adminClient =
        GravitinoAdminClient.builder(gravitinoUri).withOAuth(adminTokenProvider()).build();
    metalake =
        adminClient.createMetalake(METALAKE_NAME, "metalake comment", Collections.emptyMap());

    Map<String, String> catalogProperties = Maps.newHashMap();
    catalogProperties.put("metastore.uris", hiveMetastoreUri);
    catalog =
        metalake.createCatalog(
            CATALOG_NAME, Catalog.Type.RELATIONAL, "hive", "comment", catalogProperties);

    catalog.asSchemas().createSchema(SCHEMA_NAME, "comment", Collections.emptyMap());

    // Single-column Hive table; authorization only resolves the SecurableObject, not data.
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
    createdUsernames.clear();
    createdGroupNames.clear();
  }

  @AfterEach
  public void cleanupKeycloak() {
    // Drop Gravitino-side state first (users → groups → roles). Users come first because
    // some pre-checks reject when a User entity references a no-longer-existing group.
    try {
      for (String userName : metalake.listUserNames()) {
        try {
          metalake.removeUser(userName);
        } catch (Exception e) {
          LOG.warn("Failed to remove Gravitino user '{}': {}", userName, e.getMessage());
        }
      }
      for (String groupName : metalake.listGroupNames()) {
        try {
          metalake.removeGroup(groupName);
        } catch (Exception e) {
          LOG.warn("Failed to remove Gravitino group '{}': {}", groupName, e.getMessage());
        }
      }
      for (String roleName : metalake.listRoleNames()) {
        try {
          metalake.deleteRole(roleName);
        } catch (Exception e) {
          LOG.warn("Failed to delete Gravitino role '{}': {}", roleName, e.getMessage());
        }
      }
    } catch (Exception e) {
      LOG.warn("Failed to enumerate Gravitino users/groups/roles for cleanup", e);
    }

    // Drop Keycloak users / groups created by this test so JWT identities don't leak.
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
        catalog.asTableCatalog().dropTable(NameIdentifier.of(SCHEMA_NAME, TABLE_NAME));
      }
    } catch (Exception e) {
      LOG.warn("Failed to drop fixture table '{}.{}'", SCHEMA_NAME, TABLE_NAME, e);
    }
    try {
      if (adminClient != null) {
        // Force-drop: catalog/schema were created by this suite.
        adminClient.dropMetalake(METALAKE_NAME, true);
      }
    } catch (Exception e) {
      LOG.warn("Failed to drop metalake '{}'", METALAKE_NAME, e);
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
  //  T1 — Add, list and remove groups
  // ──────────────────────────────────────────────────────────────────────────
  @Test
  @DisplayName("T1: Add, list and remove groups via REST (OAuth2)")
  public void testGroupLifecycle() {
    String groupName = "dev_team";

    Group added = metalake.addGroup(groupName);
    Assertions.assertEquals(groupName, added.name());
    Assertions.assertTrue(added.roles().isEmpty());

    Assertions.assertTrue(Lists.newArrayList(metalake.listGroupNames()).contains(groupName));

    Group fetched = metalake.getGroup(groupName);
    Assertions.assertEquals(groupName, fetched.name());
    Assertions.assertTrue(fetched.roles().isEmpty());

    Assertions.assertTrue(metalake.removeGroup(groupName));
    Assertions.assertThrows(NoSuchGroupException.class, () -> metalake.getGroup(groupName));
  }

  // ──────────────────────────────────────────────────────────────────────────
  //  T2 — JWT `groups` claim populates authorization context
  // ──────────────────────────────────────────────────────────────────────────
  @Test
  @DisplayName("T2: JWT `groups` claim is extracted, user is authorized via her group's role")
  public void testJwtGroupsClaimExtracted() throws Exception {
    String groupName = "dev_team";
    String roleName = "r_select";
    String alice = "alice";
    String alicePassword = "alice-password";

    // Keycloak: alice joins dev_team so her JWT carries `"groups":["dev_team"]`.
    String groupId = keycloak.createGroup(groupName);
    createdGroupNames.add(groupName);
    String aliceId = keycloak.createUser(alice, alicePassword);
    createdUsernames.add(alice);
    keycloak.addUserToGroup(aliceId, groupId);

    // Gravitino: principal must exist so authorization resolves it; group must exist so
    // the role can be granted to it. No addUserToGroup — membership comes from the JWT.
    metalake.addUser(alice);
    metalake.addGroup(groupName);

    // Role with USE_CATALOG + USE_SCHEMA + SELECT_TABLE on tbl_a, granted to dev_team.
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
    metalake.grantRolesToGroup(Lists.newArrayList(roleName), groupName);

    // Mint token after the membership change so the JWT reflects it; assert the claim
    // before relying on it.
    String aliceToken = keycloak.fetchUserToken(alice, alicePassword);
    String aliceJwtPayload = decodeJwtPayload(aliceToken);
    LOG.info("Alice JWT payload (T2): {}", aliceJwtPayload);
    Assertions.assertTrue(
        aliceJwtPayload.contains("\"groups\""),
        "Alice's JWT is missing the `groups` claim. Realm/client mapper is misconfigured. "
            + "Payload: "
            + aliceJwtPayload);
    Assertions.assertTrue(
        aliceJwtPayload.contains("dev_team"),
        "Alice's JWT `groups` claim does not contain 'dev_team'. Payload: " + aliceJwtPayload);

    GravitinoClient aliceClient =
        GravitinoClient.builder(gravitinoUri)
            .withMetalake(METALAKE_NAME)
            .withOAuth(new PasswordGrantTokenProvider(aliceToken))
            .build();
    try {
      Assertions.assertDoesNotThrow(
          () ->
              aliceClient
                  .loadMetalake(METALAKE_NAME)
                  .loadCatalog(CATALOG_NAME)
                  .asTableCatalog()
                  .loadTable(NameIdentifier.of(SCHEMA_NAME, TABLE_NAME)));
    } finally {
      aliceClient.close();
    }
  }

  // ──────────────────────────────────────────────────────────────────────────
  //  T3 — JWT without group memberships → no group-derived privileges
  // ──────────────────────────────────────────────────────────────────────────
  @Test
  @DisplayName("T3: User whose JWT has no `groups` membership gets none of the group's privileges")
  public void testJwtWithoutGroupsClaimDenied() throws Exception {
    String groupName = "dev_team";
    String roleName = "r_select";
    String eve = "eve";
    String evePassword = "eve-password";

    // Negative counterpart of T2: eve is created but NOT added to dev_team. The
    // realm-level group-membership mapper emits actual memberships only, so eve's JWT
    // carries an empty/absent `groups` claim.
    keycloak.createGroup(groupName);
    createdGroupNames.add(groupName);
    keycloak.createUser(eve, evePassword);
    createdUsernames.add(eve);
    // Intentionally no addUserToGroup: eve is a non-member.

    // Gravitino: principal exists, group exists; eve is NOT in the group — membership
    // comes from the JWT only.
    metalake.addUser(eve);
    metalake.addGroup(groupName);

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
    metalake.grantRolesToGroup(Lists.newArrayList(roleName), groupName);

    // Diagnostic: payload must NOT contain `dev_team`, otherwise T3 silently degenerates
    // into a copy of T2.
    String eveToken = keycloak.fetchUserToken(eve, evePassword);
    String eveJwtPayload = decodeJwtPayload(eveToken);
    LOG.info("Eve JWT payload (T3): {}", eveJwtPayload);
    Assertions.assertFalse(
        eveJwtPayload.contains("dev_team"),
        "Eve's JWT must not carry membership in 'dev_team' for T3 to be meaningful. "
            + "Payload: "
            + eveJwtPayload);

    // Empty groups claim → no group-derived privilege → ForbiddenException.
    GravitinoClient eveClient =
        GravitinoClient.builder(gravitinoUri)
            .withMetalake(METALAKE_NAME)
            .withOAuth(new PasswordGrantTokenProvider(eveToken))
            .build();
    try {
      Assertions.assertThrows(
          ForbiddenException.class,
          () ->
              eveClient
                  .loadMetalake(METALAKE_NAME)
                  .loadCatalog(CATALOG_NAME)
                  .asTableCatalog()
                  .loadTable(NameIdentifier.of(SCHEMA_NAME, TABLE_NAME)));
    } finally {
      eveClient.close();
    }
  }

  // ──────────────────────────────────────────────────────────────────────────
  //  T4 — Set schema owner = GROUP, group member can ALTER
  // ──────────────────────────────────────────────────────────────────────────
  @Test
  @DisplayName("T4: Schema owner of type GROUP, group member can alter the schema (OAuth2)")
  public void testSchemaOwnerGroup() throws Exception {
    String groupName = "dev_team";
    String alice = "alice";
    String alicePassword = "alice-password";
    String eve = "eve";
    String evePassword = "eve-password";

    // Keycloak: alice joins dev_team, eve does not.
    String groupId = keycloak.createGroup(groupName);
    createdGroupNames.add(groupName);
    String aliceId = keycloak.createUser(alice, alicePassword);
    createdUsernames.add(alice);
    keycloak.addUserToGroup(aliceId, groupId);
    keycloak.createUser(eve, evePassword);
    createdUsernames.add(eve);

    metalake.addUser(alice);
    metalake.addUser(eve);
    metalake.addGroup(groupName);

    // alterSchema = ANY(OWNER, METALAKE, CATALOG) || SCHEMA_OWNER_WITH_USE_CATALOG, where
    // SCHEMA_OWNER_WITH_USE_CATALOG := SCHEMA::OWNER && USE_CATALOG. Schema-as-group-owner
    // alone is NOT enough; the request also needs USE_CATALOG (which loadCatalog itself
    // requires). Grant USE_CATALOG to dev_team alongside the ownership transfer so members
    // can both reach and alter the schema.
    MetadataObject schemaObject =
        MetadataObjects.of(
            Lists.newArrayList(CATALOG_NAME, SCHEMA_NAME), MetadataObject.Type.SCHEMA);
    metalake.setOwner(schemaObject, groupName, Owner.Type.GROUP);
    Owner owner = metalake.getOwner(schemaObject).orElseThrow();
    Assertions.assertEquals(groupName, owner.name());
    Assertions.assertEquals(Owner.Type.GROUP, owner.type());

    String useCatalogRoleName = "r_use_catalog";
    SecurableObject useCatalogObject =
        SecurableObjects.ofCatalog(CATALOG_NAME, Lists.newArrayList(Privileges.UseCatalog.allow()));
    metalake.createRole(
        useCatalogRoleName, Maps.newHashMap(), Lists.newArrayList(useCatalogObject));
    metalake.grantRolesToGroup(Lists.newArrayList(useCatalogRoleName), groupName);

    // alice (group member): alterSchema → success.
    String aliceToken = keycloak.fetchUserToken(alice, alicePassword);
    String aliceJwtPayload = decodeJwtPayload(aliceToken);
    LOG.info("Alice JWT payload (T4): {}", aliceJwtPayload);
    Assertions.assertTrue(
        aliceJwtPayload.contains("dev_team"),
        "Alice's JWT must place her in 'dev_team' for T4 to be meaningful. Payload: "
            + aliceJwtPayload);

    GravitinoClient aliceClient =
        GravitinoClient.builder(gravitinoUri)
            .withMetalake(METALAKE_NAME)
            .withOAuth(new PasswordGrantTokenProvider(aliceToken))
            .build();
    try {
      Assertions.assertDoesNotThrow(
          () ->
              aliceClient
                  .loadMetalake(METALAKE_NAME)
                  .loadCatalog(CATALOG_NAME)
                  .asSchemas()
                  .alterSchema(SCHEMA_NAME /* SchemaChange.setProperty("k", "v") */));
    } finally {
      aliceClient.close();
    }

    // eve (non-member): alterSchema → ForbiddenException. Without USE_CATALOG she is
    // rejected at loadCatalog, before alterSchema's check runs — same exception type,
    // same outcome.
    String eveToken = keycloak.fetchUserToken(eve, evePassword);
    String eveJwtPayload = decodeJwtPayload(eveToken);
    LOG.info("Eve JWT payload (T4): {}", eveJwtPayload);
    Assertions.assertFalse(
        eveJwtPayload.contains("dev_team"),
        "Eve's JWT must not place her in 'dev_team' for T4 to be meaningful. Payload: "
            + eveJwtPayload);

    GravitinoClient eveClient =
        GravitinoClient.builder(gravitinoUri)
            .withMetalake(METALAKE_NAME)
            .withOAuth(new PasswordGrantTokenProvider(eveToken))
            .build();
    try {
      Assertions.assertThrows(
          ForbiddenException.class,
          () ->
              eveClient
                  .loadMetalake(METALAKE_NAME)
                  .loadCatalog(CATALOG_NAME)
                  .asSchemas()
                  .alterSchema(SCHEMA_NAME /* SchemaChange.setProperty("k", "v") */));
    } finally {
      eveClient.close();
    }

    // Add eve to dev_team in Keycloak, mint a fresh token (old token's claim is frozen).
    // The next alterSchema must succeed via the JWT-derived membership — no metalake-side
    // addUserToGroup needed.
    String eveId = keycloak.getUserId(eve);
    keycloak.addUserToGroup(eveId, groupId);
    String eveTokenAfterJoin = keycloak.fetchUserToken(eve, evePassword);
    String eveJwtPayloadAfterJoin = decodeJwtPayload(eveTokenAfterJoin);
    LOG.info("Eve JWT payload after joining dev_team (T4): {}", eveJwtPayloadAfterJoin);
    Assertions.assertTrue(
        eveJwtPayloadAfterJoin.contains("dev_team"),
        "Eve's refreshed JWT must place her in 'dev_team'. Payload: " + eveJwtPayloadAfterJoin);

    GravitinoClient eveClientAfterJoin =
        GravitinoClient.builder(gravitinoUri)
            .withMetalake(METALAKE_NAME)
            .withOAuth(new PasswordGrantTokenProvider(eveTokenAfterJoin))
            .build();
    try {
      Assertions.assertDoesNotThrow(
          () ->
              eveClientAfterJoin
                  .loadMetalake(METALAKE_NAME)
                  .loadCatalog(CATALOG_NAME)
                  .asSchemas()
                  .alterSchema(SCHEMA_NAME /* SchemaChange.setProperty("k", "v") */));
    } finally {
      eveClientAfterJoin.close();
    }
  }

  // ──────────────────────────────────────────────────────────────────────────
  //  T5 — Role owner = GROUP, only members can drop the role
  // ──────────────────────────────────────────────────────────────────────────
  @Test
  @DisplayName("T5: Role owner of type GROUP, only members can drop the role (OAuth2)")
  public void testRoleOwnerGroup() throws Exception {
    String groupName = "dev_team";
    String alice = "alice";
    String alicePassword = "alice-password";
    String eve = "eve";
    String evePassword = "eve-password";
    String roleName = "r1";

    // Keycloak: only alice joins dev_team; eve is a non-member.
    String groupId = keycloak.createGroup(groupName);
    createdGroupNames.add(groupName);
    String aliceId = keycloak.createUser(alice, alicePassword);
    createdUsernames.add(alice);
    keycloak.addUserToGroup(aliceId, groupId);
    keycloak.createUser(eve, evePassword);
    createdUsernames.add(eve);

    metalake.addUser(alice);
    metalake.addUser(eve);
    metalake.addGroup(groupName);

    // deleteRole = METALAKE::OWNER || ROLE::OWNER. ROLE-as-group-owner is sufficient on
    // its own — no extra USE_CATALOG-style grant required (unlike alterSchema in T4).
    metalake.createRole(roleName, Maps.newHashMap(), Collections.emptyList());
    MetadataObject roleObject = MetadataObjects.of(null, roleName, MetadataObject.Type.ROLE);
    metalake.setOwner(roleObject, groupName, Owner.Type.GROUP);
    Owner owner = metalake.getOwner(roleObject).orElseThrow();
    Assertions.assertEquals(groupName, owner.name());
    Assertions.assertEquals(Owner.Type.GROUP, owner.type());

    // alice (group member): deleteRole → success.
    String aliceToken = keycloak.fetchUserToken(alice, alicePassword);
    String aliceJwtPayload = decodeJwtPayload(aliceToken);
    LOG.info("Alice JWT payload (T5): {}", aliceJwtPayload);
    Assertions.assertTrue(
        aliceJwtPayload.contains("dev_team"),
        "Alice's JWT must place her in 'dev_team' for T5 to be meaningful. Payload: "
            + aliceJwtPayload);

    GravitinoClient aliceClient =
        GravitinoClient.builder(gravitinoUri)
            .withMetalake(METALAKE_NAME)
            .withOAuth(new PasswordGrantTokenProvider(aliceToken))
            .build();
    try {
      Assertions.assertTrue(aliceClient.loadMetalake(METALAKE_NAME).deleteRole(roleName));
    } finally {
      aliceClient.close();
    }

    // Recreate the role + transfer to dev_team again for the negative case so eve's
    // failure is attributable to ownership, not to the role being already gone.
    metalake.createRole(roleName, Maps.newHashMap(), Collections.emptyList());
    metalake.setOwner(
        MetadataObjects.of(null, roleName, MetadataObject.Type.ROLE), groupName, Owner.Type.GROUP);

    String eveToken = keycloak.fetchUserToken(eve, evePassword);
    String eveJwtPayload = decodeJwtPayload(eveToken);
    LOG.info("Eve JWT payload (T5): {}", eveJwtPayload);
    Assertions.assertFalse(
        eveJwtPayload.contains("dev_team"),
        "Eve's JWT must not place her in 'dev_team' for T5 to be meaningful. Payload: "
            + eveJwtPayload);

    GravitinoClient eveClient =
        GravitinoClient.builder(gravitinoUri)
            .withMetalake(METALAKE_NAME)
            .withOAuth(new PasswordGrantTokenProvider(eveToken))
            .build();
    try {
      Assertions.assertThrows(
          ForbiddenException.class,
          () -> eveClient.loadMetalake(METALAKE_NAME).deleteRole(roleName));
    } finally {
      eveClient.close();
    }
  }

  // ──────────────────────────────────────────────────────────────────────────
  //  T6 — Switch owner USER → GROUP → USER, previous-owner privileges drop atomically
  // ──────────────────────────────────────────────────────────────────────────
  @Test
  @DisplayName("T6: Switching owner USER → GROUP → USER drops previous owner privileges (OAuth2)")
  public void testOwnerTypeSwitch() throws Exception {
    String groupName = "dev_team";
    String alice = "alice";
    String alicePassword = "alice-password";
    String schemaName2 = RandomNameUtils.genRandomName("s2");

    // Keycloak: alice exists but does NOT join the group initially.
    String groupId = keycloak.createGroup(groupName);
    createdGroupNames.add(groupName);
    String aliceId = keycloak.createUser(alice, alicePassword);
    createdUsernames.add(alice);

    // Dedicated schema — T6 mutates ownership four times; isolating it from the shared
    // SCHEMA_NAME prevents owner-state leakage to other tests.
    metalake.addUser(alice);
    metalake.addGroup(groupName);
    catalog.asSchemas().createSchema(schemaName2, "comment", Collections.emptyMap());

    try {
      MetadataObject schemaObject =
          MetadataObjects.of(
              Lists.newArrayList(CATALOG_NAME, schemaName2), MetadataObject.Type.SCHEMA);

      // Grant USE_CATALOG directly to alice (not via the group) so it stays available
      // across all four ownership transitions, isolating the contract under test from
      // the unrelated USE_CATALOG check (see T4).
      String useCatalogRoleAlice = "r_use_catalog_alice";
      SecurableObject useCatalogObject =
          SecurableObjects.ofCatalog(
              CATALOG_NAME, Lists.newArrayList(Privileges.UseCatalog.allow()));
      metalake.createRole(
          useCatalogRoleAlice, Maps.newHashMap(), Lists.newArrayList(useCatalogObject));
      metalake.grantRolesToUser(Lists.newArrayList(useCatalogRoleAlice), alice);

      // Step 1: owner = alice (USER). alterSchema via SCHEMA::OWNER && USE_CATALOG.
      metalake.setOwner(schemaObject, alice, Owner.Type.USER);

      String aliceTokenStep1 = keycloak.fetchUserToken(alice, alicePassword);
      LOG.info("Alice JWT payload (T6 step 1): {}", decodeJwtPayload(aliceTokenStep1));
      GravitinoClient aliceClientStep1 =
          GravitinoClient.builder(gravitinoUri)
              .withMetalake(METALAKE_NAME)
              .withOAuth(new PasswordGrantTokenProvider(aliceTokenStep1))
              .build();
      try {
        Assertions.assertDoesNotThrow(
            () ->
                aliceClientStep1
                    .loadMetalake(METALAKE_NAME)
                    .loadCatalog(CATALOG_NAME)
                    .asSchemas()
                    .alterSchema(schemaName2, SchemaChange.setProperty("k", "v1")));
      } finally {
        aliceClientStep1.close();
      }

      // Step 2: owner = dev_team (GROUP), alice ∉ dev_team. The previous USER ownership
      // must be replaced atomically; alice's ex-owner privileges must vanish. She still
      // has USE_CATALOG, so the rejection lands at the schema-owner check, not earlier.
      metalake.setOwner(schemaObject, groupName, Owner.Type.GROUP);

      String aliceTokenStep2 = keycloak.fetchUserToken(alice, alicePassword);
      String aliceJwtStep2 = decodeJwtPayload(aliceTokenStep2);
      LOG.info("Alice JWT payload (T6 step 2): {}", aliceJwtStep2);
      Assertions.assertFalse(
          aliceJwtStep2.contains("dev_team"),
          "Alice's JWT must not place her in 'dev_team' before joining (T6 step 2). Payload: "
              + aliceJwtStep2);

      GravitinoClient aliceClientStep2 =
          GravitinoClient.builder(gravitinoUri)
              .withMetalake(METALAKE_NAME)
              .withOAuth(new PasswordGrantTokenProvider(aliceTokenStep2))
              .build();
      try {
        Assertions.assertThrows(
            ForbiddenException.class,
            () ->
                aliceClientStep2
                    .loadMetalake(METALAKE_NAME)
                    .loadCatalog(CATALOG_NAME)
                    .asSchemas()
                    .alterSchema(schemaName2, SchemaChange.setProperty("k", "v2")));
      } finally {
        aliceClientStep2.close();
      }

      // Step 3: add alice to dev_team in Keycloak, mint a fresh token. alterSchema now
      // succeeds via group-derived schema ownership.
      keycloak.addUserToGroup(aliceId, groupId);
      String aliceTokenStep3 = keycloak.fetchUserToken(alice, alicePassword);
      String aliceJwtStep3 = decodeJwtPayload(aliceTokenStep3);
      LOG.info("Alice JWT payload (T6 step 3): {}", aliceJwtStep3);
      Assertions.assertTrue(
          aliceJwtStep3.contains("dev_team"),
          "Alice's JWT must place her in 'dev_team' after joining (T6 step 3). Payload: "
              + aliceJwtStep3);

      GravitinoClient aliceClientStep3 =
          GravitinoClient.builder(gravitinoUri)
              .withMetalake(METALAKE_NAME)
              .withOAuth(new PasswordGrantTokenProvider(aliceTokenStep3))
              .build();
      try {
        Assertions.assertDoesNotThrow(
            () ->
                aliceClientStep3
                    .loadMetalake(METALAKE_NAME)
                    .loadCatalog(CATALOG_NAME)
                    .asSchemas()
                    .alterSchema(schemaName2, SchemaChange.setProperty("k", "v3")));
      } finally {
        aliceClientStep3.close();
      }

      // Step 4: owner back to alice (USER). Reuse step-3 token (group membership
      // irrelevant once owner is USER). Verify the overwrite is clean and getOwner
      // round-trips name + type.
      metalake.setOwner(schemaObject, alice, Owner.Type.USER);
      Owner finalOwner = metalake.getOwner(schemaObject).orElseThrow();
      Assertions.assertEquals(alice, finalOwner.name());
      Assertions.assertEquals(Owner.Type.USER, finalOwner.type());

      GravitinoClient aliceClientStep4 =
          GravitinoClient.builder(gravitinoUri)
              .withMetalake(METALAKE_NAME)
              .withOAuth(new PasswordGrantTokenProvider(aliceTokenStep3))
              .build();
      try {
        Assertions.assertDoesNotThrow(
            () ->
                aliceClientStep4
                    .loadMetalake(METALAKE_NAME)
                    .loadCatalog(CATALOG_NAME)
                    .asSchemas()
                    .alterSchema(schemaName2, SchemaChange.setProperty("k", "v4")));
      } finally {
        aliceClientStep4.close();
      }
    } finally {
      try {
        catalog.asSchemas().dropSchema(schemaName2, true);
      } catch (Exception e) {
        LOG.warn("Failed to drop T6 schema '{}'", schemaName2, e);
      }
    }
  }

  // ──────────────────────────────────────────────────────────────────────────
  //  T7 — grantRolesToGroup → all current members inherit privileges
  // ──────────────────────────────────────────────────────────────────────────
  @Test
  @DisplayName("T7: grantRolesToGroup, every current member inherits the role (OAuth2)")
  public void testGrantRolesToGroup() throws Exception {
    String groupName = "dev_team";
    String roleName = "r_select";
    String alice = "alice";
    String alicePassword = "alice-password";
    String bob = "bob";
    String bobPassword = "bob-password";
    String eve = "eve";
    String evePassword = "eve-password";

    // Keycloak: alice + bob join dev_team; eve does not. Tri-partite identity model
    // (member / member / non-member).
    String groupId = keycloak.createGroup(groupName);
    createdGroupNames.add(groupName);
    String aliceId = keycloak.createUser(alice, alicePassword);
    createdUsernames.add(alice);
    keycloak.addUserToGroup(aliceId, groupId);
    String bobId = keycloak.createUser(bob, bobPassword);
    createdUsernames.add(bob);
    keycloak.addUserToGroup(bobId, groupId);
    keycloak.createUser(eve, evePassword);
    createdUsernames.add(eve);

    metalake.addUser(alice);
    metalake.addUser(bob);
    metalake.addUser(eve);
    metalake.addGroup(groupName);

    // r_select: USE_CATALOG + USE_SCHEMA + SELECT_TABLE on tbl_a, granted to dev_team.
    // Full chain required for loadTable.
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
    metalake.grantRolesToGroup(Lists.newArrayList(roleName), groupName);

    NameIdentifier tableIdent = NameIdentifier.of(SCHEMA_NAME, TABLE_NAME);

    // alice (member): JWT-guard + loadTable success.
    String aliceToken = keycloak.fetchUserToken(alice, alicePassword);
    LOG.info("Alice JWT payload (T7): {}", decodeJwtPayload(aliceToken));
    Assertions.assertTrue(
        decodeJwtPayload(aliceToken).contains("dev_team"),
        "Alice's JWT must place her in 'dev_team' for T7 positive case");
    GravitinoClient aliceClient =
        GravitinoClient.builder(gravitinoUri)
            .withMetalake(METALAKE_NAME)
            .withOAuth(new PasswordGrantTokenProvider(aliceToken))
            .build();
    try {
      Assertions.assertDoesNotThrow(
          () ->
              aliceClient
                  .loadMetalake(METALAKE_NAME)
                  .loadCatalog(CATALOG_NAME)
                  .asTableCatalog()
                  .loadTable(tableIdent));
    } finally {
      aliceClient.close();
    }

    // bob (member): same.
    String bobToken = keycloak.fetchUserToken(bob, bobPassword);
    LOG.info("Bob JWT payload (T7): {}", decodeJwtPayload(bobToken));
    Assertions.assertTrue(
        decodeJwtPayload(bobToken).contains("dev_team"),
        "Bob's JWT must place him in 'dev_team' for T7 positive case");
    GravitinoClient bobClient =
        GravitinoClient.builder(gravitinoUri)
            .withMetalake(METALAKE_NAME)
            .withOAuth(new PasswordGrantTokenProvider(bobToken))
            .build();
    try {
      Assertions.assertDoesNotThrow(
          () ->
              bobClient
                  .loadMetalake(METALAKE_NAME)
                  .loadCatalog(CATALOG_NAME)
                  .asTableCatalog()
                  .loadTable(tableIdent));
    } finally {
      bobClient.close();
    }

    // eve (non-member): denied.
    String eveToken = keycloak.fetchUserToken(eve, evePassword);
    String eveJwtPayload = decodeJwtPayload(eveToken);
    LOG.info("Eve JWT payload (T7): {}", eveJwtPayload);
    Assertions.assertFalse(
        eveJwtPayload.contains("dev_team"),
        "Eve's JWT must not place her in 'dev_team' for T7 to be meaningful. Payload: "
            + eveJwtPayload);
    GravitinoClient eveClient =
        GravitinoClient.builder(gravitinoUri)
            .withMetalake(METALAKE_NAME)
            .withOAuth(new PasswordGrantTokenProvider(eveToken))
            .build();
    try {
      Assertions.assertThrows(
          ForbiddenException.class,
          () ->
              eveClient
                  .loadMetalake(METALAKE_NAME)
                  .loadCatalog(CATALOG_NAME)
                  .asTableCatalog()
                  .loadTable(tableIdent));
    } finally {
      eveClient.close();
    }
  }

  // ──────────────────────────────────────────────────────────────────────────
  //  T8 — User in two groups gets the union of all granted privileges
  // ──────────────────────────────────────────────────────────────────────────
  @Test
  @DisplayName("T8: User in two groups gets the union of all granted privileges (OAuth2)")
  public void testUnionOfGroupPrivileges() throws Exception {
    String devTeam = "dev_team";
    String analystTeam = "analyst_team";
    String alice = "alice";
    String alicePassword = "alice-password";
    String roleA = "r_select_a";
    String roleB = "r_select_b";

    // Two disjoint fixture tables, each created by admin so alice is NOT their owner.
    String tableA = "tbl_t8_a_" + System.nanoTime();
    String tableB = "tbl_t8_b_" + System.nanoTime();

    // Keycloak: alice joins BOTH groups; her JWT carries both names.
    String devGroupId = keycloak.createGroup(devTeam);
    createdGroupNames.add(devTeam);
    String analystGroupId = keycloak.createGroup(analystTeam);
    createdGroupNames.add(analystTeam);
    String aliceId = keycloak.createUser(alice, alicePassword);
    createdUsernames.add(alice);
    keycloak.addUserToGroup(aliceId, devGroupId);
    keycloak.addUserToGroup(aliceId, analystGroupId);

    metalake.addUser(alice);
    metalake.addGroup(devTeam);
    metalake.addGroup(analystTeam);

    // Two fixture tables by admin so alice is NOT their owner; SELECT_TABLE is the only
    // path to loadTable for her on each table.
    Column[] columns = {Column.of("id", Types.LongType.get(), "row id")};
    NameIdentifier tableAIdent = NameIdentifier.of(SCHEMA_NAME, tableA);
    NameIdentifier tableBIdent = NameIdentifier.of(SCHEMA_NAME, tableB);
    catalog
        .asTableCatalog()
        .createTable(tableAIdent, columns, "T8 union fixture A", Maps.newHashMap());
    catalog
        .asTableCatalog()
        .createTable(tableBIdent, columns, "T8 union fixture B", Maps.newHashMap());

    try {
      // Two strictly orthogonal roles, each granting SELECT_TABLE on a different table:
      //   - r_select_a → dev_team, allows loadTable(tableA) only
      //   - r_select_b → analyst_team, allows loadTable(tableB) only
      // No ownership, no wildcard, no overlap. alice succeeds on BOTH iff the server
      // evaluates the privileges as a union across her two group memberships.
      SecurableObject catalogObj =
          SecurableObjects.ofCatalog(
              CATALOG_NAME, Lists.newArrayList(Privileges.UseCatalog.allow()));
      SecurableObject schemaUseObj =
          SecurableObjects.ofSchema(
              catalogObj, SCHEMA_NAME, Lists.newArrayList(Privileges.UseSchema.allow()));

      SecurableObject tableASelectObj =
          SecurableObjects.ofTable(
              schemaUseObj, tableA, Lists.newArrayList(Privileges.SelectTable.allow()));
      metalake.createRole(
          roleA, Maps.newHashMap(), Lists.newArrayList(catalogObj, schemaUseObj, tableASelectObj));
      metalake.grantRolesToGroup(Lists.newArrayList(roleA), devTeam);

      SecurableObject tableBSelectObj =
          SecurableObjects.ofTable(
              schemaUseObj, tableB, Lists.newArrayList(Privileges.SelectTable.allow()));
      metalake.createRole(
          roleB, Maps.newHashMap(), Lists.newArrayList(catalogObj, schemaUseObj, tableBSelectObj));
      metalake.grantRolesToGroup(Lists.newArrayList(roleB), analystTeam);

      // JWT diagnostic: both group names must be present.
      String aliceToken = keycloak.fetchUserToken(alice, alicePassword);
      String aliceJwtPayload = decodeJwtPayload(aliceToken);
      LOG.info("Alice JWT payload (T8): {}", aliceJwtPayload);
      Assertions.assertTrue(
          aliceJwtPayload.contains(devTeam),
          "Alice's JWT must place her in '" + devTeam + "' (T8). Payload: " + aliceJwtPayload);
      Assertions.assertTrue(
          aliceJwtPayload.contains(analystTeam),
          "Alice's JWT must place her in '" + analystTeam + "' (T8). Payload: " + aliceJwtPayload);

      // Same client / same token loads both tables → union semantics confirmed.
      GravitinoClient aliceClient =
          GravitinoClient.builder(gravitinoUri)
              .withMetalake(METALAKE_NAME)
              .withOAuth(new PasswordGrantTokenProvider(aliceToken))
              .build();
      try {
        Assertions.assertDoesNotThrow(
            () ->
                aliceClient
                    .loadMetalake(METALAKE_NAME)
                    .loadCatalog(CATALOG_NAME)
                    .asTableCatalog()
                    .loadTable(tableAIdent),
            "loadTable(" + tableA + ") should succeed via dev_team's SELECT_TABLE on tableA");
        Assertions.assertDoesNotThrow(
            () ->
                aliceClient
                    .loadMetalake(METALAKE_NAME)
                    .loadCatalog(CATALOG_NAME)
                    .asTableCatalog()
                    .loadTable(tableBIdent),
            "loadTable(" + tableB + ") should succeed via analyst_team's SELECT_TABLE on tableB");
      } finally {
        aliceClient.close();
      }
    } finally {
      try {
        catalog.asTableCatalog().dropTable(tableAIdent);
      } catch (Exception e) {
        LOG.warn("Failed to drop T8 fixture table A '{}'", tableA, e);
      }
      try {
        catalog.asTableCatalog().dropTable(tableBIdent);
      } catch (Exception e) {
        LOG.warn("Failed to drop T8 fixture table B '{}'", tableB, e);
      }
    }
  }

  // ──────────────────────────────────────────────────────────────────────────
  //  T9 — revokeRolesFromGroup invalidates the authorization cache
  // ──────────────────────────────────────────────────────────────────────────
  @Test
  @DisplayName(
      "T9: revokeRolesFromGroup is reflected on the very next call (cache invalidation, OAuth2)")
  public void testRevokeRoleFromGroupInvalidatesCache() throws Exception {
    String groupName = "dev_team";
    String roleName = "r_select";
    String alice = "alice";
    String alicePassword = "alice-password";

    // Keycloak: alice joins dev_team.
    String groupId = keycloak.createGroup(groupName);
    createdGroupNames.add(groupName);
    String aliceId = keycloak.createUser(alice, alicePassword);
    createdUsernames.add(alice);
    keycloak.addUserToGroup(aliceId, groupId);

    metalake.addUser(alice);
    metalake.addGroup(groupName);

    // r_select: USE_CATALOG + USE_SCHEMA + SELECT_TABLE on tbl_a, granted to dev_team.
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
    metalake.grantRolesToGroup(Lists.newArrayList(roleName), groupName);

    // Same client + same JWT for both pre- and post-revoke calls: JWT carries `groups`
    // only; role-to-group binding is server-side. Reusing the token exercises server-side
    // cache invalidation; minting a new one would not.
    String aliceToken = keycloak.fetchUserToken(alice, alicePassword);
    String aliceJwtPayload = decodeJwtPayload(aliceToken);
    LOG.info("Alice JWT payload (T9): {}", aliceJwtPayload);
    Assertions.assertTrue(
        aliceJwtPayload.contains("dev_team"),
        "Alice's JWT must place her in 'dev_team' for T9 to be meaningful. Payload: "
            + aliceJwtPayload);

    NameIdentifier tableIdent = NameIdentifier.of(SCHEMA_NAME, TABLE_NAME);
    GravitinoClient aliceClient =
        GravitinoClient.builder(gravitinoUri)
            .withMetalake(METALAKE_NAME)
            .withOAuth(new PasswordGrantTokenProvider(aliceToken))
            .build();
    try {
      // Prime the authorization cache.
      Assertions.assertDoesNotThrow(
          () ->
              aliceClient
                  .loadMetalake(METALAKE_NAME)
                  .loadCatalog(CATALOG_NAME)
                  .asTableCatalog()
                  .loadTable(tableIdent),
          "loadTable should succeed before revoke (priming the cache)");

      metalake.revokeRolesFromGroup(Lists.newArrayList(roleName), groupName);

      // Same client / same token / same op → must be rejected. No stale-ALLOW grace.
      Assertions.assertThrows(
          ForbiddenException.class,
          () ->
              aliceClient
                  .loadMetalake(METALAKE_NAME)
                  .loadCatalog(CATALOG_NAME)
                  .asTableCatalog()
                  .loadTable(tableIdent),
          "loadTable must fail after revokeRolesFromGroup with no client refresh");
    } finally {
      aliceClient.close();
    }

    // Metalake state must agree with the authorization decision.
    Group group = metalake.getGroup(groupName);
    Assertions.assertFalse(
        group.roles().contains(roleName),
        "Group '" + groupName + "' should no longer hold role '" + roleName + "' after revoke");
  }

  // ──────────────────────────────────────────────────────────────────────────
  //  T10 — User-level DENY overrides group-inherited ALLOW
  // ──────────────────────────────────────────────────────────────────────────
  @Test
  @DisplayName("T10: User-level DENY overrides group-inherited ALLOW (OAuth2)")
  public void testUserDenyOverridesGroupAllow() throws Exception {
    String groupName = "dev_team";
    String roleSelect = "r_select";
    String roleDenyAlice = "r_deny_alice";
    String alice = "alice";
    String alicePassword = "alice-password";
    String bob = "bob";
    String bobPassword = "bob-password";

    // Keycloak: alice + bob both join dev_team. The pair is symmetric except for the
    // user-level DENY granted below — that asymmetry is the entire point of T10.
    String groupId = keycloak.createGroup(groupName);
    createdGroupNames.add(groupName);
    String aliceId = keycloak.createUser(alice, alicePassword);
    createdUsernames.add(alice);
    keycloak.addUserToGroup(aliceId, groupId);
    String bobId = keycloak.createUser(bob, bobPassword);
    createdUsernames.add(bob);
    keycloak.addUserToGroup(bobId, groupId);

    metalake.addUser(alice);
    metalake.addUser(bob);
    metalake.addGroup(groupName);

    // Group-level ALLOW: USE_CATALOG + USE_SCHEMA + SELECT_TABLE on tbl_a → dev_team.
    SecurableObject catalogObj =
        SecurableObjects.ofCatalog(CATALOG_NAME, Lists.newArrayList(Privileges.UseCatalog.allow()));
    SecurableObject schemaObj =
        SecurableObjects.ofSchema(
            catalogObj, SCHEMA_NAME, Lists.newArrayList(Privileges.UseSchema.allow()));
    SecurableObject tableSelectObj =
        SecurableObjects.ofTable(
            schemaObj, TABLE_NAME, Lists.newArrayList(Privileges.SelectTable.allow()));
    metalake.createRole(
        roleSelect, Maps.newHashMap(), Lists.newArrayList(catalogObj, schemaObj, tableSelectObj));
    metalake.grantRolesToGroup(Lists.newArrayList(roleSelect), groupName);

    // User-level DENY granted directly to alice. Includes USE_CATALOG + USE_SCHEMA so
    // her request actually reaches the SELECT_TABLE check; otherwise loadCatalog
    // would reject her before DENY precedence is even evaluated.
    SecurableObject tableDenyObj =
        SecurableObjects.ofTable(
            schemaObj, TABLE_NAME, Lists.newArrayList(Privileges.SelectTable.deny()));
    metalake.createRole(
        roleDenyAlice, Maps.newHashMap(), Lists.newArrayList(catalogObj, schemaObj, tableDenyObj));
    metalake.grantRolesToUser(Lists.newArrayList(roleDenyAlice), alice);

    NameIdentifier tableIdent = NameIdentifier.of(SCHEMA_NAME, TABLE_NAME);

    // alice: user-level DENY overrides her group-level ALLOW. The JWT-guard pins the
    // failure cause to DENY precedence, not "no grant at all".
    String aliceToken = keycloak.fetchUserToken(alice, alicePassword);
    String aliceJwtPayload = decodeJwtPayload(aliceToken);
    LOG.info("Alice JWT payload (T10): {}", aliceJwtPayload);
    Assertions.assertTrue(
        aliceJwtPayload.contains("dev_team"),
        "Alice's JWT must place her in 'dev_team' so the test exercises DENY-over-ALLOW "
            + "rather than 'no grant at all'. Payload: "
            + aliceJwtPayload);

    GravitinoClient aliceClient =
        GravitinoClient.builder(gravitinoUri)
            .withMetalake(METALAKE_NAME)
            .withOAuth(new PasswordGrantTokenProvider(aliceToken))
            .build();
    try {
      Assertions.assertThrows(
          ForbiddenException.class,
          () ->
              aliceClient
                  .loadMetalake(METALAKE_NAME)
                  .loadCatalog(CATALOG_NAME)
                  .asTableCatalog()
                  .loadTable(tableIdent),
          "Alice's user-level DENY must override dev_team's ALLOW SELECT_TABLE on tbl_a");
    } finally {
      aliceClient.close();
    }

    // bob (control): same group, no user-level DENY → loadTable succeeds.
    String bobToken = keycloak.fetchUserToken(bob, bobPassword);
    String bobJwtPayload = decodeJwtPayload(bobToken);
    LOG.info("Bob JWT payload (T10): {}", bobJwtPayload);
    Assertions.assertTrue(
        bobJwtPayload.contains("dev_team"),
        "Bob's JWT must place him in 'dev_team' (T10 control). Payload: " + bobJwtPayload);

    GravitinoClient bobClient =
        GravitinoClient.builder(gravitinoUri)
            .withMetalake(METALAKE_NAME)
            .withOAuth(new PasswordGrantTokenProvider(bobToken))
            .build();
    try {
      Assertions.assertDoesNotThrow(
          () ->
              bobClient
                  .loadMetalake(METALAKE_NAME)
                  .loadCatalog(CATALOG_NAME)
                  .asTableCatalog()
                  .loadTable(tableIdent),
          "Bob has the group-inherited ALLOW and no DENY — loadTable must succeed");
    } finally {
      bobClient.close();
    }
  }

  // ──────────────────────────────────────────────────────────────────────────
  //  T11 — Removing a user from a group revokes inherited privileges
  // ──────────────────────────────────────────────────────────────────────────
  @Test
  @DisplayName(
      "T11: Removing a user from a group revokes inherited privileges on the next call (OAuth2)")
  public void testRemoveUserFromGroupRevokesPrivileges() throws Exception {
    String groupName = "dev_team";
    String roleName = "r_select";
    String alice = "alice";
    String alicePassword = "alice-password";
    String bob = "bob";
    String bobPassword = "bob-password";

    // Keycloak: alice + bob both join dev_team initially.
    String groupId = keycloak.createGroup(groupName);
    createdGroupNames.add(groupName);
    String aliceId = keycloak.createUser(alice, alicePassword);
    createdUsernames.add(alice);
    keycloak.addUserToGroup(aliceId, groupId);
    String bobId = keycloak.createUser(bob, bobPassword);
    createdUsernames.add(bob);
    keycloak.addUserToGroup(bobId, groupId);

    metalake.addUser(alice);
    metalake.addUser(bob);
    metalake.addGroup(groupName);

    // r_select: USE_CATALOG + USE_SCHEMA + SELECT_TABLE on tbl_a, granted to dev_team.
    SecurableObject catalogObj =
        SecurableObjects.ofCatalog(CATALOG_NAME, Lists.newArrayList(Privileges.UseCatalog.allow()));
    SecurableObject schemaObj =
        SecurableObjects.ofSchema(
            catalogObj, SCHEMA_NAME, Lists.newArrayList(Privileges.UseSchema.allow()));
    SecurableObject tableObj =
        SecurableObjects.ofTable(
            schemaObj, TABLE_NAME, Lists.newArrayList(Privileges.SelectTable.allow()));
    metalake.createRole(
        roleName, Maps.newHashMap(), Lists.newArrayList(catalogObj, schemaObj, tableObj));
    metalake.grantRolesToGroup(Lists.newArrayList(roleName), groupName);

    NameIdentifier tableIdent = NameIdentifier.of(SCHEMA_NAME, TABLE_NAME);

    // Initial state: both alice and bob can loadTable via the group-inherited grant.
    String aliceTokenBefore = keycloak.fetchUserToken(alice, alicePassword);
    String aliceJwtBefore = decodeJwtPayload(aliceTokenBefore);
    LOG.info("Alice JWT payload before removal (T11): {}", aliceJwtBefore);
    Assertions.assertTrue(
        aliceJwtBefore.contains("dev_team"),
        "Alice's JWT must place her in 'dev_team' before removal. Payload: " + aliceJwtBefore);

    GravitinoClient aliceClientBefore =
        GravitinoClient.builder(gravitinoUri)
            .withMetalake(METALAKE_NAME)
            .withOAuth(new PasswordGrantTokenProvider(aliceTokenBefore))
            .build();
    try {
      Assertions.assertDoesNotThrow(
          () ->
              aliceClientBefore
                  .loadMetalake(METALAKE_NAME)
                  .loadCatalog(CATALOG_NAME)
                  .asTableCatalog()
                  .loadTable(tableIdent),
          "Alice should be able to loadTable while still in dev_team");
    } finally {
      aliceClientBefore.close();
    }

    String bobTokenBefore = keycloak.fetchUserToken(bob, bobPassword);
    String bobJwtBefore = decodeJwtPayload(bobTokenBefore);
    LOG.info("Bob JWT payload before alice's removal (T11): {}", bobJwtBefore);
    Assertions.assertTrue(
        bobJwtBefore.contains("dev_team"),
        "Bob's JWT must place him in 'dev_team' before alice's removal. Payload: " + bobJwtBefore);

    GravitinoClient bobClientBefore =
        GravitinoClient.builder(gravitinoUri)
            .withMetalake(METALAKE_NAME)
            .withOAuth(new PasswordGrantTokenProvider(bobTokenBefore))
            .build();
    try {
      Assertions.assertDoesNotThrow(
          () ->
              bobClientBefore
                  .loadMetalake(METALAKE_NAME)
                  .loadCatalog(CATALOG_NAME)
                  .asTableCatalog()
                  .loadTable(tableIdent),
          "Bob should be able to loadTable while in dev_team");
    } finally {
      bobClientBefore.close();
    }

    // Remove alice from Keycloak group. The old token still carries `dev_team` (claim
    // frozen at issuance — correct OIDC behavior). Test exercises fresh-token-after-IdP-
    // mutation, not "old token must be rejected".
    keycloak.removeUserFromGroup(aliceId, groupId);

    // alice's fresh token must NOT carry `dev_team`; loadTable rejected.
    String aliceTokenAfter = keycloak.fetchUserToken(alice, alicePassword);
    String aliceJwtAfter = decodeJwtPayload(aliceTokenAfter);
    LOG.info("Alice JWT payload after removal (T11): {}", aliceJwtAfter);
    Assertions.assertFalse(
        aliceJwtAfter.contains("dev_team"),
        "Alice's refreshed JWT must NOT place her in 'dev_team' after removal. Payload: "
            + aliceJwtAfter);

    GravitinoClient aliceClientAfter =
        GravitinoClient.builder(gravitinoUri)
            .withMetalake(METALAKE_NAME)
            .withOAuth(new PasswordGrantTokenProvider(aliceTokenAfter))
            .build();
    try {
      Assertions.assertThrows(
          ForbiddenException.class,
          () ->
              aliceClientAfter
                  .loadMetalake(METALAKE_NAME)
                  .loadCatalog(CATALOG_NAME)
                  .asTableCatalog()
                  .loadTable(tableIdent),
          "Alice's new token has no dev_team membership; loadTable must be rejected");
    } finally {
      aliceClientAfter.close();
    }

    // bob (control): new token, still carries `dev_team`, loadTable still succeeds.
    // Isolates the failure to "alice was removed", not "role revoked" or "group gone".
    String bobTokenAfter = keycloak.fetchUserToken(bob, bobPassword);
    String bobJwtAfter = decodeJwtPayload(bobTokenAfter);
    LOG.info("Bob JWT payload after alice's removal (T11): {}", bobJwtAfter);
    Assertions.assertTrue(
        bobJwtAfter.contains("dev_team"),
        "Bob's JWT must still place him in 'dev_team' after alice's removal. Payload: "
            + bobJwtAfter);

    GravitinoClient bobClientAfter =
        GravitinoClient.builder(gravitinoUri)
            .withMetalake(METALAKE_NAME)
            .withOAuth(new PasswordGrantTokenProvider(bobTokenAfter))
            .build();
    try {
      Assertions.assertDoesNotThrow(
          () ->
              bobClientAfter
                  .loadMetalake(METALAKE_NAME)
                  .loadCatalog(CATALOG_NAME)
                  .asTableCatalog()
                  .loadTable(tableIdent),
          "Bob is still in dev_team and the role is intact; loadTable must still succeed");
    } finally {
      bobClientAfter.close();
    }
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

  /** Bootstrap admin token provider (Keycloak {@code postman-client}, client_credentials grant). */
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
   * GravitinoClient for an end user via the resource-owner password grant. Fetches a token on the
   * spot so any preceding group-membership change is reflected in the JWT (load-bearing for T2, T9,
   * T11).
   */
  private static GravitinoClient oidcUserClient(String username, String password) throws Exception {
    String userToken = keycloak.fetchUserToken(username, password);
    return GravitinoClient.builder(gravitinoUri)
        .withMetalake(METALAKE_NAME)
        .withOAuth(new PasswordGrantTokenProvider(userToken))
        .build();
  }

  /**
   * Decodes a JWT payload without signature verification. Diagnostic aid only — production paths
   * validate via JWKS.
   */
  private static String decodeJwtPayload(String jwt) {
    String[] parts = jwt.split("\\.");
    if (parts.length < 2) {
      throw new IllegalArgumentException("Token is not a JWT (no payload segment)");
    }
    byte[] payload = Base64.getUrlDecoder().decode(parts[1]);
    return new String(payload, StandardCharsets.UTF_8);
  }
}
