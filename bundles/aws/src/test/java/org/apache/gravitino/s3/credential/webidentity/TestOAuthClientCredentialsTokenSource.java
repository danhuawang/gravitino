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
package org.apache.gravitino.s3.credential.webidentity;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.InetSocketAddress;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Base64;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class TestOAuthClientCredentialsTokenSource {

  private HttpServer server;
  private String tokenEndpoint;
  private AtomicInteger requestCount;
  private final List<RecordedRequest> recordedRequests = new ArrayList<>();
  private final List<WebIdentityTokenSource> sources = new ArrayList<>();

  @BeforeEach
  void startServer() throws IOException {
    requestCount = new AtomicInteger();
    recordedRequests.clear();
    server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
    server.start();
    tokenEndpoint = "http://127.0.0.1:" + server.getAddress().getPort() + "/token";
  }

  @AfterEach
  void tearDown() throws IOException {
    try {
      for (WebIdentityTokenSource source : sources) {
        source.close();
      }
    } finally {
      sources.clear();
      if (server != null) {
        server.stop(0);
      }
    }
  }

  @Test
  void nameIsOAuthClientCredentials() {
    assertEquals("oauth-client-credentials", new OAuthClientCredentialsTokenSource().name());
  }

  @Test
  void fetchesTokenWithClientSecretPostByDefault() {
    serveSingleTokenResponse("tok-123", 3600);

    OAuthClientCredentialsTokenSource source = newSource(baseProps());
    assertEquals("tok-123", source.getToken());

    assertEquals(1, requestCount.get());
    RecordedRequest req = recordedRequests.get(0);
    assertTrue(
        req.contentType != null && req.contentType.startsWith("application/x-www-form-urlencoded"),
        "unexpected Content-Type: " + req.contentType);
    Map<String, String> form = parseForm(req.body);
    assertEquals("client_credentials", form.get("grant_type"));
    assertEquals("my-client", form.get("client_id"));
    assertEquals("my-secret", form.get("client_secret"));
    assertTrue(req.authorization == null, "should not send Authorization header in post mode");
  }

  @Test
  void usesBasicAuthWhenConfigured() {
    serveSingleTokenResponse("tok-basic", 3600);

    Map<String, String> props = baseProps();
    props.put(
        OAuthClientCredentialsTokenSource.OAUTH_CLIENT_AUTH_METHOD,
        OAuthClientCredentialsTokenSource.CLIENT_SECRET_BASIC);

    OAuthClientCredentialsTokenSource source = newSource(props);
    assertEquals("tok-basic", source.getToken());

    RecordedRequest req = recordedRequests.get(0);
    String expected =
        "Basic "
            + Base64.getEncoder()
                .encodeToString("my-client:my-secret".getBytes(StandardCharsets.UTF_8));
    assertEquals(expected, req.authorization);
    Map<String, String> form = parseForm(req.body);
    assertTrue(
        !form.containsKey("client_id") && !form.containsKey("client_secret"),
        "must not send client credentials in body when using basic auth");
  }

  @Test
  void includesOptionalScopeAndAudience() {
    serveSingleTokenResponse("tok-scope", 3600);

    Map<String, String> props = baseProps();
    props.put(OAuthClientCredentialsTokenSource.OAUTH_SCOPE, "s3:read s3:write");
    props.put(OAuthClientCredentialsTokenSource.OAUTH_AUDIENCE, "sts.amazonaws.com");

    OAuthClientCredentialsTokenSource source = newSource(props);
    source.getToken();

    Map<String, String> form = parseForm(recordedRequests.get(0).body);
    assertEquals("s3:read s3:write", form.get("scope"));
    assertEquals("sts.amazonaws.com", form.get("audience"));
  }

  @Test
  void readsCustomResponseTokenField() {
    server.createContext(
        "/token",
        exchange -> {
          recordRequest(exchange);
          byte[] body =
              "{\"id_token\":\"id-tok\",\"expires_in\":3600}".getBytes(StandardCharsets.UTF_8);
          exchange.getResponseHeaders().set("Content-Type", "application/json");
          exchange.sendResponseHeaders(200, body.length);
          exchange.getResponseBody().write(body);
          exchange.close();
        });

    Map<String, String> props = baseProps();
    props.put(OAuthClientCredentialsTokenSource.OAUTH_RESPONSE_TOKEN_FIELD, "id_token");

    OAuthClientCredentialsTokenSource source = newSource(props);
    assertEquals("id-tok", source.getToken());
  }

  @Test
  void cachesTokenUntilRefreshSkew() {
    serveSingleTokenResponse("tok-cache", 3600);

    OAuthClientCredentialsTokenSource source = newSource(baseProps());
    assertEquals("tok-cache", source.getToken());
    // Second call within validity window should not hit the server.
    assertEquals("tok-cache", source.getToken());
    assertEquals("tok-cache", source.getToken());
    assertEquals(1, requestCount.get());
  }

  @Test
  void refetchesWhenTokenExpiresWithinSkew() {
    server.createContext(
        "/token",
        exchange -> {
          recordRequest(exchange);
          int n = requestCount.incrementAndGet();
          String token = "tok-" + n;
          // expires_in is shorter than the refresh skew, so the cached token is
          // always considered stale and the next getToken call triggers a refetch.
          byte[] body =
              ("{\"access_token\":\"" + token + "\",\"expires_in\":1}")
                  .getBytes(StandardCharsets.UTF_8);
          exchange.getResponseHeaders().set("Content-Type", "application/json");
          exchange.sendResponseHeaders(200, body.length);
          exchange.getResponseBody().write(body);
          exchange.close();
        });

    Map<String, String> props = baseProps();
    props.put(OAuthClientCredentialsTokenSource.OAUTH_REFRESH_SKEW_SECS, "60");

    OAuthClientCredentialsTokenSource source = newSource(props);
    assertEquals("tok-1", source.getToken());
    assertEquals("tok-2", source.getToken());
    assertEquals("tok-3", source.getToken());
  }

  @Test
  void reusesCachedTokenWhenRefreshFailsButTokenStillValid() {
    server.createContext(
        "/token",
        exchange -> {
          recordRequest(exchange);
          int n = requestCount.incrementAndGet();
          if (n == 1) {
            // First call: return a token whose refresh-skew window is already exceeded
            // (expires_in = refresh_skew), but the real expiry is still ~60s away.
            byte[] body =
                "{\"access_token\":\"original-token\",\"expires_in\":60}"
                    .getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().set("Content-Type", "application/json");
            exchange.sendResponseHeaders(200, body.length);
            exchange.getResponseBody().write(body);
          } else {
            // Subsequent calls: simulate an IdP outage.
            byte[] body = "{\"error\":\"server_unavailable\"}".getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(503, body.length);
            exchange.getResponseBody().write(body);
          }
          exchange.close();
        });

    Map<String, String> props = baseProps();
    // refresh_skew == expires_in -> refreshAfterMs == nowMs, so every subsequent call enters
    // the refetch branch. expiresAtMs is still ~60s in the future so the cached token is reused.
    props.put(OAuthClientCredentialsTokenSource.OAUTH_REFRESH_SKEW_SECS, "60");

    OAuthClientCredentialsTokenSource source = newSource(props);
    assertEquals("original-token", source.getToken());
    assertEquals("original-token", source.getToken(), "should fall back to cached token");
    assertEquals("original-token", source.getToken(), "should fall back to cached token again");
    assertEquals(3, requestCount.get(), "each call should attempt a refresh");
  }

  @Test
  void throwsWhenRefreshFailsAfterCachedTokenExpires() throws InterruptedException {
    server.createContext(
        "/token",
        exchange -> {
          recordRequest(exchange);
          int n = requestCount.incrementAndGet();
          if (n == 1) {
            byte[] body =
                "{\"access_token\":\"short-token\",\"expires_in\":1}"
                    .getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().set("Content-Type", "application/json");
            exchange.sendResponseHeaders(200, body.length);
            exchange.getResponseBody().write(body);
          } else {
            byte[] body = "{\"error\":\"server_unavailable\"}".getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(503, body.length);
            exchange.getResponseBody().write(body);
          }
          exchange.close();
        });

    Map<String, String> props = baseProps();
    // refresh_skew == expires_in, so the token is stale immediately; once the 1s lifetime
    // elapses the cached token is also truly expired, so a failed refresh must propagate
    // instead of falling back to the expired token.
    props.put(OAuthClientCredentialsTokenSource.OAUTH_REFRESH_SKEW_SECS, "1");

    OAuthClientCredentialsTokenSource source = newSource(props);
    assertEquals("short-token", source.getToken());

    // Wait past the cached token's real expiry (expires_in = 1s).
    Thread.sleep(1200);

    IllegalStateException error = assertThrows(IllegalStateException.class, source::getToken);
    assertTrue(error.getMessage().contains("503"));
    assertEquals(2, requestCount.get());
  }

  @Test
  void throwsOnNon2xx() {
    String redactedTail = "secret-tail";
    server.createContext(
        "/token",
        exchange -> {
          recordRequest(exchange);
          byte[] body =
              ("{\"error\":\"invalid_client\",\n\"detail\":\""
                      + repeat('x', 700)
                      + redactedTail
                      + "\"}")
                  .getBytes(StandardCharsets.UTF_8);
          exchange.sendResponseHeaders(401, body.length);
          exchange.getResponseBody().write(body);
          exchange.close();
        });

    OAuthClientCredentialsTokenSource source = newSource(baseProps());
    IllegalStateException error = assertThrows(IllegalStateException.class, source::getToken);
    assertTrue(error.getMessage().contains("401"));
    assertTrue(error.getMessage().contains("invalid_client"));
    assertFalse(error.getMessage().contains("\n"));
    assertFalse(error.getMessage().contains(redactedTail));
    assertTrue(error.getMessage().contains("..."));
  }

  @Test
  void throwsWhenResponseMissingTokenField() {
    server.createContext(
        "/token",
        exchange -> {
          recordRequest(exchange);
          byte[] body = "{\"expires_in\":3600}".getBytes(StandardCharsets.UTF_8);
          exchange.getResponseHeaders().set("Content-Type", "application/json");
          exchange.sendResponseHeaders(200, body.length);
          exchange.getResponseBody().write(body);
          exchange.close();
        });

    OAuthClientCredentialsTokenSource source = newSource(baseProps());
    IllegalStateException error = assertThrows(IllegalStateException.class, source::getToken);
    assertTrue(error.getMessage().contains("access_token"));
  }

  @Test
  void initializeFailsOnMissingRequiredProperties() {
    Map<String, String> props = new HashMap<>();
    OAuthClientCredentialsTokenSource source = new OAuthClientCredentialsTokenSource();
    IllegalArgumentException error =
        assertThrows(IllegalArgumentException.class, () -> source.initialize(props));
    assertTrue(error.getMessage().contains(OAuthClientCredentialsTokenSource.OAUTH_TOKEN_ENDPOINT));
  }

  @Test
  void initializeFailsOnUnknownAuthMethod() {
    Map<String, String> props = baseProps();
    props.put(OAuthClientCredentialsTokenSource.OAUTH_CLIENT_AUTH_METHOD, "weird");

    OAuthClientCredentialsTokenSource source = new OAuthClientCredentialsTokenSource();
    IllegalArgumentException error =
        assertThrows(IllegalArgumentException.class, () -> source.initialize(props));
    assertTrue(error.getMessage().contains("client_secret_post"));
    assertTrue(error.getMessage().contains("weird"));
  }

  @Test
  void initializeRejectsColonInClientIdWithBasicAuth() {
    Map<String, String> props = baseProps();
    props.put(
        OAuthClientCredentialsTokenSource.OAUTH_CLIENT_AUTH_METHOD,
        OAuthClientCredentialsTokenSource.CLIENT_SECRET_BASIC);
    props.put(OAuthClientCredentialsTokenSource.OAUTH_CLIENT_ID, "realm:client");

    OAuthClientCredentialsTokenSource source = new OAuthClientCredentialsTokenSource();
    IllegalArgumentException error =
        assertThrows(IllegalArgumentException.class, () -> source.initialize(props));
    assertTrue(error.getMessage().contains(OAuthClientCredentialsTokenSource.OAUTH_CLIENT_ID));
    assertTrue(error.getMessage().contains("colon"));
  }

  @Test
  void allowsColonInClientIdWithPostAuth() {
    Map<String, String> props = baseProps();
    props.put(OAuthClientCredentialsTokenSource.OAUTH_CLIENT_ID, "realm:client");

    // client_secret_post sends the id in the form body, so a colon is harmless there.
    OAuthClientCredentialsTokenSource source = newSource(props);
    assertEquals("oauth-client-credentials", source.name());
  }

  @Test
  void factoryResolvesOAuthSourceByName() {
    serveSingleTokenResponse("from-factory", 3600);

    Map<String, String> props = baseProps();
    props.put(WebIdentityTokenSourceConfig.SOURCE, "oauth-client-credentials");

    WebIdentityTokenSource source = WebIdentityTokenSources.create(props);
    sources.add(source);
    assertNotNull(source);
    assertEquals("oauth-client-credentials", source.name());
    assertEquals("from-factory", source.getToken());
  }

  private OAuthClientCredentialsTokenSource newSource(Map<String, String> props) {
    OAuthClientCredentialsTokenSource source = new OAuthClientCredentialsTokenSource();
    source.initialize(props);
    sources.add(source);
    return source;
  }

  private Map<String, String> baseProps() {
    Map<String, String> props = new HashMap<>();
    props.put(OAuthClientCredentialsTokenSource.OAUTH_TOKEN_ENDPOINT, tokenEndpoint);
    props.put(OAuthClientCredentialsTokenSource.OAUTH_CLIENT_ID, "my-client");
    props.put(OAuthClientCredentialsTokenSource.OAUTH_CLIENT_SECRET, "my-secret");
    return props;
  }

  private void serveSingleTokenResponse(String token, int expiresIn) {
    server.createContext(
        "/token",
        exchange -> {
          recordRequest(exchange);
          requestCount.incrementAndGet();
          byte[] body =
              ("{\"access_token\":\"" + token + "\",\"expires_in\":" + expiresIn + "}")
                  .getBytes(StandardCharsets.UTF_8);
          exchange.getResponseHeaders().set("Content-Type", "application/json");
          exchange.sendResponseHeaders(200, body.length);
          exchange.getResponseBody().write(body);
          exchange.close();
        });
  }

  private void recordRequest(HttpExchange exchange) throws IOException {
    RecordedRequest req = new RecordedRequest();
    req.method = exchange.getRequestMethod();
    req.contentType = exchange.getRequestHeaders().getFirst("Content-Type");
    req.authorization = exchange.getRequestHeaders().getFirst("Authorization");
    try (InputStream in = exchange.getRequestBody();
        ByteArrayOutputStream out = new ByteArrayOutputStream()) {
      byte[] buf = new byte[1024];
      int n;
      while ((n = in.read(buf)) > 0) {
        out.write(buf, 0, n);
      }
      req.body = out.toString(StandardCharsets.UTF_8.name());
    }
    recordedRequests.add(req);
  }

  private static Map<String, String> parseForm(String body) {
    Map<String, String> result = new HashMap<>();
    if (body == null || body.isEmpty()) {
      return result;
    }
    for (String pair : body.split("&")) {
      int idx = pair.indexOf('=');
      if (idx < 0) {
        continue;
      }
      String key = urlDecode(pair.substring(0, idx));
      String value = urlDecode(pair.substring(idx + 1));
      result.put(key, value);
    }
    return result;
  }

  private static String urlDecode(String value) {
    return URLDecoder.decode(value, StandardCharsets.UTF_8);
  }

  private static String repeat(char value, int count) {
    StringBuilder builder = new StringBuilder(count);
    for (int i = 0; i < count; i++) {
      builder.append(value);
    }
    return builder.toString();
  }

  private static final class RecordedRequest {
    String method;
    String contentType;
    String authorization;
    String body;
  }
}
