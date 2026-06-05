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

/**
 * E2E tests for IDP password rule boundaries (test plan section 3.1, cases #32-#39). Reuses the K8s
 * deployment environment.
 */
@DisplayName("Built-in IDP Password Boundary E2E Tests")
public class IdpBoundariesIT {

  private static final Logger LOG = LoggerFactory.getLogger(IdpBoundariesIT.class);
  private static final String ACCEPT = "application/vnd.gravitino.v1+json";
  private static final String ADMIN = "admin";
  private static final ObjectMapper MAPPER = new ObjectMapper();

  private static String adminPassword;
  private static String idpApiBase;
  private static HttpClient httpClient;

  /** Track created users for cleanup. */
  private final List<String> createdUsers = new ArrayList<>();

  /** Track created groups for cleanup. */
  private final List<String> createdGroups = new ArrayList<>();

  @BeforeAll
  public static void setup() {
    String gravitinoUri = System.getProperty("gravitino.uri", "http://localhost:30090");
    adminPassword = System.getProperty("gravitino.admin.password", "Passw0rd-Admin12");
    idpApiBase = gravitinoUri + "/api/idp";
    httpClient = HttpClient.newHttpClient();
    LOG.info("IdpBoundariesIT setup: idpApiBase={}", idpApiBase);
  }

  @AfterEach
  public void cleanup() throws Exception {
    for (String group : createdGroups) {
      deleteGroup(group);
    }
    createdGroups.clear();

    for (String user : createdUsers) {
      deleteUser(user);
    }
    createdUsers.clear();
  }

  @Test
  @DisplayName("#32 Password exactly 12 characters - creation succeeds")
  void testPasswordExactly12Characters() throws Exception {
    String username = "e2e-pwd12-" + System.currentTimeMillis();
    // "Passw0rd-12!" is exactly 12 characters
    String password = "Passw0rd-12!";
    Assertions.assertEquals(
        12, password.length(), "Test precondition: password should be 12 chars");

    HttpResponse<String> response = createUserRequest(username, password);
    Assertions.assertEquals(
        200, response.statusCode(), "12-char password should succeed: " + response.body());

    JsonNode responseJson = MAPPER.readTree(response.body());
    Assertions.assertEquals(0, responseJson.get("code").asInt(), response.body());

    createdUsers.add(username);
    LOG.info("Successfully created user with 12-char password: {}", username);
  }

  @Test
  @DisplayName("#33 Password exactly 64 characters - creation succeeds")
  void testPasswordExactly64Characters() throws Exception {
    String username = "e2e-pwd64-" + System.currentTimeMillis();
    // Build a 64-character password that satisfies complexity rules:
    // uppercase, lowercase, digit, special char
    String password = "Aa1!abcdefghijklmnopqrstuvwxyz0123456789ABCDEFGHIJKLMNOPQRSTUVWX";
    Assertions.assertEquals(
        64, password.length(), "Test precondition: password should be 64 chars");

    HttpResponse<String> response = createUserRequest(username, password);
    Assertions.assertEquals(
        200, response.statusCode(), "64-char password should succeed: " + response.body());

    JsonNode responseJson = MAPPER.readTree(response.body());
    Assertions.assertEquals(0, responseJson.get("code").asInt(), response.body());

    createdUsers.add(username);
    LOG.info("Successfully created user with 64-char password: {}", username);
  }

  @Test
  @DisplayName("#34 Password 11 characters (too short) - returns 400")
  void testPasswordTooShort11Characters() throws Exception {
    String username = "e2e-pwd11-" + System.currentTimeMillis();
    // "Passw0rd-1!" is exactly 11 characters
    String password = "Passw0rd-1!";
    Assertions.assertEquals(
        11, password.length(), "Test precondition: password should be 11 chars");

    HttpResponse<String> response = createUserRequest(username, password);
    Assertions.assertEquals(
        400, response.statusCode(), "11-char password should be rejected: " + response.body());

    JsonNode responseJson = MAPPER.readTree(response.body());
    Assertions.assertNotEquals(
        0, responseJson.get("code").asInt(), "Should have a non-zero error code");

    LOG.info("Correctly rejected 11-char password for user: {}", username);
  }

