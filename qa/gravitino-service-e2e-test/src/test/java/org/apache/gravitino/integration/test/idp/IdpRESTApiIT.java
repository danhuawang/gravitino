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
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/** E2E tests for built-in IDP REST APIs (user and group operations). */
@DisplayName("Built-in IDP REST API E2E Tests")
public class IdpRESTApiIT {

  private static final Logger LOG = LoggerFactory.getLogger(IdpRESTApiIT.class);
  private static final String ACCEPT = "application/vnd.gravitino.v1+json";
  private static final String ADMIN = "admin";
  private static final ObjectMapper MAPPER = new ObjectMapper();

  private static String adminPassword;
  private static String idpApiBase;
  private static HttpClient httpClient;

  /** Track created users and groups for cleanup. */
  private final List<String> createdUsers = new ArrayList<>();

  private final List<String> createdGroups = new ArrayList<>();

  @BeforeAll
  public static void setup() {
    String gravitinoUri = System.getProperty("gravitino.uri", "http://localhost:30090");
    adminPassword = System.getProperty("gravitino.admin.password", "Passw0rd-Admin12");
    idpApiBase = gravitinoUri + "/api/idp";
    httpClient = HttpClient.newHttpClient();
    LOG.info("IdpRESTApiIT setup: idpApiBase={}", idpApiBase);
  }

  @AfterEach
  public void cleanup() throws Exception {
    // Force-delete groups first (force=true to handle non-empty groups)
    for (String group : createdGroups) {
      deleteGroup(group, true);
    }
    createdGroups.clear();

    // Then delete users
    for (String user : createdUsers) {
      deleteUser(user);
    }
    createdUsers.clear();
  }

  // ==================== User Operations (#1-#7) ====================

  @Test
  @DisplayName("#1 Create user - POST /api/idp/users returns 200 with user name and empty groups")
  void testCreateUser() throws Exception {
    String username = "e2e-test-user-" + System.currentTimeMillis();
    String password = "Passw0rd-E2E-Test1";

    String requestBody =
        MAPPER.createObjectNode().put("user", username).put("password", password).toString();

    HttpResponse<String> response = post("/users", requestBody);
    Assertions.assertEquals(200, response.statusCode(), "Expected 200 but got: " + response.body());

    JsonNode responseJson = MAPPER.readTree(response.body());
    Assertions.assertEquals(0, responseJson.get("code").asInt(), response.body());

    JsonNode userNode = responseJson.get("user");
    Assertions.assertNotNull(userNode, "Missing 'user' field: " + response.body());
    Assertions.assertEquals(username, userNode.get("name").asText());

    JsonNode groupsNode = userNode.get("groups");
    Assertions.assertNotNull(groupsNode, "Missing 'groups' field: " + response.body());
    Assertions.assertTrue(groupsNode.isArray());
    Assertions.assertEquals(0, groupsNode.size(), "groups should be empty for a new user");

    createdUsers.add(username);
    LOG.info("Successfully created user: {}", username);
  }

  @Test
  @DisplayName("#2 Get user - GET /api/idp/users/{user} returns 200 with user info")
  void testGetUser() throws Exception {
    String username = "e2e-test-user-" + System.currentTimeMillis();
    String password = "Passw0rd-E2E-Test1";

    // Create user first
    String requestBody =
        MAPPER.createObjectNode().put("user", username).put("password", password).toString();
    HttpResponse<String> createResp = post("/users", requestBody);
    Assertions.assertEquals(200, createResp.statusCode(), createResp.body());
    createdUsers.add(username);

    // GET the user
    HttpResponse<String> response = get("/users/" + username);
    Assertions.assertEquals(200, response.statusCode(), "Expected 200 but got: " + response.body());

    JsonNode responseJson = MAPPER.readTree(response.body());
    Assertions.assertEquals(0, responseJson.get("code").asInt(), response.body());

    JsonNode userNode = responseJson.get("user");
    Assertions.assertNotNull(userNode, "Missing 'user' field: " + response.body());
    Assertions.assertEquals(username, userNode.get("name").asText());

    JsonNode groupsNode = userNode.get("groups");
    Assertions.assertNotNull(groupsNode, "Missing 'groups' field: " + response.body());
    Assertions.assertTrue(groupsNode.isArray());

    LOG.info("Successfully retrieved user: {}", username);
  }

