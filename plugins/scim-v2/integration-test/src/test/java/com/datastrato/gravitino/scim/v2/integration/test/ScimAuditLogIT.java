/*
 * Copyright 2026 Datastrato Pvt Ltd.
 */

package com.datastrato.gravitino.scim.v2.integration.test;

import static org.awaitility.Awaitility.await;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Predicate;
import org.apache.gravitino.Configs;
import org.apache.gravitino.auth.AuthConstants;
import org.apache.gravitino.integration.test.util.CloseContainerExtension;
import org.apache.gravitino.integration.test.util.ITUtils;
import org.apache.gravitino.integration.test.util.PrintFuncNameExtension;
import org.apache.gravitino.json.JsonUtils;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.DisabledIfSystemProperty;
import org.junit.jupiter.api.extension.ExtendWith;

/**
 * End-to-end checks that SCIM provisioning writes formatted audit lines.
 *
 * <p>Covers Mark's audit feedback: membership diffs, DELETE naming, FAILURE reason, remoteAddress,
 * PATCH {@code changes}, LIST {@code count=} inside customInfo, and no duplicate {@code status}.
 */
@ExtendWith({PrintFuncNameExtension.class, CloseContainerExtension.class})
@DisabledIfSystemProperty(named = ITUtils.TEST_MODE, matches = ITUtils.DEPLOY_TEST_MODE)
class ScimAuditLogIT {

  private static final String METALAKE = "scimAuditMetalake";
  private static final String TOKEN_NAME = "audit-it";
  private static final String TOKEN_CREATOR = "scimItOwner";
  private static final String SCIM_ACCEPT = "application/scim+json, application/json";
  private static final String SCIM_USER_SCHEMA = "urn:ietf:params:scim:schemas:core:2.0:User";
  private static final String SCIM_GROUP_SCHEMA = "urn:ietf:params:scim:schemas:core:2.0:Group";
  private static final String SCIM_PATCH_SCHEMA = "urn:ietf:params:scim:api:messages:2.0:PatchOp";
  private static final HttpClient HTTP = HttpClient.newHttpClient();

  private static ScimServiceITEnvironment environment;
  private static String bearerToken;

  @BeforeAll
  static void startEnvironment() throws Exception {
    CapturingAuditLogWriter.clear();
    environment =
        ScimServiceITEnvironment.start(
            Map.of(
                Configs.AUDIT_LOG_ENABLED_CONF.getKey(),
                String.valueOf(true),
                Configs.AUDIT_LOG_WRITER_CLASS_NAME.getKey(),
                CapturingAuditLogWriter.class.getName()));
    environment.adminClient().createMetalake(METALAKE, "", new HashMap<>());
    bearerToken = environment.mintScimBearerToken(TOKEN_NAME, TOKEN_CREATOR);
  }

  @AfterAll
  static void stopEnvironment() throws Exception {
    if (environment != null) {
      try {
        environment.adminClient().dropMetalake(METALAKE, true);
      } finally {
        environment.close();
        environment = null;
      }
    }
  }

  @BeforeEach
  void clearCapturedLogs() {
    CapturingAuditLogWriter.clear();
  }

