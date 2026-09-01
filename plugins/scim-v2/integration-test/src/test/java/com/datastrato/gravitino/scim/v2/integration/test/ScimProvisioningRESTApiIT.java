/*
 * Copyright 2026 Datastrato Pvt Ltd.
 */

package com.datastrato.gravitino.scim.v2.integration.test;

import static org.awaitility.Awaitility.await;

import com.fasterxml.jackson.databind.JsonNode;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.apache.gravitino.auth.AuthConstants;
import org.apache.gravitino.integration.test.util.CloseContainerExtension;
import org.apache.gravitino.integration.test.util.ITUtils;
import org.apache.gravitino.integration.test.util.PrintFuncNameExtension;
import org.apache.gravitino.json.JsonUtils;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.DisabledIfSystemProperty;
import org.junit.jupiter.api.extension.ExtendWith;

/**
 * End-to-end SCIM 2.0 provisioning against the Jersey 3 auxiliary listener.
 *
 * <p>MiniGravitino stays on Jersey 2. The SCIM stack is started by production {@code
 * ScimRESTService}, which loads Jetty 11 / Jersey 3 / SCIMple through child-first {@code
 * scim-server/libs} (no Gradle IT classpath filtering).
 *
 * <p>Run with {@code -PjdbcBackend=h2}, {@code mysql}, or {@code postgresql}.
 */
@ExtendWith({PrintFuncNameExtension.class, CloseContainerExtension.class})
@DisabledIfSystemProperty(named = ITUtils.TEST_MODE, matches = ITUtils.DEPLOY_TEST_MODE)
class ScimProvisioningRESTApiIT {

  private static final String METALAKE = "scimProvisioningMetalake";
  private static final String TOKEN_NAME = "provisioning-it";
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
    environment = ScimServiceITEnvironment.start();
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

  @Test
  void testAuxiliaryStackUsesChildFirstClassLoader() throws Exception {
    environment.assertChildFirstHttpStack();
  }

  @Test
  void testHealthEndpoints() throws Exception {
    assertStatus(200, get("/scim/health/live", null));
    assertStatus(200, get("/scim/health/ready", null));
    assertStatus(200, get("/scim/health", null));
  }

  @Test
  void testServiceProviderConfig() throws Exception {
    HttpResponse<String> response = get(scimPath("/ServiceProviderConfig"), bearerToken);
    assertStatus(200, response);
    JsonNode body = JsonUtils.objectMapper().readTree(response.body());
    Assertions.assertTrue(body.has("patch"));
    Assertions.assertTrue(body.has("filter"));
  }

  @Test
  void testUnauthorizedWithoutBearer() throws Exception {
    assertStatus(401, get(scimPath("/Users"), null));
    assertStatus(401, get(scimPath("/Groups"), null));
  }

  @Test
  void testLastUsedOnAuth() throws Exception {
    String tokenName = "last-used-it";
    String token = environment.mintScimBearerToken(tokenName, TOKEN_CREATOR);
    var before = environment.readScimTokenMeta(tokenName);
    assertStatus(200, get(scimPath("/Users"), token));
    var after = environment.readScimTokenMeta(tokenName);
    Assertions.assertTrue(after.getLastUsedAt() > 0L);
    Assertions.assertEquals(before.getUpdatedAt(), after.getUpdatedAt());
  }