  @Test
  @DisplayName("#3 Change password - PUT /api/idp/users/{user} returns 200, new password works")
  void testChangePassword() throws Exception {
    String username = "e2e-test-user-" + System.currentTimeMillis();
    String password = "Passw0rd-E2E-Test1";
    String newPassword = "Passw0rd-E2E-New01";

    // Create user
    String requestBody =
        MAPPER.createObjectNode().put("user", username).put("password", password).toString();
    HttpResponse<String> createResp = post("/users", requestBody);
    Assertions.assertEquals(200, createResp.statusCode(), createResp.body());
    createdUsers.add(username);

    // Change password via PUT (admin operation)
    String changeBody = MAPPER.createObjectNode().put("password", newPassword).toString();
    HttpResponse<String> response = put("/users/" + username, changeBody);
    Assertions.assertEquals(200, response.statusCode(), "Expected 200 but got: " + response.body());

    // Verify new password works: regular user can authenticate but gets 403 on management API
    // This confirms the new credentials are valid (authenticated) even though access is forbidden
    HttpResponse<String> getResp = getWithAuth("/users/" + username, username, newPassword);
    Assertions.assertEquals(
        403,
        getResp.statusCode(),
        "Regular user should be authenticated but forbidden from management API: "
            + getResp.body());

    // Verify old password no longer works (should get 401 Unauthorized)
    HttpResponse<String> oldResp = getWithAuth("/users/" + username, username, password);
    Assertions.assertEquals(401, oldResp.statusCode(), "Old password should be rejected");

    // Verify admin can still retrieve user info after password change
    HttpResponse<String> adminResp = get("/users/" + username);
    Assertions.assertEquals(
        200, adminResp.statusCode(), "Admin should be able to get user: " + adminResp.body());

    LOG.info("Successfully changed password for user: {}", username);
  }

  @Test
  @DisplayName("#4 Delete user - DELETE /api/idp/users/{user} returns 200, removed=true")
  void testDeleteUser() throws Exception {
    String username = "e2e-test-user-" + System.currentTimeMillis();
    String password = "Passw0rd-E2E-Test1";

    String requestBody =
        MAPPER.createObjectNode().put("user", username).put("password", password).toString();
    HttpResponse<String> createResp = post("/users", requestBody);
    Assertions.assertEquals(200, createResp.statusCode(), createResp.body());

    HttpResponse<String> response = delete("/users/" + username);
    Assertions.assertEquals(200, response.statusCode(), response.body());

    JsonNode responseJson = MAPPER.readTree(response.body());
    Assertions.assertTrue(responseJson.get("removed").asBoolean(), "removed should be true");
    // No cleanup needed since we just deleted
  }

  @Test
  @DisplayName("#5 Delete user again - DELETE returns 200, removed=false")
  void testDeleteUserAgain() throws Exception {
    String username = "e2e-test-user-" + System.currentTimeMillis();
    String password = "Passw0rd-E2E-Test1";

    String requestBody =
        MAPPER.createObjectNode().put("user", username).put("password", password).toString();
    HttpResponse<String> createResp = post("/users", requestBody);
    Assertions.assertEquals(200, createResp.statusCode(), createResp.body());

    // First delete
    HttpResponse<String> first = delete("/users/" + username);
    Assertions.assertEquals(200, first.statusCode(), first.body());
    Assertions.assertTrue(MAPPER.readTree(first.body()).get("removed").asBoolean());

    // Second delete
    HttpResponse<String> second = delete("/users/" + username);
    Assertions.assertEquals(200, second.statusCode(), second.body());
    Assertions.assertFalse(
        MAPPER.readTree(second.body()).get("removed").asBoolean(), "removed should be false");
  }

