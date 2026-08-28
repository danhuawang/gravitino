/*
 * Copyright 2024 Datastrato Pvt Ltd.
 */
package com.datastrato.gravitino.search.store.opensearch;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import java.io.File;
import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.Base64;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** Tests the Gravitino API handling used by {@code index.sh rebuild}. */
public class TestIndexScriptRebuild {

  private static final String DEFAULT_LIST_RESPONSE = "{\"metalakes\":[{\"name\":\"metalake1\"}]}";
  private static final String OAUTH_CLIENT_ID = "rebuild-client";
  private static final String OAUTH_CLIENT_SECRET = "secret&with=\"\\separators";
  private static final String OAUTH_SCOPE = "api://gravitino/.default";

  private static String curlExecutable;

  @TempDir private Path temporaryDirectory;

  private HttpServer server;
  private Path curlArgumentsFile;
  private Path curlWrapperDirectory;
  private String listResponse;
  private int listStatus;
  private String rebuildResponse;
  private int rebuildStatus;
  private String tokenResponse;
  private int tokenStatus;
  private List<String> issuedTokens;
  private List<String> authorizationHeaders;
  private List<Map<String, String>> oauthRequestForms;
  private AtomicInteger apiRequestCount;
  private AtomicInteger listRequestCount;
  private AtomicInteger rebuildRequestCount;
  private AtomicInteger tokenRequestCount;
  private boolean rejectFirstListRequest;
  private boolean rejectFirstRebuildRequest;

  @BeforeAll
  static void checkRequiredCommands() throws Exception {
    curlExecutable = findCommand("curl");
    assertFalse(curlExecutable.isEmpty(), "index.sh tests require curl");
    assertFalse(findCommand("jq").isEmpty(), "index.sh tests require jq");
  }

  @BeforeEach
  void setUp() throws Exception {
    stageBinDirectory();
    listResponse = DEFAULT_LIST_RESPONSE;
    listStatus = 200;
    rebuildResponse = "{}";
    rebuildStatus = 200;
    tokenResponse = null;
    tokenStatus = 200;
    issuedTokens = List.of("oauth-token");
    authorizationHeaders = Collections.synchronizedList(new ArrayList<>());
    oauthRequestForms = Collections.synchronizedList(new ArrayList<>());
    apiRequestCount = new AtomicInteger();
    listRequestCount = new AtomicInteger();
    rebuildRequestCount = new AtomicInteger();
    tokenRequestCount = new AtomicInteger();
    rejectFirstListRequest = false;
    rejectFirstRebuildRequest = false;

    server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
    server.createContext("/", this::respond);
    server.start();
  }

  @AfterEach
  void tearDown() {
    if (server != null) {
      server.stop(0);
    }
  }

  @Test
  void testBasicAuthentication() throws Exception {
    String password = "password:with=\"\\separators";
    ScriptResult result =
        runScript(Map.of(), "--gravitino_username=admin", "--gravitino_password=" + password);

    assertEquals(0, result.exitCode, result.output);
    String expected =
        "Basic "
            + Base64.getEncoder()
                .encodeToString(("admin:" + password).getBytes(StandardCharsets.UTF_8));
    assertEquals(List.of(expected, expected), authorizationHeaders);
    assertEquals(1, rebuildRequestCount.get());
    assertCurlArgumentsDoNotContain("admin", password);
  }

  @Test
  void testBearerAuthenticationTakesPrecedence() throws Exception {
    ScriptResult result =
        runScript(
            Map.of(),
            "--gravitino_username=admin",
            "--gravitino_password=password",
            "--gravitino_token=token.with=separator");

    assertEquals(0, result.exitCode, result.output);
    assertEquals(
        List.of("Bearer token.with=separator", "Bearer token.with=separator"),
        authorizationHeaders);
    assertEquals(1, rebuildRequestCount.get());
    assertCurlArgumentsDoNotContain("token.with=separator");
  }

  @Test
  void testBearerAuthenticationWarnsWhenOAuthIsAlsoConfigured() throws Exception {
    ScriptResult result =
        runScript(
            Map.of(
                "GRAVITINO_TOKEN", "static-token",
                "GRAVITINO_OAUTH_TOKEN_URI", tokenUri(),
                "GRAVITINO_OAUTH_CLIENT_ID", OAUTH_CLIENT_ID,
                "GRAVITINO_OAUTH_CLIENT_SECRET", OAUTH_CLIENT_SECRET,
                "GRAVITINO_OAUTH_SCOPE", OAUTH_SCOPE));

    assertEquals(0, result.exitCode, result.output);
    assertTrue(result.output.contains("[WARN] GRAVITINO_TOKEN takes precedence"), result.output);
    assertEquals(0, tokenRequestCount.get());
    assertEquals(List.of("Bearer static-token", "Bearer static-token"), authorizationHeaders);
    assertCurlArgumentsDoNotContain("static-token", OAUTH_CLIENT_SECRET);
  }

