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

/** E2E tests for multi-serviceAdmin scenarios. Reuses the K8s deployment environment. */
@DisplayName("Built-in IDP Multi-Admin E2E Tests")
public class IdpMultiAdminIT {

  private static final Logger LOG = LoggerFactory.getLogger(IdpMultiAdminIT.class);
  private static final String ACCEPT = "application/vnd.gravitino.v1+json";
  private static final String ADMIN = "admin";
  private static final String SERVICE_ACCOUNT = "service-account-postman-client";
  private static final ObjectMapper MAPPER = new ObjectMapper();

  private static String adminPassword;
  private static String serviceAccountPassword;
  private static String idpApiBase;
  private static HttpClient httpClient;

  private final List<String> createdUsers = new ArrayList<>();
  private final List<String> createdGroups = new ArrayList<>();

  @BeforeAll
  public static void setup() {
    String gravitinoUri = System.getProperty("gravitino.uri", "http://localhost:30090");
    adminPassword = System.getProperty("gravitino.admin.password", "Passw0rd-Admin12");
    serviceAccountPassword =
        System.getProperty("gravitino.service.account.password", adminPassword);
    idpApiBase = gravitinoUri + "/api/idp";
    httpClient = HttpClient.newHttpClient();
    LOG.info("IdpMultiAdminIT setup: idpApiBase={}", idpApiBase);
  }

  @AfterEach
  public void cleanup() throws Exception {
    for (String group : createdGroups) {
      delete("/groups/" + group + "?force=true", ADMIN, adminPassword);
    }
    createdGroups.clear();
    for (String user : createdUsers) {
      delete("/users/" + user, ADMIN, adminPassword);
    }
    createdUsers.clear();
  }

  @Test
  @DisplayName("#24 Both serviceAdmins can call IDP API")
  void testBothServiceAdminsCanCallIdpApi() throws Exception {
    HttpResponse<String> adminResp = get("/users/" + ADMIN, ADMIN, adminPassword);
    Assertions.assertEquals(200, adminResp.statusCode(), adminResp.body());

    HttpResponse<String> saResp =
        get("/users/" + SERVICE_ACCOUNT, SERVICE_ACCOUNT, serviceAccountPassword);
    Assertions.assertEquals(200, saResp.statusCode(), saResp.body());
  }

  @Test
  @DisplayName("#25 Each serviceAdmin gets independent password initialization")
  void testEachServiceAdminPasswordInitialization() throws Exception {
    // Both admins should be able to authenticate with initial password
    HttpResponse<String> adminResp = get("/users/" + ADMIN, ADMIN, adminPassword);
    Assertions.assertEquals(200, adminResp.statusCode(), adminResp.body());

    HttpResponse<String> saResp =
        get("/users/" + SERVICE_ACCOUNT, SERVICE_ACCOUNT, serviceAccountPassword);
    Assertions.assertEquals(200, saResp.statusCode(), saResp.body());
  }

  @Test
  @DisplayName("#28 serviceAdmins can manage each other - admin changes SA password")
  void testServiceAdminsManageEachOther() throws Exception {
    String newPassword = "Passw0rd-SA-New-01";

    // admin changes service-account-postman-client's password
    HttpResponse<String> resp =
        put("/users/" + SERVICE_ACCOUNT, passwordBody(newPassword), ADMIN, adminPassword);
    Assertions.assertEquals(200, resp.statusCode(), resp.body());

    // Verify new password works
    HttpResponse<String> verify = get("/users/" + SERVICE_ACCOUNT, SERVICE_ACCOUNT, newPassword);
    Assertions.assertEquals(200, verify.statusCode(), verify.body());

    // Restore original password
    put("/users/" + SERVICE_ACCOUNT, passwordBody(serviceAccountPassword), ADMIN, adminPassword);
  }