  @Test
  @DisplayName("#6 Get non-existent user - GET returns 404")
  void testGetNonExistentUser() throws Exception {
    HttpResponse<String> response = get("/users/non-existent-user-" + System.currentTimeMillis());
    Assertions.assertEquals(404, response.statusCode(), response.body());
  }

  @Test
  @DisplayName("#7 Create already-existing user - POST returns 409")
  void testCreateDuplicateUser() throws Exception {
    String username = "e2e-test-user-" + System.currentTimeMillis();
    String password = "Passw0rd-E2E-Test1";

    String requestBody =
        MAPPER.createObjectNode().put("user", username).put("password", password).toString();
    HttpResponse<String> createResp = post("/users", requestBody);
    Assertions.assertEquals(200, createResp.statusCode(), createResp.body());
    createdUsers.add(username);

    // Try to create the same user again
    HttpResponse<String> response = post("/users", requestBody);
    Assertions.assertEquals(409, response.statusCode(), response.body());
  }

  // ==================== Group Operations (#8-#17) ====================

  @Test
  @DisplayName("#8 Create group - POST /api/idp/groups returns 200 with group name and empty users")
  void testCreateGroup() throws Exception {
    String groupName = "e2e-test-group-" + System.currentTimeMillis();

    String requestBody = MAPPER.createObjectNode().put("group", groupName).toString();

    HttpResponse<String> response = post("/groups", requestBody);
    Assertions.assertEquals(200, response.statusCode(), "Expected 200 but got: " + response.body());

    JsonNode responseJson = MAPPER.readTree(response.body());
    Assertions.assertEquals(0, responseJson.get("code").asInt(), response.body());

    JsonNode groupNode = responseJson.get("group");
    Assertions.assertNotNull(groupNode, "Missing 'group' field: " + response.body());
    Assertions.assertEquals(groupName, groupNode.get("name").asText());

    JsonNode usersNode = groupNode.get("users");
    Assertions.assertNotNull(usersNode, "Missing 'users' field: " + response.body());
    Assertions.assertTrue(usersNode.isArray());
    Assertions.assertEquals(0, usersNode.size(), "users should be empty for a new group");

    createdGroups.add(groupName);
    LOG.info("Successfully created group: {}", groupName);
  }

  @Test
  @DisplayName("#9 Get group - GET /api/idp/groups/{group} returns 200 with group info")
  void testGetGroup() throws Exception {
    String groupName = "e2e-test-group-" + System.currentTimeMillis();

    // Create group first
    createGroupHelper(groupName);

    // GET the group
    HttpResponse<String> response = get("/groups/" + groupName);
    Assertions.assertEquals(200, response.statusCode(), "Expected 200 but got: " + response.body());

    JsonNode responseJson = MAPPER.readTree(response.body());
    Assertions.assertEquals(0, responseJson.get("code").asInt(), response.body());

    JsonNode groupNode = responseJson.get("group");
    Assertions.assertNotNull(groupNode, "Missing 'group' field: " + response.body());
    Assertions.assertEquals(groupName, groupNode.get("name").asText());

    JsonNode usersNode = groupNode.get("users");
    Assertions.assertNotNull(usersNode, "Missing 'users' field: " + response.body());
    Assertions.assertTrue(usersNode.isArray());

    LOG.info("Successfully retrieved group: {}", groupName);
  }