  @Test
  void testErrorHistoryRecordsUsersAndGroupsFailures() throws Exception {
    String tokenName = "error-history-it";
    String token = environment.mintScimBearerToken(tokenName, TOKEN_CREATOR);
    Assertions.assertEquals(0L, environment.countScimErrorHistory());

    Map<String, Object> missingUserName = new HashMap<>();
    missingUserName.put("schemas", new String[] {SCIM_USER_SCHEMA});
    missingUserName.put("externalId", "error-history-missing-user-name");
    assertStatus(400, post(scimPath("/Users"), missingUserName, token));

    String userExternalId = "error-history-dup-user-ext";
    String userName = "error-history-dup-user";
    HttpResponse<String> createdUser =
        post(scimPath("/Users"), userBody(userExternalId, userName, true), token);
    assertStatus(201, createdUser);
    assertStatus(409, post(scimPath("/Users"), userBody(userExternalId, userName, true), token));
    String userId = JsonUtils.objectMapper().readTree(createdUser.body()).get("id").asText();
    assertStatus(404, get(scimPath("/Users/" + userId + "-missing"), token));

    Map<String, Object> missingDisplayName = new HashMap<>();
    missingDisplayName.put("schemas", new String[] {SCIM_GROUP_SCHEMA});
    missingDisplayName.put("externalId", "error-history-missing-display-name");
    assertStatus(400, post(scimPath("/Groups"), missingDisplayName, token));

    String groupExternalId = "error-history-dup-group-ext";
    String groupDisplayName = "error-history-dup-group";
    HttpResponse<String> createdGroup =
        post(scimPath("/Groups"), groupBody(groupExternalId, groupDisplayName, List.of()), token);
    assertStatus(201, createdGroup);
    assertStatus(
        409,
        post(scimPath("/Groups"), groupBody(groupExternalId, groupDisplayName, List.of()), token));
    String groupId = JsonUtils.objectMapper().readTree(createdGroup.body()).get("id").asText();
    assertStatus(404, get(scimPath("/Groups/" + groupId + "-missing"), token));

    await()
        .atMost(Duration.ofSeconds(10))
        .untilAsserted(() -> Assertions.assertEquals(4L, environment.countScimErrorHistory()));

    assertStatus(204, delete(scimPath("/Users/" + userId), token));
    assertStatus(204, delete(scimPath("/Groups/" + groupId), token));
  }

  @Test
  void testMetadataEndpoints() throws Exception {
    HttpResponse<String> resourceTypes = get(scimPath("/ResourceTypes"), bearerToken);
    assertStatus(200, resourceTypes);
    JsonNode resourceTypeList =
        JsonUtils.objectMapper().readTree(resourceTypes.body()).get("Resources");
    Assertions.assertTrue(resourceTypeList.isArray());
    Assertions.assertTrue(resourceTypeList.size() >= 2);

    HttpResponse<String> schemas = get(scimPath("/Schemas"), bearerToken);
    assertStatus(200, schemas);
    JsonNode schemaList = JsonUtils.objectMapper().readTree(schemas.body()).get("Resources");
    Assertions.assertTrue(schemaList.isArray());
    Assertions.assertTrue(schemaList.size() >= 2);
  }

  @Test
  void testUserLifecycle() throws Exception {
    String externalId = "scim-it-user-ext";
    String userName = "scim-it-user";

    HttpResponse<String> created =
        post(scimPath("/Users"), userBody(externalId, userName, true), bearerToken);
    assertStatus(201, created);
    JsonNode createdUser = JsonUtils.objectMapper().readTree(created.body());
    String userId = createdUser.get("id").asText();
    Assertions.assertEquals(userName, createdUser.get("userName").asText());
    Assertions.assertEquals(externalId, createdUser.get("externalId").asText());
    Assertions.assertNotEquals(externalId, userId);

    HttpResponse<String> fetched = get(scimPath("/Users/" + userId), bearerToken);
    assertStatus(200, fetched);
    JsonNode fetchedUser = JsonUtils.objectMapper().readTree(fetched.body());
    Assertions.assertEquals(userId, fetchedUser.get("id").asText());

    HttpResponse<String> deleted = delete(scimPath("/Users/" + userId), bearerToken);
    assertStatus(204, deleted);

    HttpResponse<String> missing = get(scimPath("/Users/" + userId), bearerToken);
    assertStatus(404, missing);
  }