  @Test
  void testAuthenticationFromEnvironment() throws Exception {
    ScriptResult result =
        runScript(
            Map.of(
                "GRAVITINO_USERNAME", "hook-user",
                "GRAVITINO_PASSWORD", "hook-password"));

    assertEquals(0, result.exitCode, result.output);
    String expected =
        "Basic "
            + Base64.getEncoder()
                .encodeToString("hook-user:hook-password".getBytes(StandardCharsets.UTF_8));
    assertEquals(List.of(expected, expected), authorizationHeaders);
    assertCurlArgumentsDoNotContain("hook-user", "hook-password");
  }

  @Test
  void testPartialOAuthConfigurationDoesNotBlockBasicAuthentication() throws Exception {
    ScriptResult result =
        runScript(
            Map.of(
                "GRAVITINO_USERNAME", "basic-user",
                "GRAVITINO_PASSWORD", "basic-password",
                "GRAVITINO_OAUTH_SCOPE", OAUTH_SCOPE));

    assertEquals(0, result.exitCode, result.output);
    String expected =
        "Basic "
            + Base64.getEncoder()
                .encodeToString("basic-user:basic-password".getBytes(StandardCharsets.UTF_8));
    assertEquals(List.of(expected, expected), authorizationHeaders);
    assertEquals(0, tokenRequestCount.get());
  }

  @Test
  void testOAuthClientCredentialsAuthentication() throws Exception {
    ScriptResult result = runScript(Map.of(), oauthArguments());

    assertEquals(0, result.exitCode, result.output);
    assertEquals(1, tokenRequestCount.get());
    assertEquals(
        List.of(
            Map.of(
                "grant_type", "client_credentials",
                "client_id", OAUTH_CLIENT_ID,
                "client_secret", OAUTH_CLIENT_SECRET,
                "scope", OAUTH_SCOPE)),
        oauthRequestForms);
    assertEquals(List.of("Bearer oauth-token", "Bearer oauth-token"), authorizationHeaders);
    assertEquals(1, rebuildRequestCount.get());
    assertCurlArgumentsDoNotContain(OAUTH_CLIENT_SECRET, "oauth-token");
  }

  @Test
  void testOAuthScopeIsOptional() throws Exception {
    ScriptResult result = runScript(Map.of(), oauthArgumentsWithoutScope());

    assertEquals(0, result.exitCode, result.output);
    assertEquals(1, tokenRequestCount.get());
    assertEquals(
        List.of(
            Map.of(
                "grant_type", "client_credentials",
                "client_id", OAUTH_CLIENT_ID,
                "client_secret", OAUTH_CLIENT_SECRET)),
        oauthRequestForms);
    assertEquals(List.of("Bearer oauth-token", "Bearer oauth-token"), authorizationHeaders);
  }

  @Test
  void testOAuthClientCredentialsFromEnvironmentAndConfig() throws Exception {
    Path configurationDirectory = temporaryDirectory.resolve("conf");
    Files.createDirectories(configurationDirectory);
    Files.writeString(
        configurationDirectory.resolve("gravitino.conf"),
        "gravitino.datastrato.search.storage.impl = opensearch\n"
            + "gravitino.authenticator.oauth.serverUri = http://127.0.0.1:"
            + server.getAddress().getPort()
            + "/\n"
            + "gravitino.authenticator.oauth.tokenPath = /oauth/token\n");

    ScriptResult result =
        runScript(
            Map.of(
                "GRAVITINO_HOME", temporaryDirectory.toString(),
                "GRAVITINO_OAUTH_CLIENT_ID", OAUTH_CLIENT_ID,
                "GRAVITINO_OAUTH_CLIENT_SECRET", OAUTH_CLIENT_SECRET,
                "GRAVITINO_OAUTH_SCOPE", OAUTH_SCOPE));

    assertEquals(0, result.exitCode, result.output);
    assertEquals(1, tokenRequestCount.get());
    assertEquals(List.of("Bearer oauth-token", "Bearer oauth-token"), authorizationHeaders);
  }