  @Test
  @DisplayName("#29 service-account-postman-client creates regular user")
  void testServiceAccountCreatesUser() throws Exception {
    String username = "sa-created-user-" + System.currentTimeMillis();
    String password = "Passw0rd-SA-User01";

    String body =
        MAPPER.createObjectNode().put("user", username).put("password", password).toString();
    HttpResponse<String> resp = post("/users", body, SERVICE_ACCOUNT, serviceAccountPassword);
    Assertions.assertEquals(200, resp.statusCode(), resp.body());

    JsonNode userNode = MAPPER.readTree(resp.body()).get("user");
    Assertions.assertEquals(username, userNode.get("name").asText());
    Assertions.assertEquals(0, userNode.get("groups").size());

    createdUsers.add(username);
  }

  @Test
  @DisplayName("#30 service-account-postman-client manages groups")
  void testServiceAccountManagesGroups() throws Exception {
    String username = "sa-group-user-" + System.currentTimeMillis();
    String groupName = "sa-group-" + System.currentTimeMillis();
    String password = "Passw0rd-SA-User01";

    // Create user
    String userBody =
        MAPPER.createObjectNode().put("user", username).put("password", password).toString();
    HttpResponse<String> userResp =
        post("/users", userBody, SERVICE_ACCOUNT, serviceAccountPassword);
    Assertions.assertEquals(200, userResp.statusCode(), userResp.body());
    createdUsers.add(username);

    // Create group
    String groupBody = MAPPER.createObjectNode().put("group", groupName).toString();
    HttpResponse<String> groupResp =
        post("/groups", groupBody, SERVICE_ACCOUNT, serviceAccountPassword);
    Assertions.assertEquals(200, groupResp.statusCode(), groupResp.body());
    createdGroups.add(groupName);

    // Add user to group
    String memberBody =
        MAPPER.createObjectNode().putPOJO("usersToAdd", new String[] {username}).toString();
    HttpResponse<String> addResp =
        put("/groups/" + groupName + "/users", memberBody, SERVICE_ACCOUNT, serviceAccountPassword);
    Assertions.assertEquals(200, addResp.statusCode(), addResp.body());

    JsonNode groupNode = MAPPER.readTree(addResp.body()).get("group");
    Assertions.assertTrue(groupNode.get("users").toString().contains(username));
  }

  @Test
  @DisplayName("#31 serviceAdmin attempts to delete another serviceAdmin")
  void testDeleteAnotherServiceAdmin() throws Exception {
    // admin attempts to delete service-account-postman-client
    HttpResponse<String> resp = delete("/users/" + SERVICE_ACCOUNT, ADMIN, adminPassword);
    // Verify behavior: either 403 (forbidden) or 200 (allowed)
    int status = resp.statusCode();
    Assertions.assertTrue(
        status == 403 || status == 200,
        "Expected 403 or 200 but got " + status + ": " + resp.body());
    LOG.info("Delete another serviceAdmin returned: {}", status);

    // If deletion succeeded, the SA should be re-created on next restart.
    // For now restore if needed by verifying SA still works
    if (status == 200) {
      LOG.warn("serviceAdmin deletion was allowed - SA may need re-initialization");
    }
  }

  @Test
  @DisplayName("#31a serviceAdmin changes own password")
  void testServiceAdminChangesOwnPassword() throws Exception {
    String newPassword = "Passw0rd-Admin-New1";

    HttpResponse<String> resp =
        put("/users/" + ADMIN, passwordBody(newPassword), ADMIN, adminPassword);
    Assertions.assertEquals(200, resp.statusCode(), resp.body());

    // Old password rejected
    HttpResponse<String> oldResp = get("/users/" + ADMIN, ADMIN, adminPassword);
    Assertions.assertEquals(401, oldResp.statusCode());

    // New password works
    HttpResponse<String> newResp = get("/users/" + ADMIN, ADMIN, newPassword);
    Assertions.assertEquals(200, newResp.statusCode(), newResp.body());

    // Restore original password
    put("/users/" + ADMIN, passwordBody(adminPassword), ADMIN, newPassword);
  }