  @Test
  @DisplayName("#10 Add members to group - PUT /api/idp/groups/{group}/users returns 200")
  void testAddMembersToGroup() throws Exception {
    String groupName = "e2e-test-group-" + System.currentTimeMillis();
    String user1 = "e2e-test-user1-" + System.currentTimeMillis();
    String user2 = "e2e-test-user2-" + System.currentTimeMillis();

    // Create group and users
    createGroupHelper(groupName);
    createUserHelper(user1);
    createUserHelper(user2);

    // Add members to group
    ObjectNode body = MAPPER.createObjectNode();
    ArrayNode usersToAdd = body.putArray("usersToAdd");
    usersToAdd.add(user1);
    usersToAdd.add(user2);

    HttpResponse<String> response = put("/groups/" + groupName + "/users", body.toString());
    Assertions.assertEquals(200, response.statusCode(), "Expected 200 but got: " + response.body());

    JsonNode responseJson = MAPPER.readTree(response.body());
    Assertions.assertEquals(0, responseJson.get("code").asInt(), response.body());

    // Verify group now contains the users
    HttpResponse<String> getResp = get("/groups/" + groupName);
    JsonNode getJson = MAPPER.readTree(getResp.body());
    JsonNode usersNode = getJson.get("group").get("users");
    Assertions.assertNotNull(usersNode, "Missing 'users' field");
    Assertions.assertEquals(2, usersNode.size(), "Group should have 2 members");

    List<String> memberNames = new ArrayList<>();
    usersNode.forEach(n -> memberNames.add(n.asText()));
    Assertions.assertTrue(memberNames.contains(user1), "Group should contain user1");
    Assertions.assertTrue(memberNames.contains(user2), "Group should contain user2");

    LOG.info("Successfully added members to group: {}", groupName);
  }

  @Test
  @DisplayName("#11 Remove members from group - PUT /api/idp/groups/{group}/users returns 200")
  void testRemoveMembersFromGroup() throws Exception {
    String groupName = "e2e-test-group-" + System.currentTimeMillis();
    String user1 = "e2e-test-user1-" + System.currentTimeMillis();
    String user2 = "e2e-test-user2-" + System.currentTimeMillis();

    // Create group and users, then add them
    createGroupHelper(groupName);
    createUserHelper(user1);
    createUserHelper(user2);
    addUsersToGroupHelper(groupName, user1, user2);

    // Remove user1 from the group
    ObjectNode body = MAPPER.createObjectNode();
    ArrayNode usersToRemove = body.putArray("usersToRemove");
    usersToRemove.add(user1);

    HttpResponse<String> response = put("/groups/" + groupName + "/users", body.toString());
    Assertions.assertEquals(200, response.statusCode(), "Expected 200 but got: " + response.body());

    JsonNode responseJson = MAPPER.readTree(response.body());
    Assertions.assertEquals(0, responseJson.get("code").asInt(), response.body());

    // Verify group now contains only user2
    HttpResponse<String> getResp = get("/groups/" + groupName);
    JsonNode getJson = MAPPER.readTree(getResp.body());
    JsonNode usersNode = getJson.get("group").get("users");
    Assertions.assertNotNull(usersNode, "Missing 'users' field");
    Assertions.assertEquals(1, usersNode.size(), "Group should have 1 member after removal");

    List<String> memberNames = new ArrayList<>();
    usersNode.forEach(n -> memberNames.add(n.asText()));
    Assertions.assertFalse(memberNames.contains(user1), "user1 should have been removed");
    Assertions.assertTrue(memberNames.contains(user2), "user2 should still be in the group");

    LOG.info("Successfully removed members from group: {}", groupName);
  }