  @Test
  void testOAuthRefreshesAndRetriesUnauthorizedMetalakeList() throws Exception {
    issuedTokens = List.of("expired-token", "fresh-token");
    rejectFirstListRequest = true;

    ScriptResult result = runScript(Map.of(), oauthArguments());

    assertEquals(0, result.exitCode, result.output);
    assertEquals(2, tokenRequestCount.get());
    assertEquals(2, listRequestCount.get());
    assertEquals(
        List.of("Bearer expired-token", "Bearer fresh-token", "Bearer fresh-token"),
        authorizationHeaders);
    assertEquals(1, rebuildRequestCount.get());
  }

  @Test
  void testOAuthRefreshesAndRetriesUnauthorizedRebuild() throws Exception {
    issuedTokens = List.of("expired-token", "fresh-token");
    rejectFirstRebuildRequest = true;

    ScriptResult result = runScript(Map.of(), oauthArguments());

    assertEquals(0, result.exitCode, result.output);
    assertEquals(2, tokenRequestCount.get());
    assertEquals(
        List.of("Bearer expired-token", "Bearer expired-token", "Bearer fresh-token"),
        authorizationHeaders);
    assertEquals(2, rebuildRequestCount.get());
  }

  @Test
  void testOAuthTokenEndpointFailureDoesNotExposeClientSecret() throws Exception {
    tokenStatus = 401;
    tokenResponse = "{\"error\":\"invalid_client\",\"error_description\":\"client rejected\"}";

    ScriptResult result = runScript(Map.of(), oauthArguments());

    assertEquals(1, result.exitCode, result.output);
    assertTrue(result.output.contains("HTTP 401"), result.output);
    assertTrue(result.output.contains("client rejected"), result.output);
    assertFalse(result.output.contains(OAUTH_CLIENT_SECRET), result.output);
    assertEquals(0, apiRequestCount.get());
    assertCurlArgumentsDoNotContain(OAUTH_CLIENT_SECRET);
  }

  @Test
  void testInvalidOAuthTokenResponseDoesNotExposeAccessToken() throws Exception {
    tokenResponse = "{\"access_token\":\"do-not-print-this-token\",\"token_type\":\"mac\"}";

    ScriptResult result = runScript(Map.of(), oauthArguments());

    assertEquals(1, result.exitCode, result.output);
    assertTrue(result.output.contains("valid bearer access token"), result.output);
    assertFalse(result.output.contains("do-not-print-this-token"), result.output);
    assertEquals(0, apiRequestCount.get());
  }

  @Test
  void testOAuthConfigurationWithoutTokenUriIsRejected() throws Exception {
    ScriptResult result =
        runScript(
            Map.of(),
            "--gravitino_oauth_client_id=" + OAUTH_CLIENT_ID,
            "--gravitino_oauth_client_secret=" + OAUTH_CLIENT_SECRET);

    assertEquals(1, result.exitCode, result.output);
    assertTrue(result.output.contains("Missing OAuth parameters"), result.output);
    assertTrue(result.output.contains("gravitino_oauth_token_uri"), result.output);
    assertEquals(0, tokenRequestCount.get());
    assertEquals(0, apiRequestCount.get());
  }

  @Test
  void testNoMetalakesIsSuccessful() throws Exception {
    for (String response : List.of("{\"metalakes\":null}", "{\"metalakes\":[]}", "{}")) {
      listResponse = response;

      ScriptResult result = runScript(Map.of());

      assertEquals(0, result.exitCode, result.output);
      assertTrue(result.output.contains("No metalakes found. Nothing to rebuild."), result.output);
    }
    assertEquals(3, apiRequestCount.get());
    assertEquals(0, rebuildRequestCount.get());
  }

  @Test
  void testMetalakeListFailureIncludesResponse() throws Exception {
    listStatus = 401;
    listResponse =
        "{\"code\":1011,\"type\":\"UnauthorizedException\",\"message\":\"invalid token\"}";

    ScriptResult result = runScript(Map.of());

    assertEquals(1, result.exitCode, result.output);
    assertTrue(result.output.contains("HTTP 401"), result.output);
    assertTrue(result.output.contains(listResponse), result.output);
    assertEquals(0, rebuildRequestCount.get());
  }

  @Test
  void testMalformedMetalakeListIncludesResponse() throws Exception {
    listResponse = "not-json";

    ScriptResult result = runScript(Map.of());

    assertEquals(1, result.exitCode, result.output);
    assertTrue(result.output.contains("Failed to parse"), result.output);
    assertTrue(result.output.contains("Response: not-json"), result.output);
    assertEquals(0, rebuildRequestCount.get());
  }