  @Test
  @DisplayName("#35 Password 65 characters (too long) - returns 400")
  void testPasswordTooLong65Characters() throws Exception {
    String username = "e2e-pwd65-" + System.currentTimeMillis();
    // Build a 65-character password
    String password = "Aa1!abcdefghijklmnopqrstuvwxyz0123456789ABCDEFGHIJKLMNOPQRSTUVWXY";
    Assertions.assertEquals(
        65, password.length(), "Test precondition: password should be 65 chars");

    HttpResponse<String> response = createUserRequest(username, password);
    Assertions.assertEquals(
        400, response.statusCode(), "65-char password should be rejected: " + response.body());

    JsonNode responseJson = MAPPER.readTree(response.body());
    Assertions.assertNotEquals(
        0, responseJson.get("code").asInt(), "Should have a non-zero error code");

    LOG.info("Correctly rejected 65-char password for user: {}", username);
  }

  @Test
  @DisplayName("#36 Password is empty string - returns 400")
  void testPasswordEmptyString() throws Exception {
    String username = "e2e-pwdempty-" + System.currentTimeMillis();
    String password = "";

    HttpResponse<String> response = createUserRequest(username, password);
    Assertions.assertEquals(
        400, response.statusCode(), "Empty password should be rejected: " + response.body());

    JsonNode responseJson = MAPPER.readTree(response.body());
    Assertions.assertNotEquals(
        0, responseJson.get("code").asInt(), "Should have a non-zero error code");

    LOG.info("Correctly rejected empty password for user: {}", username);
  }

  @Test
  @DisplayName("#37 Password is only spaces - returns 400")
  void testPasswordOnlySpaces() throws Exception {
    String username = "e2e-pwdspaces-" + System.currentTimeMillis();
    String password = "   ";

    HttpResponse<String> response = createUserRequest(username, password);
    Assertions.assertEquals(
        400, response.statusCode(), "Spaces-only password should be rejected: " + response.body());

    JsonNode responseJson = MAPPER.readTree(response.body());
    Assertions.assertNotEquals(
        0, responseJson.get("code").asInt(), "Should have a non-zero error code");

    LOG.info("Correctly rejected spaces-only password for user: {}", username);
  }

  @Test
  @DisplayName("#38 Password with special characters (>12 chars) - creation succeeds")
  void testPasswordWithSpecialCharacters() throws Exception {
    String username = "e2e-pwdspecial-" + System.currentTimeMillis();
    // "P@ss!w0rd#$%^&*" is 15 characters, contains uppercase, lowercase, digit, specials
    String password = "P@ss!w0rd#$%^&*";
    Assertions.assertTrue(
        password.length() >= 12, "Test precondition: password should be >= 12 chars");

    HttpResponse<String> response = createUserRequest(username, password);
    Assertions.assertEquals(
        200,
        response.statusCode(),
        "Password with special characters should succeed: " + response.body());

    JsonNode responseJson = MAPPER.readTree(response.body());
    Assertions.assertEquals(0, responseJson.get("code").asInt(), response.body());

    createdUsers.add(username);
    LOG.info("Successfully created user with special-character password: {}", username);
  }