  @Test
  void testUserProvisioningImport() throws Exception {
    String externalId = "scim-it-user-import-ext";
    String userName = "scim-it-user-import";

    HttpResponse<String> created =
        post(scimPath("/Users"), userBody(externalId, userName, true), bearerToken);
    assertStatus(201, created);
    String userId = JsonUtils.objectMapper().readTree(created.body()).get("id").asText();

    HttpResponse<String> reimported =
        post(scimPath("/Users"), userBody(externalId, userName, true), bearerToken);
    assertStatus(409, reimported);

    HttpResponse<String> filtered =
        get(
            scimPath("/Users") + "?filter=" + encodeQuery("externalId eq \"" + externalId + "\""),
            bearerToken);
    assertStatus(200, filtered);
    JsonNode list = JsonUtils.objectMapper().readTree(filtered.body());
    Assertions.assertEquals(1, list.get("totalResults").asInt());
    Assertions.assertEquals(userId, list.get("Resources").get(0).get("id").asText());
    Assertions.assertEquals(externalId, list.get("Resources").get(0).get("externalId").asText());

    HttpResponse<String> disabled =
        patch(scimPath("/Users/" + userId), patchBody("replace", "active", false), bearerToken);
    assertStatus(200, disabled);
    Assertions.assertFalse(
        JsonUtils.objectMapper().readTree(disabled.body()).get("active").asBoolean());

    HttpResponse<String> fetchedDisabled = get(scimPath("/Users/" + userId), bearerToken);
    assertStatus(200, fetchedDisabled);
    Assertions.assertFalse(
        JsonUtils.objectMapper().readTree(fetchedDisabled.body()).get("active").asBoolean(),
        () -> "GET by id after disable: " + fetchedDisabled.body());

    HttpResponse<String> filteredDisabled =
        get(
            scimPath("/Users") + "?filter=" + encodeQuery("userName eq \"" + userName + "\""),
            bearerToken);
    assertStatus(200, filteredDisabled);
    JsonNode filteredDisabledList = JsonUtils.objectMapper().readTree(filteredDisabled.body());
    Assertions.assertEquals(1, filteredDisabledList.get("totalResults").asInt());
    Assertions.assertFalse(
        filteredDisabledList.get("Resources").get(0).get("active").asBoolean(),
        () -> "GET filter after disable: " + filteredDisabled.body());

    HttpResponse<String> filteredById =
        get(scimPath("/Users") + "?filter=" + encodeQuery("id eq \"" + userId + "\""), bearerToken);
    assertStatus(200, filteredById);
    JsonNode filteredByIdList = JsonUtils.objectMapper().readTree(filteredById.body());
    Assertions.assertEquals(1, filteredByIdList.get("totalResults").asInt(), filteredById.body());
    Assertions.assertFalse(
        filteredByIdList.get("Resources").get(0).get("active").asBoolean(),
        () -> "GET filter id after disable: " + filteredById.body());

    HttpResponse<String> enabled =
        patch(scimPath("/Users/" + userId), patchBody("replace", "active", true), bearerToken);
    assertStatus(200, enabled);
    Assertions.assertTrue(
        JsonUtils.objectMapper().readTree(enabled.body()).get("active").asBoolean());

    assertStatus(204, delete(scimPath("/Users/" + userId), bearerToken));
  }

