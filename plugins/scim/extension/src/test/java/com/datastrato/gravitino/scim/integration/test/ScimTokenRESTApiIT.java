/*
 * Copyright 2026 Datastrato Inc.
 */

package com.datastrato.gravitino.scim.integration.test;

import com.datastrato.gravitino.scim.ScimUtils;
import com.datastrato.gravitino.scim.basic.token.ScimTokenGenerator;
import com.datastrato.gravitino.scim.dto.requests.CreateScimTokenRequest;
import com.datastrato.gravitino.scim.dto.requests.RotateScimTokenRequest;
import com.datastrato.gravitino.scim.dto.responses.ScimProvisioningListResponse;
import com.datastrato.gravitino.scim.dto.responses.ScimTokenDeleteResponse;
import com.datastrato.gravitino.scim.dto.responses.ScimTokenListResponse;
import com.datastrato.gravitino.scim.dto.responses.ScimTokenOverviewResponse;
import com.datastrato.gravitino.scim.dto.responses.ScimTokenResponse;
import com.datastrato.gravitino.scim.web.rest.feature.ScimTokenRESTFeature;
import com.google.common.collect.Maps;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.HashMap;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;
import org.apache.gravitino.Configs;
import org.apache.gravitino.auth.AuthConstants;
import org.apache.gravitino.dto.responses.ErrorConstants;
import org.apache.gravitino.integration.test.util.BaseIT;
import org.apache.gravitino.integration.test.util.ITUtils;
import org.apache.gravitino.json.JsonUtils;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.DisabledIfSystemProperty;

/**
 * End-to-end tests for SCIM token admin REST APIs.
 *
 * <p>Run with {@code -PjdbcBackend=h2}, {@code mysql}, or {@code postgresql} to exercise the same
 * API surface against each relational backend.
 */
@DisabledIfSystemProperty(named = ITUtils.TEST_MODE, matches = ITUtils.DEPLOY_TEST_MODE)
class ScimTokenRESTApiIT extends BaseIT {

  private static final String ACCEPT = "application/vnd.gravitino.v1+json";
  private static final String OWNER = "scimOwner";
  private static final String TOKEN_NAME = "prod";
  private static final String MISSING_TOKEN = "missing-token";

  private static final HttpClient HTTP = HttpClient.newHttpClient();

  private static String apiBase;

  @BeforeAll
  public void startIntegrationTest() throws Exception {
    ignoreIcebergAuxRestService = true;
    ignoreLanceAuxRestService = true;
    customConfigs = ScimTokenITServerSupport.preserveScimAuxServiceNames(customConfigs);

    Map<String, String> configs = Maps.newHashMap();
    configs.put("SimpleAuthUserName", OWNER);
    configs.put(Configs.ENABLE_AUTHORIZATION.getKey(), String.valueOf(true));
    configs.put(Configs.CACHE_ENABLED.getKey(), String.valueOf(false));
    configs.put(Configs.STORE_DELETE_AFTER_TIME.getKey(), String.valueOf(20 * 60 * 1000L));
    configs.put(Configs.SERVICE_ADMINS.getKey(), OWNER);
    configs.put(Configs.AUTHENTICATORS.getKey(), "simple");
    configs.put(
        Configs.REST_API_EXTENSION_PACKAGES.getKey(),
        ScimTokenRESTFeature.SCIM_TOKEN_REST_EXTENSION_PACKAGE);
    configs.putAll(ScimTokenITServerSupport.tokenAdminServerConfigs());
    registerCustomConfigs(configs);
    super.startIntegrationTest();
    ScimEnterpriseSchemaInitializer.initialize(serverConfig);

    apiBase = serverUri + "/api";
    client.createMetalake("scimTokenMetalake", "", new HashMap<>());
  }

  @AfterAll
  @Override
  public void stopIntegrationTest() throws IOException, InterruptedException {
    client.dropMetalake("scimTokenMetalake", true);
    super.stopIntegrationTest();
  }