  @Test
  @DisplayName("#39 Password with Unicode characters (>12 chars) - 200 or implementation-dependent")
  void testPasswordWithUnicodeCharacters() throws Exception {
    String username = "e2e-pwdunicode-" + System.currentTimeMillis();
    // "密码Passw0rd-1" contains Chinese characters plus ASCII, total > 12 chars
    String password = "\u5BC6\u7801Passw0rd-1";
    Assertions.assertTrue(
        password.length() >= 12, "Test precondition: password should be >= 12 chars");

    HttpResponse<String> response = createUserRequest(username, password);

    // Unicode password handling is implementation-dependent:
    // - If the implementation counts code points / characters: may succeed (200)
    // - If the implementation rejects non-ASCII: may return 400
    int statusCode = response.statusCode();
    Assertions.assertTrue(
        statusCode == 200 || statusCode == 400,
        "Unicode password should return 200 or 400, got: " + statusCode + " " + response.body());

    if (statusCode == 200) {
      JsonNode responseJson = MAPPER.readTree(response.body());
      Assertions.assertEquals(0, responseJson.get("code").asInt(), response.body());
      createdUsers.add(username);
      LOG.info("Unicode password accepted for user: {}", username);
    } else {
      LOG.info("Unicode password rejected (implementation-dependent) for user: {}", username);
    }
  }

  // ==================== Username Boundary Tests (#40-#44) ====================

  @Test
  @DisplayName("#40 Username is empty - returns 400")
  void testUsernameEmpty() throws Exception {
    String username = "";
    String password = "Passw0rd-E2E-Test1";

    HttpResponse<String> response = createUserRequest(username, password);
    Assertions.assertEquals(
        400, response.statusCode(), "Empty username should be rejected: " + response.body());

    JsonNode responseJson = MAPPER.readTree(response.body());
    Assertions.assertNotEquals(
        0, responseJson.get("code").asInt(), "Should have a non-zero error code");

    LOG.info("Correctly rejected empty username");
  }

  @Test
  @DisplayName("#41 Username contains colon - returns 400 (colon not allowed)")
  void testUsernameContainsColon() throws Exception {
    String username = "user:name";
    String password = "Passw0rd-E2E-Test1";

    HttpResponse<String> response = createUserRequest(username, password);
    Assertions.assertEquals(
        400, response.statusCode(), "Username with colon should be rejected: " + response.body());

    JsonNode responseJson = MAPPER.readTree(response.body());
    Assertions.assertNotEquals(
        0, responseJson.get("code").asInt(), "Should have a non-zero error code");

    LOG.info("Correctly rejected username containing colon");
  }

  @Test
  @DisplayName("#42 Username is only spaces - returns 400")
  void testUsernameOnlySpaces() throws Exception {
    String username = "   ";
    String password = "Passw0rd-E2E-Test1";

    HttpResponse<String> response = createUserRequest(username, password);
    Assertions.assertEquals(
        400, response.statusCode(), "Spaces-only username should be rejected: " + response.body());

    JsonNode responseJson = MAPPER.readTree(response.body());
    Assertions.assertNotEquals(
        0, responseJson.get("code").asInt(), "Should have a non-zero error code");

    LOG.info("Correctly rejected spaces-only username");
  }

  @Test
  @DisplayName("#43 Username with special characters (no colon) - creation succeeds")
  void testUsernameWithSpecialCharactersNoColon() throws Exception {
    String username = "user-name_01.test";
    String password = "Passw0rd-E2E-Test1";

    HttpResponse<String> response = createUserRequest(username, password);
    Assertions.assertEquals(
        200,
        response.statusCode(),
        "Username with hyphens/underscores/dots should succeed: " + response.body());

    JsonNode responseJson = MAPPER.readTree(response.body());
    Assertions.assertEquals(0, responseJson.get("code").asInt(), response.body());

    JsonNode userNode = responseJson.get("user");
    Assertions.assertNotNull(userNode, "Missing 'user' field: " + response.body());
    Assertions.assertEquals(username, userNode.get("name").asText());

    createdUsers.add(username);
    LOG.info("Successfully created user with special-character username: {}", username);
  }