  @Test
  void testPatchDisableThenGetByIdAndFilter() throws Exception {
    String userName = "lilian.streich@sauer.com";
    Map<String, Object> createBody = new HashMap<>();
    createBody.put("schemas", new String[] {SCIM_USER_SCHEMA});
    createBody.put("userName", userName);
    createBody.put("active", true);

    HttpResponse<String> created = post(scimPath("/Users"), createBody, bearerToken);
    assertStatus(201, created);
    JsonNode createdUser = JsonUtils.objectMapper().readTree(created.body());
    String userId = createdUser.get("id").asText();
    Assertions.assertTrue(createdUser.get("active").asBoolean());

    HttpResponse<String> disabled =
        patch(scimPath("/Users/" + userId), patchBody("replace", "active", false), bearerToken);
    assertStatus(200, disabled);
    JsonNode disabledUser = JsonUtils.objectMapper().readTree(disabled.body());
    Assertions.assertFalse(
        disabledUser.get("active").asBoolean(), () -> "PATCH disable body: " + disabled.body());

    HttpResponse<String> fetched = get(scimPath("/Users/" + userId), bearerToken);
    assertStatus(200, fetched);
    JsonNode fetchedUser = JsonUtils.objectMapper().readTree(fetched.body());
    Assertions.assertFalse(
        fetchedUser.path("active").asBoolean(true),
        () -> "Fetch by Id after disable: " + fetched.body());

    HttpResponse<String> filtered =
        get(
            scimPath("/Users") + "?filter=" + encodeQuery("userName eq \"" + userName + "\""),
            bearerToken);
    assertStatus(200, filtered);
    JsonNode list = JsonUtils.objectMapper().readTree(filtered.body());
    Assertions.assertEquals(1, list.get("totalResults").asInt(), () -> filtered.body());
    Assertions.assertFalse(
        list.get("Resources").get(0).path("active").asBoolean(true),
        () -> "Fetch by Filter after disable: " + filtered.body());

    HttpResponse<String> filteredById =
        get(scimPath("/Users") + "?filter=" + encodeQuery("id eq \"" + userId + "\""), bearerToken);
    assertStatus(200, filteredById);
    JsonNode filteredByIdList = JsonUtils.objectMapper().readTree(filteredById.body());
    Assertions.assertEquals(1, filteredByIdList.get("totalResults").asInt(), filteredById.body());
    Assertions.assertFalse(
        filteredByIdList.get("Resources").get(0).path("active").asBoolean(true),
        () -> "Fetch by id filter after disable: " + filteredById.body());

    assertStatus(204, delete(scimPath("/Users/" + userId), bearerToken));
  }

  @Test
  void testDeleteUserThenFilter() throws Exception {
    String userName = "scim-it-delete-user";
    Map<String, Object> createBody = new HashMap<>();
    createBody.put("schemas", new String[] {SCIM_USER_SCHEMA});
    createBody.put("userName", userName);
    createBody.put("active", true);

    HttpResponse<String> created = post(scimPath("/Users"), createBody, bearerToken);
    assertStatus(201, created);
    String userId = JsonUtils.objectMapper().readTree(created.body()).get("id").asText();

    assertStatus(204, delete(scimPath("/Users/" + userId), bearerToken));
    assertStatus(404, get(scimPath("/Users/" + userId), bearerToken));
    assertFilterEmpty("userName eq \"" + userName + "\"");
    assertFilterEmpty("id eq \"" + userId + "\"");
  }

  @Test
  void testUserCreateRequiresUserName() throws Exception {
    Map<String, Object> body = new HashMap<>();
    body.put("schemas", new String[] {SCIM_USER_SCHEMA});
    body.put("externalId", "missing-user-name");
    assertStatus(400, post(scimPath("/Users"), body, bearerToken));
  }

  @Test
  void testUserCreateWithoutExternalId() throws Exception {
    Map<String, Object> body = new HashMap<>();
    body.put("schemas", new String[] {SCIM_USER_SCHEMA});
    body.put("userName", "scim-it-user-no-ext");
    body.put("active", true);

    HttpResponse<String> created = post(scimPath("/Users"), body, bearerToken);
    assertStatus(201, created);
    JsonNode createdUser = JsonUtils.objectMapper().readTree(created.body());
    String userId = createdUser.get("id").asText();
    Assertions.assertFalse(createdUser.hasNonNull("externalId"));

    assertStatus(204, delete(scimPath("/Users/" + userId), bearerToken));
  }