  @Test
  void testEmptyMetalakeListResponseIsIdentified() throws Exception {
    listResponse = "";

    ScriptResult result = runScript(Map.of());

    assertEquals(1, result.exitCode, result.output);
    assertTrue(result.output.contains("Failed to parse"), result.output);
    assertTrue(result.output.contains("Response: <empty>"), result.output);
    assertEquals(0, rebuildRequestCount.get());
  }

  @Test
  void testRebuildFailureIncludesResponse() throws Exception {
    rebuildStatus = 401;
    rebuildResponse = "{\"message\":\"expired token\"}";

    ScriptResult result = runScript(Map.of(), "--gravitino_token=expired");

    assertEquals(1, result.exitCode, result.output);
    assertTrue(result.output.contains("HTTP 401"), result.output);
    assertTrue(result.output.contains(rebuildResponse), result.output);
    assertEquals(1, rebuildRequestCount.get());
    assertEquals(0, tokenRequestCount.get());
  }

  @Test
  void testIncompleteBasicAuthenticationIsRejected() throws Exception {
    ScriptResult result = runScript(Map.of(), "--gravitino_username=admin");

    assertEquals(1, result.exitCode, result.output);
    assertTrue(
        result.output.contains("Both gravitino_username and gravitino_password are required"),
        result.output);
    assertEquals(0, apiRequestCount.get());
  }

  private void respond(HttpExchange exchange) throws IOException {
    String path = exchange.getRequestURI().getPath();
    if ("POST".equals(exchange.getRequestMethod()) && "/oauth/token".equals(path)) {
      respondToTokenRequest(exchange);
      return;
    }

    apiRequestCount.incrementAndGet();
    authorizationHeaders.add(exchange.getRequestHeaders().getFirst("Authorization"));

    int status;
    String body;
    if ("GET".equals(exchange.getRequestMethod()) && "/api/metalakes".equals(path)) {
      int requestNumber = listRequestCount.incrementAndGet();
      if (rejectFirstListRequest && requestNumber == 1) {
        status = 401;
        body = "{\"message\":\"expired token\"}";
      } else {
        status = listStatus;
        body = listResponse;
      }
    } else if ("POST".equals(exchange.getRequestMethod())
        && "/api/search/rebuild/metalakes/metalake1".equals(path)) {
      int requestNumber = rebuildRequestCount.incrementAndGet();
      if (rejectFirstRebuildRequest && requestNumber == 1) {
        status = 401;
        body = "{\"message\":\"expired token\"}";
      } else {
        status = rebuildStatus;
        body = rebuildResponse;
      }
    } else {
      status = 404;
      body = "{\"message\":\"not found\"}";
    }

    writeResponse(exchange, status, body);
  }

  private void respondToTokenRequest(HttpExchange exchange) throws IOException {
    int requestNumber = tokenRequestCount.getAndIncrement();
    oauthRequestForms.add(parseForm(exchange));

    String body = tokenResponse;
    if (body == null) {
      String token = issuedTokens.get(Math.min(requestNumber, issuedTokens.size() - 1));
      body = "{\"access_token\":\"" + token + "\",\"token_type\":\"Bearer\",\"expires_in\":300}";
    }
    writeResponse(exchange, tokenStatus, body);
  }

  private static Map<String, String> parseForm(HttpExchange exchange) throws IOException {
    String body = new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
    Map<String, String> form = new LinkedHashMap<>();
    for (String part : body.split("&")) {
      String[] pair = part.split("=", 2);
      String name = URLDecoder.decode(pair[0], StandardCharsets.UTF_8);
      String value = pair.length == 2 ? URLDecoder.decode(pair[1], StandardCharsets.UTF_8) : "";
      form.put(name, value);
    }
    return form;
  }

  private static void writeResponse(HttpExchange exchange, int status, String body)
      throws IOException {
    byte[] response = body.getBytes(StandardCharsets.UTF_8);
    exchange.getResponseHeaders().set("Content-Type", "application/json");
    exchange.sendResponseHeaders(status, response.length);
    try (OutputStream output = exchange.getResponseBody()) {
      output.write(response);
    }
  }

  private String[] oauthArguments() {
    return new String[] {
      "--gravitino_oauth_token_uri=" + tokenUri(),
      "--gravitino_oauth_client_id=" + OAUTH_CLIENT_ID,
      "--gravitino_oauth_client_secret=" + OAUTH_CLIENT_SECRET,
      "--gravitino_oauth_scope=" + OAUTH_SCOPE
    };
  }