  @Test
  @DisplayName("#44 Very long username (256 chars) - returns 400")
  void testVeryLongUsername() throws Exception {
    // Build a 256-character username
    StringBuilder sb = new StringBuilder("e2e-longuser-");
    while (sb.length() < 256) {
      sb.append("abcdefghij");
    }
    String username = sb.substring(0, 256);
    Assertions.assertEquals(
        256, username.length(), "Test precondition: username should be 256 chars");

    String password = "Passw0rd-E2E-Test1";

    HttpResponse<String> response = createUserRequest(username, password);

    Assertions.assertEquals(
        400, response.statusCode(), "256-char username should be rejected: " + response.body());

    JsonNode responseJson = MAPPER.readTree(response.body());
    Assertions.assertNotEquals(
        0, responseJson.get("code").asInt(), "Should have a non-zero error code");

    LOG.info("Correctly rejected 256-char username (implementation limit)");
  }

  // ==================== Group Name Boundary Tests (#45-#46) ====================

  @Test
  @DisplayName("#45 Group name is empty - returns 400")
  void testGroupNameEmpty() throws Exception {
    String groupName = "";

    String requestBody = MAPPER.createObjectNode().put("group", groupName).toString();
    HttpResponse<String> response = post("/groups", requestBody);
    Assertions.assertEquals(
        400, response.statusCode(), "Empty group name should be rejected: " + response.body());

    JsonNode responseJson = MAPPER.readTree(response.body());
    Assertions.assertNotEquals(
        0, responseJson.get("code").asInt(), "Should have a non-zero error code");

    LOG.info("Correctly rejected empty group name");
  }

  @Test
  @DisplayName("#46 Group name with special characters - creation succeeds")
  void testGroupNameWithSpecialCharacters() throws Exception {
    String groupName = "group-name_01";

    String requestBody = MAPPER.createObjectNode().put("group", groupName).toString();
    HttpResponse<String> response = post("/groups", requestBody);
    Assertions.assertEquals(
        200,
        response.statusCode(),
        "Group name with hyphens/underscores should succeed: " + response.body());

    JsonNode responseJson = MAPPER.readTree(response.body());
    Assertions.assertEquals(0, responseJson.get("code").asInt(), response.body());

    JsonNode groupNode = responseJson.get("group");
    Assertions.assertNotNull(groupNode, "Missing 'group' field: " + response.body());
    Assertions.assertEquals(groupName, groupNode.get("name").asText());

    createdGroups.add(groupName);
    LOG.info("Successfully created group with special-character name: {}", groupName);
  }

  // ==================== Membership Boundary Tests (#47-#52) ====================

  @Test
  @DisplayName("#47 usersToAdd and usersToRemove both null (empty body) - returns 400")
  void testMembershipBothFieldsNull() throws Exception {
    String groupName = "e2e-test-group-" + System.currentTimeMillis();
    createGroup(groupName);

    // Send empty JSON body (neither usersToAdd nor usersToRemove)
    String body = "{}";
    HttpResponse<String> response = put("/groups/" + groupName + "/users", body);
    Assertions.assertEquals(
        400, response.statusCode(), "Both fields null should be rejected: " + response.body());

    JsonNode responseJson = MAPPER.readTree(response.body());
    Assertions.assertNotEquals(
        0, responseJson.get("code").asInt(), "Should have a non-zero error code");

    LOG.info("Correctly rejected membership update with both fields null");
  }

  @Test
  @DisplayName("#48 usersToAdd and usersToRemove both empty arrays - returns 400")
  void testMembershipBothFieldsEmptyArrays() throws Exception {
    String groupName = "e2e-test-group-" + System.currentTimeMillis();
    createGroup(groupName);

    ObjectNode body = MAPPER.createObjectNode();
    body.putArray("usersToAdd");
    body.putArray("usersToRemove");

    HttpResponse<String> response = put("/groups/" + groupName + "/users", body.toString());
    Assertions.assertEquals(
        400,
        response.statusCode(),
        "Both fields as empty arrays should be rejected: " + response.body());

    JsonNode responseJson = MAPPER.readTree(response.body());
    Assertions.assertNotEquals(
        0, responseJson.get("code").asInt(), "Should have a non-zero error code");

    LOG.info("Correctly rejected membership update with both fields as empty arrays");
  }