  @Test
  void testListProvisioning() throws Exception {
    createToken("provisioning-a", null);
    createToken("provisioning-b", null);
    ScimProvisioningListResponse list =
        read(get("/scim/provisioning", OWNER), ScimProvisioningListResponse.class);
    Assertions.assertEquals(1, list.getProvisioning().size());
    Assertions.assertEquals(ScimUtils.scimBasePath(), list.getProvisioning().get(0).getEndpoint());
    Assertions.assertTrue(list.getProvisioning().get(0).getTokenCount() >= 2L);
    Assertions.assertTrue(list.getProvisioning().get(0).getLastUsedAt() >= 0L);
  }

  @Test
  void testListTokens() throws Exception {
    createToken("list-a", null);
    createToken("list-b", 30);

    ScimTokenListResponse list = read(get("/scim/tokens", OWNER), ScimTokenListResponse.class);
    list.validate();
    var byName = index(list.getTokens(), t -> t.getTokenName());
    Assertions.assertTrue(byName.containsKey("list-a"));
    Assertions.assertEquals("valid", byName.get("list-a").getStatus());
    Assertions.assertEquals(0L, byName.get("list-a").getLastUsedAt());
    Assertions.assertTrue(byName.get("list-b").getExpiresAt() > 0L);
    Assertions.assertEquals(0L, byName.get("list-b").getLastUsedAt());
  }

  @Test
  void testGetTokenOverview() throws Exception {
    createToken("overview-a", null);
    createToken("overview-b", null);

    ScimTokenOverviewResponse overview =
        read(get("/scim/tokens/overview", OWNER), ScimTokenOverviewResponse.class);
    overview.validate();
    Assertions.assertTrue(overview.getTokenCount() >= 2L);
    Assertions.assertEquals(overview.getTokenCount(), overview.getTokens().size());
    var byName = index(overview.getTokens(), t -> t.getTokenName());
    Assertions.assertTrue(byName.containsKey("overview-a"));
    Assertions.assertTrue(byName.get("overview-a").getCreatedAt() > 0L);
    Assertions.assertEquals(0L, byName.get("overview-a").getLastUsedAt());
  }

  @Test
  void testCrud() throws Exception {
    ScimTokenResponse created = createToken(TOKEN_NAME, null);
    Assertions.assertEquals(TOKEN_NAME, created.getToken().getTokenName());
    Assertions.assertTrue(ScimTokenGenerator.hasValidPrefix(created.getToken().getTokenValue()));
    Assertions.assertEquals(0L, created.getToken().getExpiresAt());

    assertError(
        400,
        post("/scim/tokens", new CreateScimTokenRequest("", null), OWNER),
        ErrorConstants.ILLEGAL_ARGUMENTS_CODE);
    assertError(
        409,
        post("/scim/tokens", new CreateScimTokenRequest(TOKEN_NAME, null), OWNER),
        ErrorConstants.ALREADY_EXISTS_CODE);

    ScimTokenResponse rotated = rotateToken(TOKEN_NAME, new RotateScimTokenRequest(30));
    Assertions.assertEquals(TOKEN_NAME, rotated.getToken().getTokenName());
    Assertions.assertNotEquals(
        created.getToken().getTokenValue(), rotated.getToken().getTokenValue());
    Assertions.assertTrue(rotated.getToken().getExpiresAt() > 0L);

    assertError(
        400,
        post("/scim/tokens/" + TOKEN_NAME + "/rotate", new RotateScimTokenRequest(0), OWNER),
        ErrorConstants.ILLEGAL_ARGUMENTS_CODE);

    ScimTokenDeleteResponse deleted = deleteToken(TOKEN_NAME);
    Assertions.assertTrue(deleted.getDeleted());

    assertError(404, delete("/scim/tokens/" + TOKEN_NAME, OWNER), ErrorConstants.NOT_FOUND_CODE);
    assertError(404, delete("/scim/tokens/" + MISSING_TOKEN, OWNER), ErrorConstants.NOT_FOUND_CODE);
  }