  private String[] oauthArgumentsWithoutScope() {
    return new String[] {
      "--gravitino_oauth_token_uri=" + tokenUri(),
      "--gravitino_oauth_client_id=" + OAUTH_CLIENT_ID,
      "--gravitino_oauth_client_secret=" + OAUTH_CLIENT_SECRET
    };
  }

  private String tokenUri() {
    return "http://127.0.0.1:" + server.getAddress().getPort() + "/oauth/token";
  }

  private void stageBinDirectory() throws IOException {
    Path sourceDirectory = Paths.get(System.getProperty("user.dir"), "..", "bin").normalize();
    Path openSearchDirectory = temporaryDirectory.resolve("opensearch");
    curlWrapperDirectory = temporaryDirectory.resolve("test-bin");
    curlArgumentsFile = temporaryDirectory.resolve("curl-arguments.txt");
    Files.createDirectories(openSearchDirectory);
    Files.createDirectories(curlWrapperDirectory);
    Files.copy(
        sourceDirectory.resolve("index.sh.template"),
        temporaryDirectory.resolve("index.sh"),
        StandardCopyOption.REPLACE_EXISTING);
    Files.writeString(openSearchDirectory.resolve("create_indices_template.sh"), "#!/bin/bash\n");
    Files.writeString(openSearchDirectory.resolve("delete_indices_template.sh"), "#!/bin/bash\n");

    Path curlWrapper = curlWrapperDirectory.resolve("curl");
    Files.writeString(
        curlWrapper,
        "#!/bin/bash\n"
            + "{\n"
            + "  printf '%s\\n' '--- curl invocation ---'\n"
            + "  printf '%s\\n' \"$@\"\n"
            + "} >> \"$CURL_ARGUMENTS_FILE\"\n"
            + "exec \"$REAL_CURL\" \"$@\"\n");
    if (!curlWrapper.toFile().setExecutable(true)) {
      throw new IOException("Failed to make the curl test wrapper executable");
    }
  }

  private ScriptResult runScript(Map<String, String> environment, String... arguments)
      throws Exception {
    Files.deleteIfExists(curlArgumentsFile);

    List<String> command = new ArrayList<>();
    command.add("/bin/bash");
    command.add(temporaryDirectory.resolve("index.sh").toString());
    command.add("rebuild");
    command.add("--gravitino_uri=http://127.0.0.1:" + server.getAddress().getPort());
    Collections.addAll(command, arguments);

    ProcessBuilder builder = new ProcessBuilder(command);
    builder.directory(temporaryDirectory.toFile());
    Map<String, String> processEnvironment = builder.environment();
    processEnvironment.remove("GRAVITINO_HOME");
    processEnvironment.remove("GRAVITINO_USERNAME");
    processEnvironment.remove("GRAVITINO_PASSWORD");
    processEnvironment.remove("GRAVITINO_TOKEN");
    processEnvironment.remove("GRAVITINO_OAUTH_TOKEN_URI");
    processEnvironment.remove("GRAVITINO_OAUTH_CLIENT_ID");
    processEnvironment.remove("GRAVITINO_OAUTH_CLIENT_SECRET");
    processEnvironment.remove("GRAVITINO_OAUTH_SCOPE");
    processEnvironment.putAll(environment);
    processEnvironment.put("REAL_CURL", curlExecutable);
    processEnvironment.put("CURL_ARGUMENTS_FILE", curlArgumentsFile.toString());
    processEnvironment.put(
        "PATH",
        curlWrapperDirectory + File.pathSeparator + processEnvironment.getOrDefault("PATH", ""));
    builder.redirectErrorStream(true);

    Process process = builder.start();
    String output = new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
    return new ScriptResult(process.waitFor(), output);
  }

  private void assertCurlArgumentsDoNotContain(String... sensitiveValues) throws IOException {
    String arguments = Files.exists(curlArgumentsFile) ? Files.readString(curlArgumentsFile) : "";
    for (String sensitiveValue : sensitiveValues) {
      assertFalse(
          arguments.contains(sensitiveValue),
          "curl arguments contain a sensitive authentication value");
    }
  }

  private static String findCommand(String command) throws Exception {
    Process process =
        new ProcessBuilder("/bin/sh", "-c", "command -v \"$1\"", "find-command", command)
            .redirectErrorStream(true)
            .start();
    String output =
        new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8).strip();
    return process.waitFor() == 0 ? output : "";
  }

  private static class ScriptResult {
    private final int exitCode;
    private final String output;

    private ScriptResult(int exitCode, String output) {
      this.exitCode = exitCode;
      this.output = output;
    }
  }
}