  @Test
  void testScimAuditLogCoversMarkFeedback() throws Exception {
    String userName = "scim-audit-user";
    String userExternalId = "scim-audit-user-ext";
    String memberName = "scim-audit-member";
    String memberExternalId = "scim-audit-member-ext";
    String groupName = "scim-audit-group";
    String groupExternalId = "scim-audit-group-ext";

    HttpResponse<String> createdUser =
        post(scimPath("/Users"), userBody(userExternalId, userName, true), bearerToken);
    assertStatus(201, createdUser);
    String userId = JsonUtils.objectMapper().readTree(createdUser.body()).get("id").asText();

    HttpResponse<String> createdMember =
        post(scimPath("/Users"), userBody(memberExternalId, memberName, true), bearerToken);
    assertStatus(201, createdMember);
    String memberId = JsonUtils.objectMapper().readTree(createdMember.body()).get("id").asText();

    HttpResponse<String> listed = get(scimPath("/Users") + "?startIndex=1&count=10", bearerToken);
    assertStatus(200, listed);

    HttpResponse<String> disabled =
        patch(scimPath("/Users/" + userId), patchBody("replace", "active", false), bearerToken);
    assertStatus(200, disabled);

    HttpResponse<String> conflict =
        post(scimPath("/Users"), userBody(userExternalId, userName, true), bearerToken);
    assertStatus(409, conflict);

    HttpResponse<String> createdGroup =
        post(
            scimPath("/Groups"),
            groupBody(groupExternalId, groupName, List.of(Map.of("value", userId))),
            bearerToken);
    assertStatus(201, createdGroup);
    String groupId = JsonUtils.objectMapper().readTree(createdGroup.body()).get("id").asText();

    HttpResponse<String> withExtraMember =
        patch(
            scimPath("/Groups/" + groupId),
            patchBody("add", "members", List.of(Map.of("value", memberId))),
            bearerToken);
    assertStatus(200, withExtraMember);

    HttpResponse<String> withoutUser =
        patch(
            scimPath("/Groups/" + groupId),
            patchBody("remove", "members", List.of(Map.of("value", userId))),
            bearerToken);
    assertStatus(200, withoutUser);

    assertStatus(204, delete(scimPath("/Groups/" + groupId), bearerToken));
    assertStatus(204, delete(scimPath("/Users/" + userId), bearerToken));
    assertStatus(204, delete(scimPath("/Users/" + memberId), bearerToken));

    String listLine =
        awaitLine(
            line ->
                line.contains("\tLIST_USERS_PAGED\t")
                    && line.contains("\tSUCCESS\t")
                    && line.contains("source=scim")
                    && line.contains("count="));
    Assertions.assertFalse(
        listLine.contains("status="),
        () -> "LIST customInfo must not duplicate status: " + listLine);
    Assertions.assertTrue(listLine.matches("(?s).*\\{[^}]*count=\\d+.*"), listLine);

    String patchUserLine =
        awaitLine(
            line ->
                line.contains("\tALTER_USER\t")
                    && line.contains(userName)
                    && line.contains("\tSUCCESS\t")
                    && line.contains("changes=active=false"));
    assertRemoteAddressPresent(patchUserLine);
    Assertions.assertFalse(patchUserLine.contains("status="), patchUserLine);

    String failureLine =
        awaitLine(
            line ->
                line.contains("\tADD_USER\t")
                    && line.contains("\tFAILURE\t")
                    && line.contains("reason="));
    Assertions.assertTrue(failureLine.contains("source=scim"), failureLine);
    Assertions.assertFalse(failureLine.contains("status="), failureLine);

    String membersAddedLine =
        awaitLine(
            line ->
                line.contains("\tALTER_GROUP\t")
                    && line.contains(groupName)
                    && line.contains("membersAdded=" + memberId));
    Assertions.assertTrue(membersAddedLine.contains("changes=members"), membersAddedLine);

    String membersRemovedLine =
        awaitLine(
            line ->
                line.contains("\tALTER_GROUP\t")
                    && line.contains(groupName)
                    && line.contains("membersRemoved=" + userId));
    Assertions.assertTrue(membersRemovedLine.contains("changes=members"), membersRemovedLine);

    String deleteUserLine =
        awaitLine(
            line ->
                line.contains("\tREMOVE_USER\t")
                    && line.contains("\tSUCCESS\t")
                    && line.contains("." + userName + "\t"));
    Assertions.assertFalse(
        deleteUserLine.contains(".unknown\t"),
        () -> "DELETE must use preloaded userName, got: " + deleteUserLine);
    assertRemoteAddressPresent(deleteUserLine);

    String deleteGroupLine =
        awaitLine(
            line ->
                line.contains("\tREMOVE_GROUP\t")
                    && line.contains("\tSUCCESS\t")
                    && line.contains("." + groupName + "\t"));
    Assertions.assertFalse(
        deleteGroupLine.contains(".unknown\t"),
        () -> "DELETE must use preloaded groupName, got: " + deleteGroupLine);
  }

  private static String awaitLine(Predicate<String> matcher) {
    await()
        .atMost(Duration.ofSeconds(20))
        .pollInterval(Duration.ofMillis(100))
        .until(() -> CapturingAuditLogWriter.lines().stream().anyMatch(matcher));
    return CapturingAuditLogWriter.lines().stream()
        .filter(matcher)
        .findFirst()
        .orElseThrow(
            () ->
                new AssertionError(
                    "No matching audit line in: " + CapturingAuditLogWriter.lines()));
  }