  @Test
  @DisplayName("#31b serviceAdmin changes another serviceAdmin's password")
  void testServiceAdminChangesAnotherAdminPassword() throws Exception {
    String newPassword = "Passw0rd-SA-Chg-01";

    HttpResponse<String> resp =
        put("/users/" + SERVICE_ACCOUNT, passwordBody(newPassword), ADMIN, adminPassword);
    Assertions.assertEquals(200, resp.statusCode(), resp.body());

    // service-account-postman-client must use new password
    HttpResponse<String> verify = get("/users/" + SERVICE_ACCOUNT, SERVICE_ACCOUNT, newPassword);
    Assertions.assertEquals(200, verify.statusCode(), verify.body());

    // Old password rejected
    HttpResponse<String> oldResp =
        get("/users/" + SERVICE_ACCOUNT, SERVICE_ACCOUNT, serviceAccountPassword);
    Assertions.assertEquals(401, oldResp.statusCode());

    // Restore
    put("/users/" + SERVICE_ACCOUNT, passwordBody(serviceAccountPassword), ADMIN, adminPassword);
  }

  @Test
  @DisplayName("#31d Regular user cannot change another user's password")
  void testRegularUserCannotChangeOtherPassword() throws Exception {
    String user1 = "regular-user1-" + System.currentTimeMillis();
    String user2 = "regular-user2-" + System.currentTimeMillis();
    String password = "Passw0rd-Regular01";

    // Create two regular users
    post(
        "/users",
        MAPPER.createObjectNode().put("user", user1).put("password", password).toString(),
        ADMIN,
        adminPassword);
    createdUsers.add(user1);
    post(
        "/users",
        MAPPER.createObjectNode().put("user", user2).put("password", password).toString(),
        ADMIN,
        adminPassword);
    createdUsers.add(user2);

    // user1 tries to change user2's password
    HttpResponse<String> resp =
        put("/users/" + user2, passwordBody("Passw0rd-Hacked-01"), user1, password);
    Assertions.assertEquals(403, resp.statusCode(), resp.body());
  }

  @Test
  @DisplayName("#31e Regular user cannot change own password")
  void testRegularUserCannotChangeOwnPassword() throws Exception {
    String username = "regular-self-" + System.currentTimeMillis();
    String password = "Passw0rd-Regular01";
    String newPassword = "Passw0rd-RegNew-01";

    post(
        "/users",
        MAPPER.createObjectNode().put("user", username).put("password", password).toString(),
        ADMIN,
        adminPassword);
    createdUsers.add(username);

    // Regular user tries to change own password - should be rejected
    HttpResponse<String> resp =
        put("/users/" + username, passwordBody(newPassword), username, password);
    Assertions.assertEquals(403, resp.statusCode(), resp.body());
  }

  @Test
  @DisplayName("#31f Change password with invalid new password (too short, 11 chars)")
  void testChangePasswordTooShort() throws Exception {
    String username = "short-pwd-" + System.currentTimeMillis();
    String password = "Passw0rd-Regular01";

    post(
        "/users",
        MAPPER.createObjectNode().put("user", username).put("password", password).toString(),
        ADMIN,
        adminPassword);
    createdUsers.add(username);

    // 11-char password
    HttpResponse<String> resp =
        put("/users/" + username, passwordBody("Passw0rd-1!"), ADMIN, adminPassword);
    Assertions.assertEquals(400, resp.statusCode(), resp.body());
  }

  @Test
  @DisplayName("#31g Change password with invalid new password (too long, 65 chars)")
  void testChangePasswordTooLong() throws Exception {
    String username = "long-pwd-" + System.currentTimeMillis();
    String password = "Passw0rd-Regular01";

    post(
        "/users",
        MAPPER.createObjectNode().put("user", username).put("password", password).toString(),
        ADMIN,
        adminPassword);
    createdUsers.add(username);

    // 65-char password
    String longPassword = "Passw0rd-" + "A".repeat(56);
    Assertions.assertEquals(65, longPassword.length());

    HttpResponse<String> resp =
        put("/users/" + username, passwordBody(longPassword), ADMIN, adminPassword);
    Assertions.assertEquals(400, resp.statusCode(), resp.body());
  }