  @Test
  void testGroupLifecycle() throws Exception {
    String externalId = "scim-it-group-ext";
    String displayName = "scim-it-group";

    HttpResponse<String> created =
        post(scimPath("/Groups"), groupBody(externalId, displayName, List.of()), bearerToken);
    assertStatus(201, created);
    JsonNode createdGroup = JsonUtils.objectMapper().readTree(created.body());
    String groupId = createdGroup.get("id").asText();
    Assertions.assertEquals(displayName, createdGroup.get("displayName").asText());
    Assertions.assertEquals(externalId, createdGroup.get("externalId").asText());
    Assertions.assertNotEquals(externalId, groupId);

    assertStatus(
        409, post(scimPath("/Groups"), groupBody(externalId, displayName, List.of()), bearerToken));

    HttpResponse<String> fetched = get(scimPath("/Groups/" + groupId), bearerToken);
    assertStatus(200, fetched);
    Assertions.assertEquals(
        groupId, JsonUtils.objectMapper().readTree(fetched.body()).get("id").asText());

    HttpResponse<String> deleted = delete(scimPath("/Groups/" + groupId), bearerToken);
    assertStatus(204, deleted);

    HttpResponse<String> missing = get(scimPath("/Groups/" + groupId), bearerToken);
    assertStatus(404, missing);
    assertGroupFilterEmpty("displayName eq \"" + displayName + "\"");
    assertGroupFilterEmpty("id eq \"" + groupId + "\"");
  }

  @Test
  void testGroupProvisioningImportWithMembers() throws Exception {
    String userExternalId = "scim-it-group-member-user-ext";
    String userName = "scim-it-group-member-user";
    String groupExternalId = "scim-it-group-with-members-ext";
    String groupDisplayName = "scim-it-group-with-members";
    String extraUserExternalId = "scim-it-group-member-user2-ext";
    String extraUserName = "scim-it-group-member-user2";

    HttpResponse<String> firstUserCreated =
        post(scimPath("/Users"), userBody(userExternalId, userName, true), bearerToken);
    assertStatus(201, firstUserCreated);
    String firstUserId =
        JsonUtils.objectMapper().readTree(firstUserCreated.body()).get("id").asText();

    HttpResponse<String> secondUserCreated =
        post(scimPath("/Users"), userBody(extraUserExternalId, extraUserName, true), bearerToken);
    assertStatus(201, secondUserCreated);
    String secondUserId =
        JsonUtils.objectMapper().readTree(secondUserCreated.body()).get("id").asText();

    Map<String, Object> member = Map.of("value", firstUserId);
    HttpResponse<String> created =
        post(
            scimPath("/Groups"),
            groupBody(groupExternalId, groupDisplayName, List.of(member)),
            bearerToken);
    assertStatus(201, created);
    JsonNode createdGroup = JsonUtils.objectMapper().readTree(created.body());
    String groupId = createdGroup.get("id").asText();
    Assertions.assertEquals(1, createdGroup.get("members").size());
    Assertions.assertEquals(firstUserId, createdGroup.get("members").get(0).get("value").asText());

    HttpResponse<String> filtered =
        get(
            scimPath("/Groups")
                + "?filter="
                + encodeQuery("externalId eq \"" + groupExternalId + "\""),
            bearerToken);
    assertStatus(200, filtered);
    JsonNode list = JsonUtils.objectMapper().readTree(filtered.body());
    Assertions.assertEquals(1, list.get("totalResults").asInt());
    Assertions.assertEquals(groupId, list.get("Resources").get(0).get("id").asText());

    HttpResponse<String> withExtraMember =
        patch(
            scimPath("/Groups/" + groupId),
            patchBody("add", "members", List.of(Map.of("value", secondUserId))),
            bearerToken);
    assertStatus(200, withExtraMember);
    JsonNode membersAfterAdd =
        JsonUtils.objectMapper().readTree(withExtraMember.body()).get("members");
    Assertions.assertEquals(2, membersAfterAdd.size());

    HttpResponse<String> withoutFirstMember =
        patch(
            scimPath("/Groups/" + groupId),
            patchBody("remove", "members", List.of(Map.of("value", firstUserId))),
            bearerToken);
    assertStatus(200, withoutFirstMember);
    JsonNode membersAfterRemove =
        JsonUtils.objectMapper().readTree(withoutFirstMember.body()).get("members");
    Assertions.assertEquals(1, membersAfterRemove.size());
    Assertions.assertEquals(secondUserId, membersAfterRemove.get(0).get("value").asText());

    assertStatus(204, delete(scimPath("/Groups/" + groupId), bearerToken));
    assertStatus(204, delete(scimPath("/Users/" + firstUserId), bearerToken));
    assertStatus(204, delete(scimPath("/Users/" + secondUserId), bearerToken));
  }