  @Test
  @DisplayName(
      "#12 Add and remove members simultaneously - PUT /api/idp/groups/{group}/users returns 200")
  void testAddAndRemoveMembersSimultaneously() throws Exception {
    String groupName = "e2e-test-group-" + System.currentTimeMillis();
    String user1 = "e2e-test-user1-" + System.currentTimeMillis();
    String user2 = "e2e-test-user2-" + System.currentTimeMillis();
    String user3 = "e2e-test-user3-" + System.currentTimeMillis();

    // Create group and users, add user1 and user2 initially
    createGroupHelper(groupName);
    createUserHelper(user1);
    createUserHelper(user2);
    createUserHelper(user3);
    addUsersToGroupHelper(groupName, user1, user2);

    // Simultaneously add user3 and remove user1
    ObjectNode body = MAPPER.createObjectNode();
    ArrayNode usersToAdd = body.putArray("usersToAdd");
    usersToAdd.add(user3);
    ArrayNode usersToRemove = body.putArray("usersToRemove");
    usersToRemove.add(user1);

    HttpResponse<String> response = put("/groups/" + groupName + "/users", body.toString());
    Assertions.assertEquals(200, response.statusCode(), "Expected 200 but got: " + response.body());

    JsonNode responseJson = MAPPER.readTree(response.body());
    Assertions.assertEquals(0, responseJson.get("code").asInt(), response.body());

    // Verify the result: group should contain user2 and user3, but not user1
    HttpResponse<String> getResp = get("/groups/" + groupName);
    JsonNode getJson = MAPPER.readTree(getResp.body());
    JsonNode usersNode = getJson.get("group").get("users");
    Assertions.assertNotNull(usersNode, "Missing 'users' field");
    Assertions.assertEquals(2, usersNode.size(), "Group should have 2 members");

    List<String> memberNames = new ArrayList<>();
    usersNode.forEach(n -> memberNames.add(n.asText()));
    Assertions.assertFalse(memberNames.contains(user1), "user1 should have been removed");
    Assertions.assertTrue(memberNames.contains(user2), "user2 should still be present");
    Assertions.assertTrue(memberNames.contains(user3), "user3 should have been added");

    LOG.info("Successfully added and removed members simultaneously for group: {}", groupName);
  }

  @Test
  @DisplayName(
      "#13 Delete empty group (force=false) - DELETE /api/idp/groups/{group}?force=false"
          + " returns 200, removed=true")
  void testDeleteEmptyGroupNotForced() throws Exception {
    String groupName = "e2e-test-group-" + System.currentTimeMillis();

    // Create an empty group
    createGroupHelper(groupName);

    // Delete with force=false
    HttpResponse<String> response = delete("/groups/" + groupName + "?force=false");
    Assertions.assertEquals(200, response.statusCode(), "Expected 200 but got: " + response.body());

    JsonNode responseJson = MAPPER.readTree(response.body());
    Assertions.assertEquals(0, responseJson.get("code").asInt(), response.body());
    Assertions.assertTrue(responseJson.get("removed").asBoolean(), "removed should be true");

    // Remove from cleanup list since we just deleted it
    createdGroups.remove(groupName);

    // Verify the group no longer exists
    HttpResponse<String> getResp = get("/groups/" + groupName);
    Assertions.assertEquals(
        404, getResp.statusCode(), "Group should not exist after deletion: " + getResp.body());

    LOG.info("Successfully deleted empty group (force=false): {}", groupName);
  }

  @Test
  @DisplayName(
      "#14 Delete non-empty group (force=false) - DELETE /api/idp/groups/{group}?force=false"
          + " returns 405, UNSUPPORTED_OPERATION_CODE")
  void testDeleteNonEmptyGroupNotForced() throws Exception {
    String groupName = "e2e-test-group-" + System.currentTimeMillis();
    String user1 = "e2e-test-user1-" + System.currentTimeMillis();

    // Create group with a member
    createGroupHelper(groupName);
    createUserHelper(user1);
    addUsersToGroupHelper(groupName, user1);

    // Try to delete with force=false
    HttpResponse<String> response = delete("/groups/" + groupName + "?force=false");
    Assertions.assertEquals(405, response.statusCode(), "Expected 405 but got: " + response.body());

    JsonNode responseJson = MAPPER.readTree(response.body());
    // Verify error code indicates unsupported operation
    Assertions.assertNotEquals(
        0, responseJson.get("code").asInt(), "Should have a non-zero error code");

    // Verify the group still exists
    HttpResponse<String> getResp = get("/groups/" + groupName);
    Assertions.assertEquals(
        200, getResp.statusCode(), "Group should still exist: " + getResp.body());

    LOG.info("Correctly rejected deletion of non-empty group (force=false): {}", groupName);
  }