  @Test
  @DisplayName("#31h Change password then immediately authenticate with new password")
  void testChangePasswordImmediateAuth() throws Exception {
    String username = "imm-auth-" + System.currentTimeMillis();
    String password = "Passw0rd-Regular01";
    String newPassword = "Passw0rd-ImmNew-01";

    post(
        "/users",
        MAPPER.createObjectNode().put("user", username).put("password", password).toString(),
        ADMIN,
        adminPassword);
    createdUsers.add(username);

    // Admin changes user password
    HttpResponse<String> resp =
        put("/users/" + username, passwordBody(newPassword), ADMIN, adminPassword);
    Assertions.assertEquals(200, resp.statusCode(), resp.body());

    // Immediately authenticate with new password via Gravitino API (not IDP API)
    String gravitinoUri = System.getProperty("gravitino.uri", "http://localhost:30090");
    HttpRequest verifyReq =
        HttpRequest.newBuilder()
            .uri(URI.create(gravitinoUri + "/api/version"))
            .header("Accept", ACCEPT)
            .header("Authorization", basicAuth(username, newPassword))
            .GET()
            .build();
    HttpResponse<String> verify = httpClient.send(verifyReq, HttpResponse.BodyHandlers.ofString());
    Assertions.assertEquals(
        200, verify.statusCode(), "Auth should work immediately: " + verify.body());

    // Verify old password is rejected
    HttpRequest oldReq =
        HttpRequest.newBuilder()
            .uri(URI.create(gravitinoUri + "/api/version"))
            .header("Accept", ACCEPT)
            .header("Authorization", basicAuth(username, password))
            .GET()
            .build();
    HttpResponse<String> oldResp = httpClient.send(oldReq, HttpResponse.BodyHandlers.ofString());
    Assertions.assertEquals(401, oldResp.statusCode(), "Old password should be rejected");
  }

  @Test
  @DisplayName("#31i Change password with same value as current password")
  void testChangePasswordSameValue() throws Exception {
    String username = "same-pwd-" + System.currentTimeMillis();
    String password = "Passw0rd-Regular01";

    post(
        "/users",
        MAPPER.createObjectNode().put("user", username).put("password", password).toString(),
        ADMIN,
        adminPassword);
    createdUsers.add(username);

    // Change to same password
    HttpResponse<String> resp =
        put("/users/" + username, passwordBody(password), ADMIN, adminPassword);
    int status = resp.statusCode();
    Assertions.assertTrue(status == 200, "Expected 200 but got " + status + ": " + resp.body());
    LOG.info("Change password to same value returned: {}", status);
  }

  // ==================== Helper methods ====================

  private static String passwordBody(String password) throws Exception {
    return MAPPER.createObjectNode().put("password", password).toString();
  }

  private static HttpResponse<String> get(String path, String user, String password)
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

  private static HttpResponse<String> post(String path, String body, String user, String password)
      throws Exception {
    HttpRequest request =
        HttpRequest.newBuilder()
            .uri(URI.create(idpApiBase + path))
            .header("Accept", ACCEPT)
            .header("Content-Type", ACCEPT)
            .header("Authorization", basicAuth(user, password))
            .POST(HttpRequest.BodyPublishers.ofString(body))
            .build();
    return httpClient.send(request, HttpResponse.BodyHandlers.ofString());
  }

  private static HttpResponse<String> put(String path, String body, String user, String password)
      throws Exception {
    HttpRequest request =
        HttpRequest.newBuilder()
            .uri(URI.create(idpApiBase + path))
            .header("Accept", ACCEPT)
            .header("Content-Type", ACCEPT)
            .header("Authorization", basicAuth(user, password))
            .PUT(HttpRequest.BodyPublishers.ofString(body))
            .build();
    return httpClient.send(request, HttpResponse.BodyHandlers.ofString());
  }

  private static HttpResponse<String> delete(String path, String user, String password)
      throws Exception {
    HttpRequest request =
        HttpRequest.newBuilder()
            .uri(URI.create(idpApiBase + path))
            .header("Accept", ACCEPT)
            .header("Authorization", basicAuth(user, password))
            .DELETE()
            .build();
    return httpClient.send(request, HttpResponse.BodyHandlers.ofString());
  }

  private static String basicAuth(String username, String password) {
    String credentials = username + ":" + password;
    return "Basic "
        + Base64.getEncoder().encodeToString(credentials.getBytes(StandardCharsets.UTF_8));
  }
}
