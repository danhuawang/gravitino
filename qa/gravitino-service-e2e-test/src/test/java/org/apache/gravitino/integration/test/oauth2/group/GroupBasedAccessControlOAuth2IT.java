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
import org.apache.gravitino.NameIdentifier;
import org.apache.gravitino.authorization.Group;
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
 * (EPIC apache/gravitino#10404).
 *
 * <p>This class is the OAuth2 counterpart of {@link
 * org.apache.gravitino.integration.test.authorization.group.GroupBasedAccessControlIT}. It is bound
 * to <b>Env 2 oauth2-auth</b> ({@code qa/k8s/helm-values/env2-oauth2-auth-values.yaml}). The
 * Gravitino server validates JWTs via JWKS and extracts the {@code groups} claim into the
 * authorization context (see {@code authenticator.oauth.groupsFields=groups}, {@code
 * groupMapper=regex}, pattern {@code ^/(.*)} in the env2 helm values).
 *
 * <p>Identity model: every test run self-bootstraps users and groups in Keycloak via {@link
 * KeycloakAdminHelper} so the suite is independent of pre-baked realm data. Test fixtures are
 * dropped in {@link #cleanupKeycloak()} to avoid dirty state across runs.
 *
 * <p>Token model:
 *
 * <ul>
 *   <li>The bootstrap admin uses {@link DefaultOAuth2TokenProvider} (client_credentials grant)
 *       against the {@code postman-client} service account, which maps to {@code
 *       service-account-postman-client} in {@code serviceAdmins}.
 *   <li>End-user clients use {@link PasswordGrantTokenProvider} with a JWT fetched <i>after</i> all
 *       Keycloak group changes have been applied. This is critical for T9/T11 where the test
 *       validates Gravitino's authorization-cache invalidation against the <i>current</i> claim
 *       set, not a stale token.
 * </ul>
 *
 * <p>Covered tests (mirrors the simple-auth original, plus T2 — the OAuth2 group-claim extraction
 * path introduced in subtask apache/gravitino#10410):
 *
 * <ul>
 *   <li>T1 — add/list/remove groups
 *   <li>T2 — JWT {@code groups} claim is extracted into the authorization context, so a user with
 *       no direct grants can still be authorized via her group's role
 *   <li>T3 — JWT carries no group memberships → user gets none of the group's privileges
 *   <li>T4 — schema owner = GROUP, member can ALTER
 *   <li>T5 — role owner = GROUP, only members can drop the role
 *   <li>T6 — owner type switch USER ↔ GROUP
 *   <li>T7 — grantRolesToGroup → all members inherit privileges
 *   <li>T8 — member of two groups gets the union of privileges
 *   <li>T9 — revokeRolesFromGroup invalidates the authorization cache
 *   <li>T10 — DENY on user overrides ALLOW from group role
 *   <li>T11 — removeUserFromGroup revokes inherited privileges
 * </ul>
 *
 * <p>Required environment variables (see {@code IrcE2ETestBase} for the project-wide convention):
 *
 * <ul>
 *   <li>{@code OAUTH2_SERVER_URI} — Keycloak base URL, e.g. {@code http://<kc_ip>:8080}
 *   <li>{@code OAUTH2_CLIENT_ID} — typically {@code postman-client}
 *   <li>{@code OAUTH2_CLIENT_SECRET} — service-account secret
 * </ul>
 *
 * <p>Optional: {@code OAUTH2_REALM} (default {@code myrealm}), {@code OAUTH2_TOKEN_PATH}, {@code
 * OAUTH2_SCOPE} (default {@code openid profile email}), {@code OAUTH2_ADMIN_USER} (default {@code
 * admin}), {@code OAUTH2_ADMIN_PASSWORD} (default {@code admin}).
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

    // Keycloak admin helper. We mint admin tokens via master/admin-cli password grant because
    // the realm-level `postman-client` service account has no realm-management roles in the
    // default seeded realm. Resource-owner password tokens for end users (e.g. alice) are
    // still minted against the realm-level `postman-client`.
    keycloak =
        KeycloakAdminHelper.builder()
            .withBaseUrl(keycloakBaseUrl)
            .withRealm(keycloakRealm)
            .withMasterAdminPassword(keycloakAdminUser, keycloakAdminPassword)
            .withUserTokenClient(keycloakClientId, keycloakClientSecret)
            .build();
    keycloak.authenticate();

    // The seeded `myrealm` baseimage does not bake a Group Membership mapper into the
    // `postman-client`, so end-user tokens issued via the password grant carry no `groups`
    // claim. Provision the mapper here (idempotent) so Gravitino's group-claim extraction
    // (subtask #10410) has something to read.
    keycloak.ensureGroupsMapperOnClient(keycloakClientId);

    // Bootstrap admin Gravitino client. Maps to serviceAdmin
    // `service-account-postman-client` in env2-oauth2-auth-values.yaml.
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

    // tbl_a — the canonical "data table" used by privilege-inheritance tests (T2 today,
    // T7/T8/T9/T10/T11 once they migrate from metadata-only ops to real table reads).
    // A minimal single-column Hive table is enough: the authorization layer only needs to
    // resolve the SecurableObject; it does not read row data.
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
    // 1) Drop Gravitino-side user/group/role state so the next test starts from a clean
    // metalake. Users must come first because some pre-checks reject the request when a
    // dangling User entity references a no-longer-existing group.
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

    // 2) Drop Keycloak users created by this test (so JWT identities don't leak across tests).
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

    // 3) Drop Keycloak groups created by this test.
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

    // 1) Bootstrap Keycloak: create the group, the user, and put the user in the group so
    // the realm's group-membership mapper emits `"groups":["dev_team"]` in alice's JWT.
    String groupId = keycloak.createGroup(groupName);
    createdGroupNames.add(groupName);
    String aliceId = keycloak.createUser(alice, alicePassword);
    createdUsernames.add(alice);
    keycloak.addUserToGroup(aliceId, groupId);

    // 2) Pre-create the group AND the user entity on the Gravitino side. Subtask #10410
    // populates the runtime *group* set on a request from the JWT's `groups` claim, but
    // Gravitino still requires the user principal to exist as a metalake entity before
    // authorization can resolve it (otherwise the request fails with 403
    // "Current user X doesn't exist in the metalake"). Note that we do NOT call
    // addUserToGroup here: that link is supplied implicitly by the JWT claim, which is the
    // whole point of T2.
    metalake.addUser(alice);
    metalake.addGroup(groupName);

    // 3) Grant a role with USE_CATALOG + USE_SCHEMA + SELECT_TABLE on tbl_a to dev_team.
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

    // 4) Alice fetches her token *after* the membership change, so her JWT carries the
    // `groups` claim, then issues loadTable. The authorization layer must populate her
    // group set from the claim and authorize via the role granted to dev_team.
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

    // 1) Bootstrap Keycloak: create the group and the user, but do NOT add eve to the
    // group. The realm-level group-membership mapper on `postman-client` emits the
    // `groups` claim from the user's actual memberships, so eve's JWT will carry an empty
    // `groups` array (or no claim at all). This is the exact negative counterpart of T2:
    // T2 verifies that a non-empty `groups` claim is extracted and grants privileges; T3
    // verifies that the absence of the claim does not leak any group-derived privileges.
    String groupId = keycloak.createGroup(groupName);
    createdGroupNames.add(groupName);
    String eveId = keycloak.createUser(eve, evePassword);
    createdUsernames.add(eve);
    // Intentionally no addUserToGroup(eveId, groupId): eve is a non-member.
    Assertions.assertNotNull(groupId, "groupId must be non-null after createGroup");
    Assertions.assertNotNull(eveId, "eveId must be non-null after createUser");

    // 2) Pre-create the user and the group on the Gravitino side. The user entity must
    // exist so authorization can resolve the principal; the group must exist so the
    // role can be granted to it. Note: eve is NOT added to the Gravitino group either —
    // direct group membership in the metalake is the simple-auth path, while the OAuth2
    // path under test sources membership exclusively from the JWT.
    metalake.addUser(eve);
    metalake.addGroup(groupName);

    // 3) Grant the role to dev_team. If group-claim extraction were buggy and silently
    // matched any user, eve's loadTable would succeed below. The contract under test is
    // that it must not.
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

    // 4) Eve fetches her token. The `groups` claim should be absent or empty: we log the
    // payload as a diagnostic aid (matches T2 logging) and assert that 'dev_team' is not
    // in it, otherwise the test would silently degenerate into a copy of T2.
    String eveToken = keycloak.fetchUserToken(eve, evePassword);
    String eveJwtPayload = decodeJwtPayload(eveToken);
    LOG.info("Eve JWT payload (T3): {}", eveJwtPayload);
    Assertions.assertFalse(
        eveJwtPayload.contains("dev_team"),
        "Eve's JWT must not carry membership in 'dev_team' for T3 to be meaningful. "
            + "Payload: "
            + eveJwtPayload);

    // 5) Eve calls loadTable. Expected: ForbiddenException, since she has no direct grant
    // and her JWT does not place her in any group that holds the role.
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
  //  Helpers
  // ──────────────────────────────────────────────────────────────────────────

  /**
   * Returns the value of the named environment variable, or fails fast with a clear message if it
   * is unset or empty. Used to surface missing CI / kind-cluster wiring at @BeforeAll.
   */
  private static String requireEnv(String name) {
    String value = System.getenv(name);
    if (value == null || value.isEmpty()) {
      throw new IllegalStateException(
          "Required environment variable not set: " + name + ". Run setup-kind-env.sh first.");
    }
    return value;
  }

  /**
   * Builds an {@link OAuth2TokenProvider} for the bootstrap admin (Keycloak {@code postman-client}
   * service account, mapped to {@code service-account-postman-client} in {@code serviceAdmins} of
   * {@code env2-oauth2-auth-values.yaml}). Uses the {@code client_credentials} grant.
   */
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
   * Builds a {@link GravitinoClient} that authenticates as a Keycloak end-user via the
   * resource-owner password grant. The token is fetched on the spot so that any group-membership
   * change applied just before this call is reflected in the JWT's {@code groups} claim — this is
   * load-bearing for tests that mutate Keycloak group state mid-test (T2, T9, T11).
   */
  private static GravitinoClient oidcUserClient(String username, String password) throws Exception {
    String userToken = keycloak.fetchUserToken(username, password);
    return GravitinoClient.builder(gravitinoUri)
        .withMetalake(METALAKE_NAME)
        .withOAuth(new PasswordGrantTokenProvider(userToken))
        .build();
  }

  /**
   * Decodes the payload (second segment) of a compact JWS without verifying the signature, so a
   * test can assert which claims Keycloak actually emitted (e.g. {@code groups}). Returns the raw
   * UTF-8 JSON string. Used purely as a diagnostic aid; production code paths must validate the
   * signature via JWKS.
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