  @Test
  void testGroupCreateRequiresDisplayName() throws Exception {
    Map<String, Object> body = new HashMap<>();
    body.put("schemas", new String[] {SCIM_GROUP_SCHEMA});
    body.put("externalId", "missing-display-name");
    assertStatus(400, post(scimPath("/Groups"), body, bearerToken));
  }

  @Test
  void testGroupCreateWithoutExternalId() throws Exception {
    Map<String, Object> body = new HashMap<>();
    body.put("schemas", new String[] {SCIM_GROUP_SCHEMA});
    body.put("displayName", "scim-it-group-no-ext");

    HttpResponse<String> created = post(scimPath("/Groups"), body, bearerToken);
    assertStatus(201, created);
    JsonNode createdGroup = JsonUtils.objectMapper().readTree(created.body());
    String groupId = createdGroup.get("id").asText();
    Assertions.assertFalse(createdGroup.hasNonNull("externalId"));

    assertStatus(204, delete(scimPath("/Groups/" + groupId), bearerToken));
  }

  @Test
  void testDeleteGroupThenFilter() throws Exception {
    String displayName = "scim-it-delete-group";
    Map<String, Object> body = new HashMap<>();
    body.put("schemas", new String[] {SCIM_GROUP_SCHEMA});
    body.put("displayName", displayName);

    HttpResponse<String> created = post(scimPath("/Groups"), body, bearerToken);
    assertStatus(201, created);
    String groupId = JsonUtils.objectMapper().readTree(created.body()).get("id").asText();

    assertStatus(204, delete(scimPath("/Groups/" + groupId), bearerToken));
    assertStatus(404, get(scimPath("/Groups/" + groupId), bearerToken));
    assertGroupFilterEmpty("displayName eq \"" + displayName + "\"");
    assertGroupFilterEmpty("id eq \"" + groupId + "\"");
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

  private static String encodeQuery(String value) {
    return URLEncoder.encode(value, StandardCharsets.UTF_8);
  }

  private static HttpResponse<String> get(String path, String bearerToken) throws Exception {
    HttpRequest.Builder builder =
        HttpRequest.newBuilder()
            .uri(URI.create(environment.scimBaseUri() + path))
            .GET()
            .header("Accept", path.startsWith("/scim/health") ? "application/json" : SCIM_ACCEPT);
    if (bearerToken != null) {
      builder.header(AuthConstants.HTTP_HEADER_AUTHORIZATION, "Bearer " + bearerToken);
    }
    return HTTP.send(builder.build(), HttpResponse.BodyHandlers.ofString());
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

  private static void assertFilterEmpty(String filter) throws Exception {
    assertCollectionFilterEmpty("/Users", filter);
  }

  private static void assertGroupFilterEmpty(String filter) throws Exception {
    assertCollectionFilterEmpty("/Groups", filter);
  }

  private static void assertCollectionFilterEmpty(String collection, String filter)
      throws Exception {
    HttpResponse<String> response =
        get(scimPath(collection) + "?filter=" + encodeQuery(filter), bearerToken);
    assertStatus(200, response);
    JsonNode list = JsonUtils.objectMapper().readTree(response.body());
    Assertions.assertEquals(0, list.get("totalResults").asInt(), () -> response.body());
    JsonNode resources = list.get("Resources");
    Assertions.assertTrue(
        resources == null || resources.isNull() || resources.isEmpty(),
        () -> "Expected no Resources, got: " + response.body());
  }
}
