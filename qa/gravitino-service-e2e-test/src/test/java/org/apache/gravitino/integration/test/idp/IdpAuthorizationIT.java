/*
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *
 *  http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing,
 * software distributed under the License is distributed on an
 * "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
 * KIND, either express or implied.  See the License for the
 * specific language governing permissions and limitations
 * under the License.
 */
package org.apache.gravitino.integration.test.idp;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.google.common.collect.Lists;
import com.google.common.collect.Maps;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Base64;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import org.apache.gravitino.Catalog;
import org.apache.gravitino.MetadataObject;
import org.apache.gravitino.MetadataObjects;
import org.apache.gravitino.NameIdentifier;
import org.apache.gravitino.SchemaChange;
import org.apache.gravitino.authorization.Owner;
import org.apache.gravitino.authorization.Privileges;
import org.apache.gravitino.authorization.SecurableObject;
import org.apache.gravitino.authorization.SecurableObjects;
import org.apache.gravitino.client.GravitinoAdminClient;
import org.apache.gravitino.client.GravitinoClient;
import org.apache.gravitino.client.GravitinoMetalake;
import org.apache.gravitino.exceptions.ForbiddenException;
import org.apache.gravitino.rel.Column;
import org.apache.gravitino.rel.types.Types;
import org.apache.gravitino.utils.RandomNameUtils;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * E2E tests for built-in IDP group-based access control (test plan section 2.3, cases #65-#74).
 *
 * <p>Mirrors {@code GroupBasedAccessControlOAuth2IT} (T1-T11) but uses built-in IDP as the identity
 * source. Group membership is managed via {@code /api/idp/groups/{group}/users} instead of JWT
 * {@code groups} claim. Users authenticate with Basic auth; the server resolves group memberships
 * from the IDP backend at authorization time.
 *
 * <p>Environment config:
 *
 * <ul>
 *   <li>gravitino.authorization.enable=true
 *   <li>gravitino.authorization.serviceAdmins = admin,service-account-postman-client
 *   <li>Built-in IDP enabled with REST extension
 *   <li>Hive catalog available
 * </ul>
 */
@DisplayName("Built-in IDP Group-Based Access Control E2E Tests")
public class IdpAuthorizationIT {

  private static final Logger LOG = LoggerFactory.getLogger(IdpAuthorizationIT.class);
  private static final String ACCEPT = "application/vnd.gravitino.v1+json";
  private static final String ADMIN = "admin";
  private static final ObjectMapper MAPPER = new ObjectMapper();

  private static final String METALAKE_NAME = RandomNameUtils.genRandomName("metalake");
  private static final String CATALOG_NAME = "hive_catalog";
  private static final String SCHEMA_NAME = RandomNameUtils.genRandomName("test_schema");
  private static final String TABLE_NAME = "tbl_a";

  private static final String ALICE_PASSWORD = "Passw0rd-Alice01";
  private static final String BOB_PASSWORD = "Passw0rd-Bob00001";
  private static final String EVE_PASSWORD = "Passw0rd-Eve0001";

  private static String adminPassword;
  private static String gravitinoUri;
  private static String idpApiBase;
  private static HttpClient httpClient;

  private static GravitinoAdminClient adminClient;
  private static GravitinoMetalake metalake;
  private static Catalog catalog;

  /** Track IDP users and groups created by the current test for cleanup. */
  private final List<String> createdIdpUsers = new ArrayList<>();

  private final List<String> createdIdpGroups = new ArrayList<>();

  @BeforeAll
  public static void setup() throws Exception {
    gravitinoUri = System.getProperty("gravitino.uri", "http://localhost:30090");
    adminPassword = System.getProperty("gravitino.admin.password", "Passw0rd-Admin12");
    String hiveMetastoreUri = System.getProperty("hive.metastore.uri", "thrift://localhost:30083");
    idpApiBase = gravitinoUri + "/api/idp";
    httpClient = HttpClient.newHttpClient();

    adminClient =
        GravitinoAdminClient.builder(gravitinoUri).withBasicAuth(ADMIN, adminPassword).build();
    metalake =
        adminClient.createMetalake(METALAKE_NAME, "IDP authorization test", Collections.emptyMap());

    Map<String, String> catalogProperties = Maps.newHashMap();
    catalogProperties.put("metastore.uris", hiveMetastoreUri);
    catalog =
        metalake.createCatalog(
            CATALOG_NAME, Catalog.Type.RELATIONAL, "hive", "comment", catalogProperties);

    catalog.asSchemas().createSchema(SCHEMA_NAME, "comment", Collections.emptyMap());

    Column[] columns = {Column.of("id", Types.LongType.get(), "row id")};
    catalog
        .asTableCatalog()
        .createTable(
            NameIdentifier.of(SCHEMA_NAME, TABLE_NAME),
            columns,
            "group-based AC fixture table",
            Collections.emptyMap());

    LOG.info(
        "IdpAuthorizationIT setup: metalake={}, catalog={}, schema={}, table={}",
        METALAKE_NAME,
        CATALOG_NAME,
        SCHEMA_NAME,
        TABLE_NAME);
  }

  @AfterEach
  public void cleanup() throws Exception {
    // Clean Gravitino-side state: users → groups → roles
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

    // Clean IDP-side groups (force=true) then users
    for (String group : createdIdpGroups) {
      deleteIdpGroup(group, true);
    }
    createdIdpGroups.clear();

    for (String user : createdIdpUsers) {
      deleteIdpUser(user);
    }
    createdIdpUsers.clear();
  }

  @AfterAll
  public static void teardown() {
    try {
      if (catalog != null) {
        catalog.asTableCatalog().dropTable(NameIdentifier.of(SCHEMA_NAME, TABLE_NAME));
      }
    } catch (Exception e) {
      LOG.warn("Failed to drop fixture table", e);
    }
    try {
      if (adminClient != null) {
        adminClient.dropMetalake(METALAKE_NAME, true);
      }
    } catch (Exception e) {
      LOG.warn("Failed to drop metalake '{}'", METALAKE_NAME, e);
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
  //  #21 #22 — Authorization-enabled IDP user permission isolation
  // ──────────────────────────────────────────────────────────────────────────
  @Test
  @DisplayName(
      "#22 Authorization-enabled IDP user permission isolation - IDP user can only access"
          + " authorized resources per RBAC rules")
  void testIdpUserPermissionIsolation() throws Exception {
    String username = "e2e-isolated-user-" + System.currentTimeMillis();
    String password = "Passw0rd-E2E-Test1";
    createIdpUser(username, password);

    // When authorization is enabled, a regular IDP user should NOT be able to access
    // admin-only resources. Try to list metalakes which requires authorization.
    HttpRequest metalakeRequest =
        HttpRequest.newBuilder()
            .uri(URI.create(gravitinoUri + "/api/metalakes"))
            .header("Accept", ACCEPT)
            .header("Authorization", basicAuth(username, password))
            .GET()
            .build();
    HttpResponse<String> metalakeResp =
        httpClient.send(metalakeRequest, HttpResponse.BodyHandlers.ofString());

    int statusCode = metalakeResp.statusCode();
    JsonNode responseJson = MAPPER.readTree(metalakeResp.body());

    if (statusCode == 403) {
      // Authorization is enabled: regular user is blocked from accessing metalakes
      Assertions.assertNotEquals(
          0, responseJson.get("code").asInt(), "Should have a non-zero error code");
      LOG.info(
          "IDP user '{}' correctly denied access to /api/metalakes (authorization enabled)",
          username);
    } else if (statusCode == 200) {
      // Authorization may not be enabled or metalake listing is open. Confirm the regular
      // user CANNOT access IDP management APIs (confirming role-based isolation at IDP level).
      LOG.info(
          "IDP user '{}' can access /api/metalakes (authorization may not be enabled),"
              + " verifying IDP management isolation instead",
          username);

      HttpRequest idpRequest =
          HttpRequest.newBuilder()
              .uri(URI.create(idpApiBase + "/users/" + ADMIN))
              .header("Accept", ACCEPT)
              .header("Authorization", basicAuth(username, password))
              .GET()
              .build();
      HttpResponse<String> idpResp =
          httpClient.send(idpRequest, HttpResponse.BodyHandlers.ofString());
      Assertions.assertEquals(
          403,
          idpResp.statusCode(),
          "Regular IDP user should NOT access IDP management API: " + idpResp.body());
    } else {
      Assertions.fail(
          "Unexpected status code "
              + statusCode
              + " when accessing /api/metalakes: "
              + metalakeResp.body());
    }

    // Additionally verify the user can still access non-protected endpoints
    HttpRequest versionRequest =
        HttpRequest.newBuilder()
            .uri(URI.create(gravitinoUri + "/api/version"))
            .header("Accept", ACCEPT)
            .header("Authorization", basicAuth(username, password))
            .GET()
            .build();
    HttpResponse<String> versionResp =
        httpClient.send(versionRequest, HttpResponse.BodyHandlers.ofString());
    Assertions.assertEquals(
        200,
        versionResp.statusCode(),
        "IDP user should always be able to access /api/version: " + versionResp.body());

    LOG.info("Permission isolation test completed for user: {}", username);
  }

  // ──────────────────────────────────────────────────────────────────────────
  //  #65 — IDP group membership grants authorization
  // ──────────────────────────────────────────────────────────────────────────
  @Test
  @DisplayName("#65 IDP group membership grants authorization via group role")
  void testIdpGroupMembershipGrantsAuthorization() throws Exception {
    String groupName = "dev_team";
    String roleName = "r_select";
    String alice = "alice";

    // IDP: create user and group, add user to group
    createIdpUser(alice, ALICE_PASSWORD);
    createIdpGroup(groupName);
    addIdpUsersToGroup(groupName, alice);

    // Gravitino: register user and group, grant role to group
    metalake.addUser(alice);
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

    // alice (group member): loadTable should succeed
    GravitinoClient aliceClient =
        GravitinoClient.builder(gravitinoUri)
            .withMetalake(METALAKE_NAME)
            .withBasicAuth(alice, ALICE_PASSWORD)
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
  //  #66 — User not in IDP group has no group-derived privileges
  // ──────────────────────────────────────────────────────────────────────────
  @Test
  @DisplayName("#66 User not in IDP group has no group-derived privileges")
  void testUserNotInIdpGroupDenied() throws Exception {
    String groupName = "dev_team";
    String roleName = "r_select";
    String eve = "eve";

    // IDP: create user and group, but do NOT add eve to group
    createIdpUser(eve, EVE_PASSWORD);
    createIdpGroup(groupName);

    // Gravitino: register user and group, grant role to group only
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

    // eve (non-member): should be denied
    GravitinoClient eveClient =
        GravitinoClient.builder(gravitinoUri)
            .withMetalake(METALAKE_NAME)
            .withBasicAuth(eve, EVE_PASSWORD)
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
  //  #67 — Schema owner = GROUP, group member can ALTER
  // ──────────────────────────────────────────────────────────────────────────
  @Test
  @DisplayName("#67 Schema owner of type GROUP, group member can alter the schema")
  void testSchemaOwnerGroup() throws Exception {
    String groupName = "dev_team";
    String alice = "alice";

    String eve = "eve";

    // IDP: alice joins dev_team, eve does not
    createIdpUser(alice, ALICE_PASSWORD);
    createIdpUser(eve, EVE_PASSWORD);
    createIdpGroup(groupName);
    addIdpUsersToGroup(groupName, alice);

    metalake.addUser(alice);
    metalake.addUser(eve);
    metalake.addGroup(groupName);

    // Set schema owner to dev_team (GROUP)
    MetadataObject schemaObj =
        MetadataObjects.of(
            Lists.newArrayList(CATALOG_NAME, SCHEMA_NAME), MetadataObject.Type.SCHEMA);
    metalake.setOwner(schemaObj, groupName, Owner.Type.GROUP);

    // Grant USE_CATALOG to dev_team so members can reach the schema
    String useCatalogRoleName = "r_use_catalog";
    SecurableObject useCatalogObject =
        SecurableObjects.ofCatalog(CATALOG_NAME, Lists.newArrayList(Privileges.UseCatalog.allow()));
    metalake.createRole(
        useCatalogRoleName, Maps.newHashMap(), Lists.newArrayList(useCatalogObject));
    metalake.grantRolesToGroup(Lists.newArrayList(useCatalogRoleName), groupName);

    // alice (group member): alterSchema → success
    GravitinoClient aliceClient =
        GravitinoClient.builder(gravitinoUri)
            .withMetalake(METALAKE_NAME)
            .withBasicAuth(alice, ALICE_PASSWORD)
            .build();
    try {
      Assertions.assertDoesNotThrow(
          () ->
              aliceClient
                  .loadMetalake(METALAKE_NAME)
                  .loadCatalog(CATALOG_NAME)
                  .asSchemas()
                  .alterSchema(SCHEMA_NAME, SchemaChange.setProperty("k", "v")));
    } finally {
      aliceClient.close();
    }

    // eve (non-member): alterSchema → ForbiddenException
    GravitinoClient eveClient =
        GravitinoClient.builder(gravitinoUri)
            .withMetalake(METALAKE_NAME)
            .withBasicAuth(eve, EVE_PASSWORD)
            .build();
    try {
      Assertions.assertThrows(
          ForbiddenException.class,
          () ->
              eveClient
                  .loadMetalake(METALAKE_NAME)
                  .loadCatalog(CATALOG_NAME)
                  .asSchemas()
                  .alterSchema(SCHEMA_NAME, SchemaChange.setProperty("k", "v")));
    } finally {
      eveClient.close();
    }
  }

  // ──────────────────────────────────────────────────────────────────────────
  //  #68 — Role owner = GROUP, only members can drop the role
  // ──────────────────────────────────────────────────────────────────────────
  @Test
  @DisplayName("#68 Role owner of type GROUP, only members can drop the role")
  void testRoleOwnerGroup() throws Exception {
    String groupName = "dev_team";
    String alice = "alice";

    String eve = "eve";

    String roleName = "r1";

    // IDP: only alice joins dev_team
    createIdpUser(alice, ALICE_PASSWORD);
    createIdpUser(eve, EVE_PASSWORD);
    createIdpGroup(groupName);
    addIdpUsersToGroup(groupName, alice);

    metalake.addUser(alice);
    metalake.addUser(eve);
    metalake.addGroup(groupName);

    // Create role and set owner to dev_team (GROUP)
    metalake.createRole(roleName, Maps.newHashMap(), Collections.emptyList());
    MetadataObject roleObject = MetadataObjects.of(null, roleName, MetadataObject.Type.ROLE);
    metalake.setOwner(roleObject, groupName, Owner.Type.GROUP);

    // alice (group member): deleteRole → success
    GravitinoClient aliceClient =
        GravitinoClient.builder(gravitinoUri)
            .withMetalake(METALAKE_NAME)
            .withBasicAuth(alice, ALICE_PASSWORD)
            .build();
    try {
      Assertions.assertTrue(aliceClient.loadMetalake(METALAKE_NAME).deleteRole(roleName));
    } finally {
      aliceClient.close();
    }

    // Recreate role + set owner for the negative case
    metalake.createRole(roleName, Maps.newHashMap(), Collections.emptyList());
    metalake.setOwner(
        MetadataObjects.of(null, roleName, MetadataObject.Type.ROLE), groupName, Owner.Type.GROUP);

    // eve (non-member): deleteRole → ForbiddenException
    GravitinoClient eveClient =
        GravitinoClient.builder(gravitinoUri)
            .withMetalake(METALAKE_NAME)
            .withBasicAuth(eve, EVE_PASSWORD)
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
  //  #69 — Switch owner USER → GROUP → USER
  // ──────────────────────────────────────────────────────────────────────────
  @Test
  @DisplayName("#69 Switching owner USER → GROUP → USER drops previous owner privileges")
  void testOwnerTypeSwitch() throws Exception {
    String groupName = "dev_team";
    String alice = "alice";

    String schemaName2 = RandomNameUtils.genRandomName("s2");

    createIdpUser(alice, ALICE_PASSWORD);
    createIdpGroup(groupName);
    // alice NOT in dev_team initially

    metalake.addUser(alice);
    metalake.addGroup(groupName);
    catalog.asSchemas().createSchema(schemaName2, "comment", Collections.emptyMap());

    try {
      MetadataObject schemaObj =
          MetadataObjects.of(
              Lists.newArrayList(CATALOG_NAME, schemaName2), MetadataObject.Type.SCHEMA);

      // Grant USE_CATALOG directly to alice so it persists across ownership transitions
      String useCatalogRoleAlice = "r_use_catalog_alice";
      SecurableObject useCatalogObject =
          SecurableObjects.ofCatalog(
              CATALOG_NAME, Lists.newArrayList(Privileges.UseCatalog.allow()));
      metalake.createRole(
          useCatalogRoleAlice, Maps.newHashMap(), Lists.newArrayList(useCatalogObject));
      metalake.grantRolesToUser(Lists.newArrayList(useCatalogRoleAlice), alice);

      // Step 1: owner = alice (USER). alterSchema succeeds.
      metalake.setOwner(schemaObj, alice, Owner.Type.USER);
      GravitinoClient aliceClient1 =
          GravitinoClient.builder(gravitinoUri)
              .withMetalake(METALAKE_NAME)
              .withBasicAuth(alice, ALICE_PASSWORD)
              .build();
      try {
        Assertions.assertDoesNotThrow(
            () ->
                aliceClient1
                    .loadMetalake(METALAKE_NAME)
                    .loadCatalog(CATALOG_NAME)
                    .asSchemas()
                    .alterSchema(schemaName2, SchemaChange.setProperty("k", "v1")));
      } finally {
        aliceClient1.close();
      }

      // Step 2: owner = dev_team (GROUP), alice NOT in dev_team. Must fail.
      metalake.setOwner(schemaObj, groupName, Owner.Type.GROUP);
      GravitinoClient aliceClient2 =
          GravitinoClient.builder(gravitinoUri)
              .withMetalake(METALAKE_NAME)
              .withBasicAuth(alice, ALICE_PASSWORD)
              .build();
      try {
        Assertions.assertThrows(
            ForbiddenException.class,
            () ->
                aliceClient2
                    .loadMetalake(METALAKE_NAME)
                    .loadCatalog(CATALOG_NAME)
                    .asSchemas()
                    .alterSchema(schemaName2, SchemaChange.setProperty("k", "v2")));
      } finally {
        aliceClient2.close();
      }

      // Step 3: add alice to dev_team via IDP. alterSchema succeeds via group ownership.
      addIdpUsersToGroup(groupName, alice);
      GravitinoClient aliceClient3 =
          GravitinoClient.builder(gravitinoUri)
              .withMetalake(METALAKE_NAME)
              .withBasicAuth(alice, ALICE_PASSWORD)
              .build();
      try {
        Assertions.assertDoesNotThrow(
            () ->
                aliceClient3
                    .loadMetalake(METALAKE_NAME)
                    .loadCatalog(CATALOG_NAME)
                    .asSchemas()
                    .alterSchema(schemaName2, SchemaChange.setProperty("k", "v3")));
      } finally {
        aliceClient3.close();
      }

      // Step 4: owner back to alice (USER). Works regardless of group membership.
      metalake.setOwner(schemaObj, alice, Owner.Type.USER);
      Owner finalOwner = metalake.getOwner(schemaObj).orElseThrow();
      Assertions.assertEquals(alice, finalOwner.name());
      Assertions.assertEquals(Owner.Type.USER, finalOwner.type());

      GravitinoClient aliceClient4 =
          GravitinoClient.builder(gravitinoUri)
              .withMetalake(METALAKE_NAME)
              .withBasicAuth(alice, ALICE_PASSWORD)
              .build();
      try {
        Assertions.assertDoesNotThrow(
            () ->
                aliceClient4
                    .loadMetalake(METALAKE_NAME)
                    .loadCatalog(CATALOG_NAME)
                    .asSchemas()
                    .alterSchema(schemaName2, SchemaChange.setProperty("k", "v4")));
      } finally {
        aliceClient4.close();
      }
    } finally {
      try {
        catalog.asSchemas().dropSchema(schemaName2, true);
      } catch (Exception e) {
        LOG.warn("Failed to drop schema '{}'", schemaName2, e);
      }
    }
  }

  // ──────────────────────────────────────────────────────────────────────────
  //  #70 — grantRolesToGroup → all members inherit privileges
  // ──────────────────────────────────────────────────────────────────────────
  @Test
  @DisplayName("#70 grantRolesToGroup, every current member inherits the role")
  void testGrantRolesToGroup() throws Exception {
    String groupName = "dev_team";
    String roleName = "r_select";
    String alice = "alice";

    String bob = "bob";

    String eve = "eve";

    // IDP: alice + bob in dev_team; eve not
    createIdpUser(alice, ALICE_PASSWORD);
    createIdpUser(bob, BOB_PASSWORD);
    createIdpUser(eve, EVE_PASSWORD);
    createIdpGroup(groupName);
    addIdpUsersToGroup(groupName, alice, bob);

    metalake.addUser(alice);
    metalake.addUser(bob);
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

    NameIdentifier tableIdent = NameIdentifier.of(SCHEMA_NAME, TABLE_NAME);

    // alice (member): success
    GravitinoClient aliceClient =
        GravitinoClient.builder(gravitinoUri)
            .withMetalake(METALAKE_NAME)
            .withBasicAuth(alice, ALICE_PASSWORD)
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

    // bob (member): success
    GravitinoClient bobClient =
        GravitinoClient.builder(gravitinoUri)
            .withMetalake(METALAKE_NAME)
            .withBasicAuth(bob, BOB_PASSWORD)
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

    // eve (non-member): denied
    GravitinoClient eveClient =
        GravitinoClient.builder(gravitinoUri)
            .withMetalake(METALAKE_NAME)
            .withBasicAuth(eve, EVE_PASSWORD)
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
  //  #71 — User in two groups gets union of privileges
  // ──────────────────────────────────────────────────────────────────────────
  @Test
  @DisplayName("#71 User in two groups gets the union of all granted privileges")
  void testUnionOfGroupPrivileges() throws Exception {
    String devTeam = "dev_team";
    String analystTeam = "analyst_team";
    String alice = "alice";

    String roleA = "r_select_a";
    String roleB = "r_select_b";

    String tableA = "tbl_71_a_" + System.nanoTime();
    String tableB = "tbl_71_b_" + System.nanoTime();

    // IDP: alice joins BOTH groups
    createIdpUser(alice, ALICE_PASSWORD);
    createIdpGroup(devTeam);
    createIdpGroup(analystTeam);
    addIdpUsersToGroup(devTeam, alice);
    addIdpUsersToGroup(analystTeam, alice);

    metalake.addUser(alice);
    metalake.addGroup(devTeam);
    metalake.addGroup(analystTeam);

    // Two fixture tables
    Column[] columns = {Column.of("id", Types.LongType.get(), "row id")};
    NameIdentifier tableAIdent = NameIdentifier.of(SCHEMA_NAME, tableA);
    NameIdentifier tableBIdent = NameIdentifier.of(SCHEMA_NAME, tableB);
    catalog.asTableCatalog().createTable(tableAIdent, columns, "fixture A", Maps.newHashMap());
    catalog.asTableCatalog().createTable(tableBIdent, columns, "fixture B", Maps.newHashMap());

    try {
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

      GravitinoClient aliceClient =
          GravitinoClient.builder(gravitinoUri)
              .withMetalake(METALAKE_NAME)
              .withBasicAuth(alice, ALICE_PASSWORD)
              .build();
      try {
        Assertions.assertDoesNotThrow(
            () ->
                aliceClient
                    .loadMetalake(METALAKE_NAME)
                    .loadCatalog(CATALOG_NAME)
                    .asTableCatalog()
                    .loadTable(tableAIdent),
            "loadTable(tableA) should succeed via dev_team");
        Assertions.assertDoesNotThrow(
            () ->
                aliceClient
                    .loadMetalake(METALAKE_NAME)
                    .loadCatalog(CATALOG_NAME)
                    .asTableCatalog()
                    .loadTable(tableBIdent),
            "loadTable(tableB) should succeed via analyst_team");
      } finally {
        aliceClient.close();
      }
    } finally {
      try {
        catalog.asTableCatalog().dropTable(tableAIdent);
      } catch (Exception e) {
        LOG.warn("Failed to drop fixture table A", e);
      }
      try {
        catalog.asTableCatalog().dropTable(tableBIdent);
      } catch (Exception e) {
        LOG.warn("Failed to drop fixture table B", e);
      }
    }
  }

  // ──────────────────────────────────────────────────────────────────────────
  //  #72 — revokeRolesFromGroup invalidates authorization
  // ──────────────────────────────────────────────────────────────────────────
  @Test
  @DisplayName("#72 revokeRolesFromGroup is reflected on the very next call")
  void testRevokeRoleFromGroupInvalidatesCache() throws Exception {
    String groupName = "dev_team";
    String roleName = "r_select";
    String alice = "alice";

    createIdpUser(alice, ALICE_PASSWORD);
    createIdpGroup(groupName);
    addIdpUsersToGroup(groupName, alice);

    metalake.addUser(alice);
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

    NameIdentifier tableIdent = NameIdentifier.of(SCHEMA_NAME, TABLE_NAME);

    GravitinoClient aliceClient =
        GravitinoClient.builder(gravitinoUri)
            .withMetalake(METALAKE_NAME)
            .withBasicAuth(alice, ALICE_PASSWORD)
            .build();
    try {
      // Pre-revoke: should succeed
      Assertions.assertDoesNotThrow(
          () ->
              aliceClient
                  .loadMetalake(METALAKE_NAME)
                  .loadCatalog(CATALOG_NAME)
                  .asTableCatalog()
                  .loadTable(tableIdent),
          "loadTable should succeed before revoke");

      // Revoke role from group
      metalake.revokeRolesFromGroup(Lists.newArrayList(roleName), groupName);

      // Post-revoke: must fail
      Assertions.assertThrows(
          ForbiddenException.class,
          () ->
              aliceClient
                  .loadMetalake(METALAKE_NAME)
                  .loadCatalog(CATALOG_NAME)
                  .asTableCatalog()
                  .loadTable(tableIdent),
          "loadTable must fail after revokeRolesFromGroup");
    } finally {
      aliceClient.close();
    }
  }

  // ──────────────────────────────────────────────────────────────────────────
  //  #73 — User-level DENY overrides group-inherited ALLOW
  // ──────────────────────────────────────────────────────────────────────────
  @Test
  @DisplayName("#73 User-level DENY overrides group-inherited ALLOW")
  void testUserDenyOverridesGroupAllow() throws Exception {
    String groupName = "dev_team";
    String roleSelect = "r_select";
    String roleDenyAlice = "r_deny_alice";
    String alice = "alice";

    String bob = "bob";

    // IDP: alice + bob both join dev_team
    createIdpUser(alice, ALICE_PASSWORD);
    createIdpUser(bob, BOB_PASSWORD);
    createIdpGroup(groupName);
    addIdpUsersToGroup(groupName, alice, bob);

    metalake.addUser(alice);
    metalake.addUser(bob);
    metalake.addGroup(groupName);

    // Group-level ALLOW
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

    // User-level DENY granted directly to alice
    SecurableObject tableDenyObj =
        SecurableObjects.ofTable(
            schemaObj, TABLE_NAME, Lists.newArrayList(Privileges.SelectTable.deny()));
    metalake.createRole(
        roleDenyAlice, Maps.newHashMap(), Lists.newArrayList(catalogObj, schemaObj, tableDenyObj));
    metalake.grantRolesToUser(Lists.newArrayList(roleDenyAlice), alice);

    NameIdentifier tableIdent = NameIdentifier.of(SCHEMA_NAME, TABLE_NAME);

    // alice: user-level DENY overrides group-level ALLOW
    GravitinoClient aliceClient =
        GravitinoClient.builder(gravitinoUri)
            .withMetalake(METALAKE_NAME)
            .withBasicAuth(alice, ALICE_PASSWORD)
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
          "Alice's user-level DENY must override dev_team's ALLOW");
    } finally {
      aliceClient.close();
    }

    // bob (control): same group, no user-level DENY → success
    GravitinoClient bobClient =
        GravitinoClient.builder(gravitinoUri)
            .withMetalake(METALAKE_NAME)
            .withBasicAuth(bob, BOB_PASSWORD)
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
  //  #74 — Remove user from IDP group revokes inherited privileges
  // ──────────────────────────────────────────────────────────────────────────
  @Test
  @DisplayName("#74 Removing a user from IDP group revokes inherited privileges on the next call")
  void testRemoveUserFromIdpGroupRevokesPrivileges() throws Exception {
    String groupName = "dev_team";
    String roleName = "r_select";
    String alice = "alice";

    String bob = "bob";

    // IDP: alice + bob both join dev_team
    createIdpUser(alice, ALICE_PASSWORD);
    createIdpUser(bob, BOB_PASSWORD);
    createIdpGroup(groupName);
    addIdpUsersToGroup(groupName, alice, bob);

    metalake.addUser(alice);
    metalake.addUser(bob);
    metalake.addGroup(groupName);

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

    // Initial state: alice can loadTable
    GravitinoClient aliceClientBefore =
        GravitinoClient.builder(gravitinoUri)
            .withMetalake(METALAKE_NAME)
            .withBasicAuth(alice, ALICE_PASSWORD)
            .build();
    try {
      Assertions.assertDoesNotThrow(
          () ->
              aliceClientBefore
                  .loadMetalake(METALAKE_NAME)
                  .loadCatalog(CATALOG_NAME)
                  .asTableCatalog()
                  .loadTable(tableIdent),
          "Alice should be able to loadTable while in dev_team");
    } finally {
      aliceClientBefore.close();
    }

    // Remove alice from dev_team via IDP API
    removeIdpUsersFromGroup(groupName, alice);

    // After removal: alice should be denied
    GravitinoClient aliceClientAfter =
        GravitinoClient.builder(gravitinoUri)
            .withMetalake(METALAKE_NAME)
            .withBasicAuth(alice, ALICE_PASSWORD)
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
          "Alice must be denied after being removed from dev_team");
    } finally {
      aliceClientAfter.close();
    }

    // bob (control): still in dev_team, still succeeds
    GravitinoClient bobClient =
        GravitinoClient.builder(gravitinoUri)
            .withMetalake(METALAKE_NAME)
            .withBasicAuth(bob, BOB_PASSWORD)
            .build();
    try {
      Assertions.assertDoesNotThrow(
          () ->
              bobClient
                  .loadMetalake(METALAKE_NAME)
                  .loadCatalog(CATALOG_NAME)
                  .asTableCatalog()
                  .loadTable(tableIdent),
          "Bob is still in dev_team; loadTable must still succeed");
    } finally {
      bobClient.close();
    }
  }

  // ==================== IDP API Helper methods ====================

  private void createIdpUser(String username, String password) throws Exception {
    String requestBody =
        MAPPER.createObjectNode().put("user", username).put("password", password).toString();
    HttpResponse<String> response = idpPost("/users", requestBody);
    Assertions.assertEquals(
        200, response.statusCode(), "Failed to create IDP user: " + response.body());
    createdIdpUsers.add(username);
  }

  private void createIdpGroup(String groupName) throws Exception {
    String requestBody = MAPPER.createObjectNode().put("group", groupName).toString();
    HttpResponse<String> response = idpPost("/groups", requestBody);
    Assertions.assertEquals(
        200, response.statusCode(), "Failed to create IDP group: " + response.body());
    createdIdpGroups.add(groupName);
  }

  private void addIdpUsersToGroup(String groupName, String... users) throws Exception {
    ObjectNode body = MAPPER.createObjectNode();
    ArrayNode usersToAdd = body.putArray("usersToAdd");
    for (String user : users) {
      usersToAdd.add(user);
    }
    HttpResponse<String> response = idpPut("/groups/" + groupName + "/users", body.toString());
    Assertions.assertEquals(
        200, response.statusCode(), "Failed to add users to IDP group: " + response.body());
  }

  private void removeIdpUsersFromGroup(String groupName, String... users) throws Exception {
    ObjectNode body = MAPPER.createObjectNode();
    ArrayNode usersToRemove = body.putArray("usersToRemove");
    for (String user : users) {
      usersToRemove.add(user);
    }
    HttpResponse<String> response = idpPut("/groups/" + groupName + "/users", body.toString());
    Assertions.assertEquals(
        200, response.statusCode(), "Failed to remove users from IDP group: " + response.body());
  }

  private static HttpResponse<String> idpPost(String path, String body) throws Exception {
    HttpRequest request =
        HttpRequest.newBuilder()
            .uri(URI.create(idpApiBase + path))
            .header("Accept", ACCEPT)
            .header("Content-Type", ACCEPT)
            .header("Authorization", basicAuth(ADMIN, adminPassword))
            .POST(HttpRequest.BodyPublishers.ofString(body))
            .build();
    return httpClient.send(request, HttpResponse.BodyHandlers.ofString());
  }

  private static HttpResponse<String> idpPut(String path, String body) throws Exception {
    HttpRequest request =
        HttpRequest.newBuilder()
            .uri(URI.create(idpApiBase + path))
            .header("Accept", ACCEPT)
            .header("Content-Type", ACCEPT)
            .header("Authorization", basicAuth(ADMIN, adminPassword))
            .PUT(HttpRequest.BodyPublishers.ofString(body))
            .build();
    return httpClient.send(request, HttpResponse.BodyHandlers.ofString());
  }

  private static void deleteIdpGroup(String groupName, boolean force) throws Exception {
    HttpRequest request =
        HttpRequest.newBuilder()
            .uri(URI.create(idpApiBase + "/groups/" + groupName + "?force=" + force))
            .header("Accept", ACCEPT)
            .header("Authorization", basicAuth(ADMIN, adminPassword))
            .DELETE()
            .build();
    HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
    LOG.info(
        "Cleanup IDP group '{}' (force={}): status={}", groupName, force, response.statusCode());
  }

  private static void deleteIdpUser(String username) throws Exception {
    HttpRequest request =
        HttpRequest.newBuilder()
            .uri(URI.create(idpApiBase + "/users/" + username))
            .header("Accept", ACCEPT)
            .header("Authorization", basicAuth(ADMIN, adminPassword))
            .DELETE()
            .build();
    HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
    LOG.info("Cleanup IDP user '{}': status={}", username, response.statusCode());
  }

  private static String basicAuth(String username, String password) {
    String credentials = username + ":" + password;
    return "Basic "
        + Base64.getEncoder().encodeToString(credentials.getBytes(StandardCharsets.UTF_8));
  }
}