  @Test
  @DisplayName(
      "#15 Force-delete non-empty group (force=true) - DELETE /api/idp/groups/{group}?force=true"
          + " returns 200, removed=true")
  void testForceDeleteNonEmptyGroup() throws Exception {
    String groupName = "e2e-test-group-" + System.currentTimeMillis();
    String user1 = "e2e-test-user1-" + System.currentTimeMillis();

    // Create group with a member
    createGroupHelper(groupName);
    createUserHelper(user1);
    addUsersToGroupHelper(groupName, user1);

    // Force-delete
    HttpResponse<String> response = delete("/groups/" + groupName + "?force=true");
    Assertions.assertEquals(200, response.statusCode(), "Expected 200 but got: " + response.body());

    JsonNode responseJson = MAPPER.readTree(response.body());
    Assertions.assertEquals(0, responseJson.get("code").asInt(), response.body());
    Assertions.assertTrue(responseJson.get("removed").asBoolean(), "removed should be true");

    // Remove from cleanup list since we just deleted it
    createdGroups.remove(groupName);

    // Verify the group no longer exists
    HttpResponse<String> getResp = get("/groups/" + groupName);
    Assertions.assertEquals(
        404,
        getResp.statusCode(),
        "Group should not exist after force deletion: " + getResp.body());

    LOG.info("Successfully force-deleted non-empty group: {}", groupName);
  }

  @Test
  @DisplayName(
      "#16 Get non-existent group - GET /api/idp/groups/missing returns 404, NOT_FOUND_CODE")
  void testGetNonExistentGroup() throws Exception {
    String groupName = "non-existent-group-" + System.currentTimeMillis();

    HttpResponse<String> response = get("/groups/" + groupName);
    Assertions.assertEquals(404, response.statusCode(), "Expected 404 but got: " + response.body());

    JsonNode responseJson = MAPPER.readTree(response.body());
    Assertions.assertNotEquals(
        0, responseJson.get("code").asInt(), "Should have a non-zero error code");

    LOG.info("Correctly received 404 for non-existent group: {}", groupName);
  }

  @Test
  @DisplayName(
      "#17 Create already-existing group - POST /api/idp/groups returns 409,"
          + " ALREADY_EXISTS_CODE")
  void testCreateAlreadyExistingGroup() throws Exception {
    String groupName = "e2e-test-group-" + System.currentTimeMillis();

    // Create group first
    createGroupHelper(groupName);

    // Try to create again
    String requestBody = MAPPER.createObjectNode().put("group", groupName).toString();
    HttpResponse<String> response = post("/groups", requestBody);
    Assertions.assertEquals(409, response.statusCode(), "Expected 409 but got: " + response.body());

    JsonNode responseJson = MAPPER.readTree(response.body());
    Assertions.assertNotEquals(
        0, responseJson.get("code").asInt(), "Should have a non-zero error code");

    LOG.info("Correctly received 409 for already-existing group: {}", groupName);
  }

  // ==================== Auth/Permission Tests (#20) ====================

  @Test
  @DisplayName("#20 Missing Authorization header is rejected - returns 403, FORBIDDEN_CODE")
  void testMissingAuthorizationHeaderRejected() throws Exception {
    String username = "e2e-test-user-" + System.currentTimeMillis();
    String password = "Passw0rd-E2E-Test1";

    // Create a user first so the path is valid
    String requestBody =
        MAPPER.createObjectNode().put("user", username).put("password", password).toString();
    HttpResponse<String> createResp = post("/users", requestBody);
    Assertions.assertEquals(200, createResp.statusCode(), createResp.body());
    createdUsers.add(username);

    // Access /api/idp/users/{user} without Authorization header
    HttpRequest request =
        HttpRequest.newBuilder()
            .uri(URI.create(idpApiBase + "/users/" + username))
            .header("Accept", ACCEPT)
            .GET()
            .build();
    HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());