  private static void assertRemoteAddressPresent(String line) {
    // SimpleAuditLogV2: ...\toperationStatus\teventSource\tremoteAddress\t{customInfo}
    String[] parts = line.split("\t", -1);
    Assertions.assertTrue(parts.length >= 8, () -> "Unexpected audit format: " + line);
    String remoteAddress = parts[6];
    Assertions.assertFalse(
        "unknown".equals(remoteAddress),
        () -> "remoteAddress must not be literal unknown: " + line);
    Assertions.assertFalse(
        remoteAddress.isBlank(), () -> "remoteAddress should be set from HTTP client: " + line);
  }

  private static String scimPath(String suffix) {
    return "/scim/v2" + suffix;
  }

  private static Map<String, Object> userBody(String externalId, String userName, boolean active) {
    Map<String, Object> body = new HashMap<>();
    body.put("schemas", new String[] {SCIM_USER_SCHEMA});
    body.put("externalId", externalId);
    body.put("userName", userName);
    body.put("active", active);
    return body;
  }

  private static Map<String, Object> groupBody(
      String externalId, String displayName, List<Map<String, Object>> members) {
    Map<String, Object> body = new HashMap<>();
    body.put("schemas", new String[] {SCIM_GROUP_SCHEMA});
    body.put("externalId", externalId);
    body.put("displayName", displayName);
    if (!members.isEmpty()) {
      body.put("members", members);
    }
    return body;
  }

  private static Map<String, Object> patchBody(String op, String path, Object value) {
    Map<String, Object> operation = new HashMap<>();
    operation.put("op", op);
    operation.put("path", path);
    operation.put("value", value);

    List<Map<String, Object>> operations = new ArrayList<>();
    operations.add(operation);

    Map<String, Object> body = new HashMap<>();
    body.put("schemas", new String[] {SCIM_PATCH_SCHEMA});
    body.put("Operations", operations);
    return body;
  }

  private static HttpResponse<String> get(String path, String bearerToken) throws Exception {
    return HTTP.send(
        HttpRequest.newBuilder()
            .uri(URI.create(environment.scimBaseUri() + path))
            .GET()
            .header("Accept", SCIM_ACCEPT)
            .header(AuthConstants.HTTP_HEADER_AUTHORIZATION, "Bearer " + bearerToken)
            .build(),
        HttpResponse.BodyHandlers.ofString());
  }

  private static HttpResponse<String> post(String path, Object body, String bearerToken)
      throws Exception {
    return HTTP.send(
        HttpRequest.newBuilder()
            .uri(URI.create(environment.scimBaseUri() + path))
            .POST(
                HttpRequest.BodyPublishers.ofString(
                    JsonUtils.objectMapper().writeValueAsString(body)))
            .header("Accept", SCIM_ACCEPT)
            .header("Content-Type", "application/scim+json")
            .header(AuthConstants.HTTP_HEADER_AUTHORIZATION, "Bearer " + bearerToken)
            .build(),
        HttpResponse.BodyHandlers.ofString());
  }

  private static HttpResponse<String> patch(String path, Object body, String bearerToken)
      throws Exception {
    return HTTP.send(
        HttpRequest.newBuilder()
            .uri(URI.create(environment.scimBaseUri() + path))
            .method(
                "PATCH",
                HttpRequest.BodyPublishers.ofString(
                    JsonUtils.objectMapper().writeValueAsString(body)))
            .header("Accept", SCIM_ACCEPT)
            .header("Content-Type", "application/scim+json")
            .header(AuthConstants.HTTP_HEADER_AUTHORIZATION, "Bearer " + bearerToken)
            .build(),
        HttpResponse.BodyHandlers.ofString());
  }

  private static HttpResponse<String> delete(String path, String bearerToken) throws Exception {
    return HTTP.send(
        HttpRequest.newBuilder()
            .uri(URI.create(environment.scimBaseUri() + path))
            .DELETE()
            .header("Accept", SCIM_ACCEPT)
            .header(AuthConstants.HTTP_HEADER_AUTHORIZATION, "Bearer " + bearerToken)
            .build(),
        HttpResponse.BodyHandlers.ofString());
  }

  private static void assertStatus(int expected, HttpResponse<String> response) {
    Assertions.assertEquals(
        expected, response.statusCode(), () -> "Unexpected body: " + response.body());
  }
}