  private static ScimTokenResponse createToken(String tokenName, Integer expiresInDays)
      throws Exception {
    HttpResponse<String> response =
        post("/scim/tokens", new CreateScimTokenRequest(tokenName, expiresInDays), OWNER);
    Assertions.assertEquals(200, response.statusCode(), response.body());
    ScimTokenResponse tokenResponse =
        JsonUtils.objectMapper().readValue(response.body(), ScimTokenResponse.class);
    tokenResponse.validate();
    return tokenResponse;
  }

  private static ScimTokenResponse rotateToken(String tokenName, RotateScimTokenRequest request)
      throws Exception {
    HttpResponse<String> response = post("/scim/tokens/" + tokenName + "/rotate", request, OWNER);
    Assertions.assertEquals(200, response.statusCode(), response.body());
    ScimTokenResponse tokenResponse =
        JsonUtils.objectMapper().readValue(response.body(), ScimTokenResponse.class);
    tokenResponse.validate();
    return tokenResponse;
  }

  private static ScimTokenDeleteResponse deleteToken(String tokenName) throws Exception {
    HttpResponse<String> response = delete("/scim/tokens/" + tokenName, OWNER);
    Assertions.assertEquals(200, response.statusCode(), response.body());
    ScimTokenDeleteResponse deleteResponse =
        JsonUtils.objectMapper().readValue(response.body(), ScimTokenDeleteResponse.class);
    deleteResponse.validate();
    return deleteResponse;
  }

  private static HttpResponse<String> get(String path, String username) throws Exception {
    return HTTP.send(
        authorized(username).uri(URI.create(apiBase + path)).GET().build(),
        HttpResponse.BodyHandlers.ofString());
  }

  private static HttpResponse<String> post(String path, Object body, String username)
      throws Exception {
    return HTTP.send(
        authorized(username)
            .uri(URI.create(apiBase + path))
            .header("Content-Type", "application/json")
            .POST(jsonBody(body))
            .build(),
        HttpResponse.BodyHandlers.ofString());
  }

  private static HttpResponse<String> delete(String path, String username) throws Exception {
    return HTTP.send(
        authorized(username).uri(URI.create(apiBase + path)).DELETE().build(),
        HttpResponse.BodyHandlers.ofString());
  }

  private static HttpRequest.Builder authorized(String username) {
    return HttpRequest.newBuilder()
        .header("Accept", ACCEPT)
        .header("Authorization", simpleAuth(username));
  }

  private static HttpRequest.BodyPublisher jsonBody(Object body) throws Exception {
    return HttpRequest.BodyPublishers.ofString(JsonUtils.objectMapper().writeValueAsString(body));
  }

  private static String simpleAuth(String username) {
    return AuthConstants.AUTHORIZATION_BASIC_HEADER
        + Base64.getEncoder()
            .encodeToString((username + ":dummy").getBytes(StandardCharsets.UTF_8));
  }

  private static void assertError(int expectedStatus, HttpResponse<String> response, int errorCode)
      throws Exception {
    Assertions.assertEquals(expectedStatus, response.statusCode(), response.body());
    Assertions.assertEquals(errorCode, errorCode(response));
  }

  private static int errorCode(HttpResponse<String> response) throws Exception {
    return JsonUtils.objectMapper().readTree(response.body()).get("code").asInt();
  }

  private static <T> T read(HttpResponse<String> response, Class<T> type) throws Exception {
    Assertions.assertEquals(200, response.statusCode(), response.body());
    return JsonUtils.objectMapper().readValue(response.body(), type);
  }

  private static <T, K> Map<K, T> index(java.util.List<T> items, Function<T, K> key) {
    return items.stream().collect(Collectors.toMap(key, Function.identity()));
  }
}