  @Test
  @DisplayName("#49 Add non-existent user to group - returns 404")
  void testAddNonExistentUserToGroup() throws Exception {
    String groupName = "e2e-test-group-" + System.currentTimeMillis();
    createGroup(groupName);

    ObjectNode body = MAPPER.createObjectNode();
    ArrayNode usersToAdd = body.putArray("usersToAdd");
    usersToAdd.add("no-exist-user-" + System.currentTimeMillis());

    HttpResponse<String> response = put("/groups/" + groupName + "/users", body.toString());
    Assertions.assertEquals(
        404,
        response.statusCode(),
        "Adding non-existent user should return 404: " + response.body());

    JsonNode responseJson = MAPPER.readTree(response.body());
    Assertions.assertNotEquals(
        0, responseJson.get("code").asInt(), "Should have a non-zero error code");

    LOG.info("Correctly received 404 when adding non-existent user to group");
  }

  @Test
  @DisplayName("#50 Modify membership of non-existent group - returns 404")
  void testModifyMembershipOfNonExistentGroup() throws Exception {
    String groupName = "non-existent-group-" + System.currentTimeMillis();
    String username = "e2e-test-user-" + System.currentTimeMillis();
    createUser(username);

    ObjectNode body = MAPPER.createObjectNode();
    ArrayNode usersToAdd = body.putArray("usersToAdd");
    usersToAdd.add(username);

    HttpResponse<String> response = put("/groups/" + groupName + "/users", body.toString());
    Assertions.assertEquals(
        404,
        response.statusCode(),
        "Modifying non-existent group should return 404: " + response.body());

    JsonNode responseJson = MAPPER.readTree(response.body());
    Assertions.assertNotEquals(
        0, responseJson.get("code").asInt(), "Should have a non-zero error code");

    LOG.info("Correctly received 404 for membership update on non-existent group");
  }

  @Test
  @DisplayName("#51 Re-add user already in group - returns 200 (idempotent, no duplicates)")
  void testReAddUserAlreadyInGroup() throws Exception {
    String groupName = "e2e-test-group-" + System.currentTimeMillis();
    String username = "e2e-test-user-" + System.currentTimeMillis();
    createGroup(groupName);
    createUser(username);

    // Add user to group first time
    addUsersToGroup(groupName, username);

    // Add same user again
    ObjectNode body = MAPPER.createObjectNode();
    ArrayNode usersToAdd = body.putArray("usersToAdd");
    usersToAdd.add(username);

    HttpResponse<String> response = put("/groups/" + groupName + "/users", body.toString());

    // Server treats re-adding an existing member as a no-op (idempotent)
    Assertions.assertEquals(
        200,
        response.statusCode(),
        "Re-adding user should be idempotent (200): " + response.body());

    JsonNode responseJson = MAPPER.readTree(response.body());
    Assertions.assertEquals(0, responseJson.get("code").asInt(), response.body());

    // Verify no duplicate: user should appear exactly once in the group
    JsonNode groupNode = responseJson.get("group");
    Assertions.assertNotNull(groupNode, "Missing 'group' field: " + response.body());
    JsonNode usersNode = groupNode.get("users");
    Assertions.assertNotNull(usersNode, "Missing 'users' field: " + response.body());
    Assertions.assertEquals(
        1, usersNode.size(), "User should appear exactly once (no duplicates): " + response.body());
    Assertions.assertEquals(username, usersNode.get(0).asText());

    LOG.info("Re-adding user was idempotent with no duplicates for group: {}", groupName);
  }