    Assertions.assertEquals(
        403,
        response.statusCode(),
        "Missing Authorization header should be rejected: " + response.body());

    JsonNode responseJson = MAPPER.readTree(response.body());
    Assertions.assertEquals(1008, responseJson.get("code").asInt(), response.body());

    LOG.info("Correctly rejected request without Authorization header");
  }

  // ==================== Setup helpers ====================

  private void createGroupHelper(String groupName) throws Exception {
    String requestBody = MAPPER.createObjectNode().put("group", groupName).toString();
    HttpResponse<String> response = post("/groups", requestBody);
    Assertions.assertEquals(
        200, response.statusCode(), "Failed to create group: " + response.body());
    createdGroups.add(groupName);
  }

  private void createUserHelper(String username) throws Exception {
    String password = "Passw0rd-E2E-Test1";
    String requestBody =
        MAPPER.createObjectNode().put("user", username).put("password", password).toString();
    HttpResponse<String> response = post("/users", requestBody);
    Assertions.assertEquals(
        200, response.statusCode(), "Failed to create user: " + response.body());
    createdUsers.add(username);
  }

  private void addUsersToGroupHelper(String groupName, String... users) throws Exception {
    ObjectNode body = MAPPER.createObjectNode();
    ArrayNode usersToAdd = body.putArray("usersToAdd");
    for (String user : users) {
      usersToAdd.add(user);
    }
    HttpResponse<String> response = put("/groups/" + groupName + "/users", body.toString());
    Assertions.assertEquals(
        200, response.statusCode(), "Failed to add users to group: " + response.body());
  }

  // ==================== HTTP helper methods ====================

  private static HttpResponse<String> get(String path) throws Exception {
    return getWithAuth(path, ADMIN, adminPassword);
  }

  private static HttpResponse<String> getWithAuth(String path, String user, String password)
      throws Exception {
    HttpRequest request =
        HttpRequest.newBuilder()
            .uri(URI.create(idpApiBase + path))
            .header("Accept", ACCEPT)
            .header("Authorization", basicAuth(user, password))
            .GET()
            .build();
    return httpClient.send(request, HttpResponse.BodyHandlers.ofString());
  }

  private static HttpResponse<String> post(String path, String body) throws Exception {
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

  private static HttpResponse<String> put(String path, String body) throws Exception {
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

  private static HttpResponse<String> delete(String path) throws Exception {
    HttpRequest request =
        HttpRequest.newBuilder()
            .uri(URI.create(idpApiBase + path))
            .header("Accept", ACCEPT)
            .header("Authorization", basicAuth(ADMIN, adminPassword))
            .DELETE()
            .build();
    return httpClient.send(request, HttpResponse.BodyHandlers.ofString());
  }

  private static void deleteGroup(String groupName, boolean force) throws Exception {
    HttpRequest request =
        HttpRequest.newBuilder()
            .uri(URI.create(idpApiBase + "/groups/" + groupName + "?force=" + force))
            .header("Accept", ACCEPT)
            .header("Authorization", basicAuth(ADMIN, adminPassword))
            .DELETE()
            .build();
    HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
    LOG.info("Cleanup group '{}' (force={}): status={}", groupName, force, response.statusCode());
  }

  private static void deleteUser(String username) throws Exception {
    HttpResponse<String> response = delete("/users/" + username);
    LOG.info("Cleanup user '{}': status={}", username, response.statusCode());
  }

  private static String basicAuth(String username, String password) {
    String credentials = username + ":" + password;
    return "Basic "
        + Base64.getEncoder().encodeToString(credentials.getBytes(StandardCharsets.UTF_8));
  }
}