  @Test
  @DisplayName("#52 Remove user not in group - returns 200 (idempotent)")
  void testRemoveUserNotInGroup() throws Exception {
    String groupName = "e2e-test-group-" + System.currentTimeMillis();
    String username = "e2e-test-user-" + System.currentTimeMillis();
    createGroup(groupName);
    createUser(username);

    // User is NOT in the group, attempt to remove
    ObjectNode body = MAPPER.createObjectNode();
    ArrayNode usersToRemove = body.putArray("usersToRemove");
    usersToRemove.add(username);

    HttpResponse<String> response = put("/groups/" + groupName + "/users", body.toString());

    // Server treats removing a non-member as a no-op (idempotent)
    Assertions.assertEquals(
        200,
        response.statusCode(),
        "Removing user not in group should be idempotent (200): " + response.body());

    JsonNode responseJson = MAPPER.readTree(response.body());
    Assertions.assertEquals(0, responseJson.get("code").asInt(), response.body());

    // Verify group remains empty
    JsonNode groupNode = responseJson.get("group");
    Assertions.assertNotNull(groupNode, "Missing 'group' field: " + response.body());
    JsonNode usersNode = groupNode.get("users");
    Assertions.assertNotNull(usersNode, "Missing 'users' field: " + response.body());
    Assertions.assertEquals(0, usersNode.size(), "Group should still be empty");

    LOG.info("Removing user not in group was idempotent (200) for group: {}", groupName);
  }

  // ==================== Helper methods ====================

  private static HttpResponse<String> createUserRequest(String username, String password)
      throws Exception {
    String requestBody =
        MAPPER.createObjectNode().put("user", username).put("password", password).toString();
    return post("/users", requestBody);
  }

  private void createGroup(String groupName) throws Exception {
    String requestBody = MAPPER.createObjectNode().put("group", groupName).toString();
    HttpResponse<String> response = post("/groups", requestBody);
    Assertions.assertEquals(
        200, response.statusCode(), "Failed to create group: " + response.body());
    createdGroups.add(groupName);
  }

  private void createUser(String username) throws Exception {
    String password = "Passw0rd-E2E-Test1";
    String requestBody =
        MAPPER.createObjectNode().put("user", username).put("password", password).toString();
    HttpResponse<String> response = post("/users", requestBody);
    Assertions.assertEquals(
        200, response.statusCode(), "Failed to create user: " + response.body());
    createdUsers.add(username);
  }

  private void addUsersToGroup(String groupName, String... users) throws Exception {
    ObjectNode body = MAPPER.createObjectNode();
    ArrayNode usersToAdd = body.putArray("usersToAdd");
    for (String user : users) {
      usersToAdd.add(user);
    }
    HttpResponse<String> response = put("/groups/" + groupName + "/users", body.toString());
    Assertions.assertEquals(
        200, response.statusCode(), "Failed to add users to group: " + response.body());
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

  private static void deleteUser(String username) throws Exception {
    HttpRequest request =
        HttpRequest.newBuilder()
            .uri(URI.create(idpApiBase + "/users/" + username))
            .header("Accept", ACCEPT)
            .header("Authorization", basicAuth(ADMIN, adminPassword))
            .DELETE()
            .build();
    HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
    LOG.info("Cleanup user '{}': status={}", username, response.statusCode());
  }

  private static void deleteGroup(String groupName) throws Exception {
    HttpRequest request =
        HttpRequest.newBuilder()
            .uri(URI.create(idpApiBase + "/groups/" + groupName + "?force=true"))
            .header("Accept", ACCEPT)
            .header("Authorization", basicAuth(ADMIN, adminPassword))
            .DELETE()
            .build();
    HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
    LOG.info("Cleanup group '{}': status={}", groupName, response.statusCode());
  }

  private static String basicAuth(String username, String password) {
    String credentials = username + ":" + password;
    return "Basic "
        + Base64.getEncoder().encodeToString(credentials.getBytes(StandardCharsets.UTF_8));
  }
}
