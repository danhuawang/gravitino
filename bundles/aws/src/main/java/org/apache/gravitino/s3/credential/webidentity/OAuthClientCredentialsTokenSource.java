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

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import javax.annotation.Nullable;
import org.apache.commons.lang3.StringUtils;
import org.apache.hc.client5.http.classic.methods.HttpPost;
import org.apache.hc.client5.http.config.ConnectionConfig;
import org.apache.hc.client5.http.config.RequestConfig;
import org.apache.hc.client5.http.entity.UrlEncodedFormEntity;
import org.apache.hc.client5.http.impl.classic.CloseableHttpClient;
import org.apache.hc.client5.http.impl.classic.HttpClients;
import org.apache.hc.client5.http.impl.io.PoolingHttpClientConnectionManagerBuilder;
import org.apache.hc.core5.http.HttpEntity;
import org.apache.hc.core5.http.NameValuePair;
import org.apache.hc.core5.http.ParseException;
import org.apache.hc.core5.http.io.entity.EntityUtils;
import org.apache.hc.core5.http.message.BasicNameValuePair;
import org.apache.hc.core5.util.Timeout;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * A {@link WebIdentityTokenSource} that fetches an OIDC token from an OAuth2 token endpoint using
 * the {@code client_credentials} grant. Suitable for non-interactive workloads where Gravitino acts
 * as the OAuth2 client (Keycloak, Azure Entra ID, Okta, Auth0, AWS Cognito, etc.).
 *
 * <p>Tokens are cached in memory and refreshed shortly before expiry; the refresh skew is
 * configurable via {@link #OAUTH_REFRESH_SKEW_SECS}.
 */
public class OAuthClientCredentialsTokenSource implements WebIdentityTokenSource {

  /** The configuration name used to select the OAuth client credentials token source. */
  public static final String NAME = "oauth-client-credentials";

  /** OAuth2 token endpoint. */
  public static final String OAUTH_TOKEN_ENDPOINT = "s3-web-identity-token-endpoint";

  /** OAuth2 client id. */
  public static final String OAUTH_CLIENT_ID = "s3-web-identity-token-client-id";

  /** OAuth2 client secret. */
  public static final String OAUTH_CLIENT_SECRET = "s3-web-identity-token-client-secret";

  /**
   * OAuth2 client authentication method, either {@code client_secret_post} (default) or {@code
   * client_secret_basic}.
   */
  public static final String OAUTH_CLIENT_AUTH_METHOD = "s3-web-identity-token-client-auth-method";

  /** Optional OAuth2 scope. */
  public static final String OAUTH_SCOPE = "s3-web-identity-token-scope";

  /** Optional OAuth2 audience parameter. */
  public static final String OAUTH_AUDIENCE = "s3-web-identity-token-audience";

  /**
   * Field name to read the WebIdentity token from in the OAuth2 token response, defaults to {@code
   * access_token}.
   */
  public static final String OAUTH_RESPONSE_TOKEN_FIELD =
      "s3-web-identity-token-response-token-field";

  /** Token endpoint connect / read timeout in milliseconds, defaults to {@code 10000}. */
  public static final String OAUTH_REQUEST_TIMEOUT_MS =
      "s3-web-identity-token-request-timeout-in-ms";

  /** Refresh the cached token this many seconds before it expires, defaults to {@code 60}. */
  public static final String OAUTH_REFRESH_SKEW_SECS = "s3-web-identity-token-refresh-skew-in-secs";

  /** {@link #OAUTH_CLIENT_AUTH_METHOD} value: send client_id / client_secret in the form body. */
  public static final String CLIENT_SECRET_POST = "client_secret_post";

  /** {@link #OAUTH_CLIENT_AUTH_METHOD} value: send client_id / client_secret as Basic auth. */
  public static final String CLIENT_SECRET_BASIC = "client_secret_basic";

  /** Default value for {@link #OAUTH_RESPONSE_TOKEN_FIELD}. */
  public static final String DEFAULT_RESPONSE_TOKEN_FIELD = "access_token";

  /** Default value for {@link #OAUTH_REQUEST_TIMEOUT_MS}. */
  public static final int DEFAULT_REQUEST_TIMEOUT_MS = 10_000;

  /** Default value for {@link #OAUTH_REFRESH_SKEW_SECS}. */
  public static final int DEFAULT_REFRESH_SKEW_SECS = 60;

  private static final int MAX_ERROR_RESPONSE_BODY_CHARS = 512;
  private static final long REFRESH_FAILURE_WARN_INTERVAL_MS = 60_000L;

  private static final Logger LOG =
      LoggerFactory.getLogger(OAuthClientCredentialsTokenSource.class);
  private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();

  private String tokenEndpoint;
  private String clientId;
  private String clientSecret;
  private String clientAuthMethod;
  @Nullable private String scope;
  @Nullable private String audience;
  private String responseTokenField;
  private int requestTimeoutMs;
  private int refreshSkewSecs;
  private CloseableHttpClient httpClient;

  private volatile CachedToken cached;
  private long nextRefreshFailureWarnAtMs;

  @Override
  public String name() {
    return NAME;
  }

  @Override
  public void initialize(Map<String, String> properties) {
    this.tokenEndpoint = required(properties, OAUTH_TOKEN_ENDPOINT);
    this.clientId = required(properties, OAUTH_CLIENT_ID);
    this.clientSecret = required(properties, OAUTH_CLIENT_SECRET);
    this.clientAuthMethod =
        StringUtils.defaultIfBlank(properties.get(OAUTH_CLIENT_AUTH_METHOD), CLIENT_SECRET_POST);
    if (!CLIENT_SECRET_POST.equals(clientAuthMethod)
        && !CLIENT_SECRET_BASIC.equals(clientAuthMethod)) {
      throw new IllegalArgumentException(
          OAUTH_CLIENT_AUTH_METHOD
              + " must be either '"
              + CLIENT_SECRET_POST
              + "' or '"
              + CLIENT_SECRET_BASIC
              + "', got: "
              + clientAuthMethod);
    }
    // RFC 7617 splits the Basic userid:password on the first colon, so a colon in the client id
    // would corrupt the credentials. Reject it explicitly when Basic auth is used.
    if (CLIENT_SECRET_BASIC.equals(clientAuthMethod) && clientId.contains(":")) {
      throw new IllegalArgumentException(
          OAUTH_CLIENT_ID
              + " must not contain a colon when using '"
              + CLIENT_SECRET_BASIC
              + "' authentication, as it corrupts the HTTP Basic credentials (RFC 7617).");
    }
    this.scope = properties.get(OAUTH_SCOPE);
    this.audience = properties.get(OAUTH_AUDIENCE);
    this.responseTokenField =
        StringUtils.defaultIfBlank(
            properties.get(OAUTH_RESPONSE_TOKEN_FIELD), DEFAULT_RESPONSE_TOKEN_FIELD);
    this.requestTimeoutMs =
        parsePositiveInt(
            properties.get(OAUTH_REQUEST_TIMEOUT_MS),
            DEFAULT_REQUEST_TIMEOUT_MS,
            OAUTH_REQUEST_TIMEOUT_MS);
    this.refreshSkewSecs =
        parsePositiveInt(
            properties.get(OAUTH_REFRESH_SKEW_SECS),
            DEFAULT_REFRESH_SKEW_SECS,
            OAUTH_REFRESH_SKEW_SECS);
    this.httpClient = createHttpClient();
  }

  @Override
  public String getToken() {
    long nowMs = System.currentTimeMillis();
    CachedToken local = cached;
    if (local != null && local.refreshAfterMs > nowMs) {
      return local.token;
    }
    synchronized (this) {
      nowMs = System.currentTimeMillis();
      if (cached != null && cached.refreshAfterMs > nowMs) {
        return cached.token;
      }
      // The cached token is past its refresh-skew window. Try to refetch; if the IdP is
      // temporarily unavailable and the cached token has not yet truly expired, keep using
      // it instead of failing — this avoids outages on transient IdP / network issues.
      try {
        CachedToken fetched = fetchToken(nowMs);
        cached = fetched;
        nextRefreshFailureWarnAtMs = 0;
        return fetched.token;
      } catch (RuntimeException refreshFailure) {
        long failureTimeMs = System.currentTimeMillis();
        CachedToken reusableToken = cached;
        if (reusableToken != null && reusableToken.expiresAtMs > failureTimeMs) {
          logRefreshFailure(reusableToken, failureTimeMs, refreshFailure);
          return reusableToken.token;
        }
        throw refreshFailure;
      }
    }
  }

  @Override
  public void close() throws IOException {
    if (httpClient != null) {
      httpClient.close();
    }
  }

  CachedToken fetchToken(long nowMs) {
    List<NameValuePair> formParams = new ArrayList<>();
    formParams.add(new BasicNameValuePair("grant_type", "client_credentials"));
    if (CLIENT_SECRET_POST.equals(clientAuthMethod)) {
      formParams.add(new BasicNameValuePair("client_id", clientId));
      formParams.add(new BasicNameValuePair("client_secret", clientSecret));
    }
    if (StringUtils.isNotBlank(scope)) {
      formParams.add(new BasicNameValuePair("scope", scope));
    }
    if (StringUtils.isNotBlank(audience)) {
      formParams.add(new BasicNameValuePair("audience", audience));
    }

    HttpPost request = new HttpPost(tokenEndpoint);
    request.setHeader("Accept", "application/json");
    if (CLIENT_SECRET_BASIC.equals(clientAuthMethod)) {
      request.setHeader("Authorization", basicAuthorizationHeader());
    }
    // UrlEncodedFormEntity encodes the body and sets the application/x-www-form-urlencoded header.
    request.setEntity(new UrlEncodedFormEntity(formParams, StandardCharsets.UTF_8));

    TokenEndpointResponse response;
    try {
      response =
          httpClient.execute(
              request,
              httpResponse -> {
                String body =
                    httpResponse.getEntity() == null
                        ? ""
                        : entityToString(httpResponse.getEntity());
                return new TokenEndpointResponse(httpResponse.getCode(), body);
              });
    } catch (IOException e) {
      throw new RuntimeException("Failed to fetch OAuth token from " + tokenEndpoint, e);
    }

    if (response.statusCode < 200 || response.statusCode >= 300) {
      throw new IllegalStateException(
          "Failed to fetch OAuth token. HTTP status: "
              + response.statusCode
              + errorResponseBodyMessage(response.body));
    }

    JsonNode root;
    try {
      root = OBJECT_MAPPER.readTree(response.body);
    } catch (IOException e) {
      throw new RuntimeException("Failed to parse OAuth token response JSON", e);
    }

    JsonNode tokenNode = root.get(responseTokenField);
    if (tokenNode == null || tokenNode.isNull() || StringUtils.isBlank(tokenNode.asText())) {
      throw new IllegalStateException(
          "OAuth token response did not contain a non-empty '" + responseTokenField + "' field.");
    }
    String token = tokenNode.asText();

    long expiresInSecs = root.path("expires_in").asLong(refreshSkewSecs * 2L);
    long expiresAtMs = nowMs + expiresInSecs * 1000L;
    long refreshAfterMs = nowMs + Math.max(0, expiresInSecs - refreshSkewSecs) * 1000L;
    return new CachedToken(token, refreshAfterMs, expiresAtMs);
  }

  CloseableHttpClient createHttpClient() {
    Timeout timeout = Timeout.ofMilliseconds(requestTimeoutMs);
    RequestConfig requestConfig =
        RequestConfig.custom()
            .setConnectionRequestTimeout(timeout)
            .setResponseTimeout(timeout)
            .build();
    ConnectionConfig connectionConfig =
        ConnectionConfig.custom().setConnectTimeout(timeout).setSocketTimeout(timeout).build();
    return HttpClients.custom()
        .setConnectionManager(
            PoolingHttpClientConnectionManagerBuilder.create()
                .setDefaultConnectionConfig(connectionConfig)
                .build())
        .setDefaultRequestConfig(requestConfig)
        .disableAutomaticRetries()
        .disableRedirectHandling()
        .build();
  }

  private String basicAuthorizationHeader() {
    String raw = clientId + ":" + clientSecret;
    return "Basic " + Base64.getEncoder().encodeToString(raw.getBytes(StandardCharsets.UTF_8));
  }

  private void logRefreshFailure(
      CachedToken reusableToken, long failureTimeMs, RuntimeException refreshFailure) {
    long expiresInMs = reusableToken.expiresAtMs - failureTimeMs;
    if (failureTimeMs >= nextRefreshFailureWarnAtMs) {
      nextRefreshFailureWarnAtMs = failureTimeMs + REFRESH_FAILURE_WARN_INTERVAL_MS;
      LOG.warn(
          "Failed to refresh OAuth token; reusing cached token (expires in {} ms): {}",
          expiresInMs,
          refreshFailure.toString());
      return;
    }

    LOG.debug(
        "Failed to refresh OAuth token; reusing cached token (expires in {} ms): {}",
        expiresInMs,
        refreshFailure.toString());
  }

  private static String entityToString(HttpEntity entity) throws IOException {
    try {
      return EntityUtils.toString(entity, StandardCharsets.UTF_8);
    } catch (ParseException e) {
      throw new IOException("Failed to parse OAuth token response body", e);
    }
  }

  private static String errorResponseBodyMessage(String responseBody) {
    if (StringUtils.isBlank(responseBody)) {
      return "";
    }

    String normalized = StringUtils.normalizeSpace(responseBody);
    return ", response body: " + StringUtils.abbreviate(normalized, MAX_ERROR_RESPONSE_BODY_CHARS);
  }

  private static String required(Map<String, String> properties, String key) {
    String value = properties.get(key);
    if (StringUtils.isBlank(value)) {
      throw new IllegalArgumentException("Required property '" + key + "' is missing or blank.");
    }
    return value;
  }

  private static int parsePositiveInt(String value, int defaultValue, String key) {
    if (StringUtils.isBlank(value)) {
      return defaultValue;
    }
    int parsed;
    try {
      parsed = Integer.parseInt(value.trim());
    } catch (NumberFormatException e) {
      throw new IllegalArgumentException("Property '" + key + "' must be an integer: " + value, e);
    }
    if (parsed <= 0) {
      throw new IllegalArgumentException("Property '" + key + "' must be positive: " + value);
    }
    return parsed;
  }

  private static final class TokenEndpointResponse {
    private final int statusCode;
    private final String body;

    private TokenEndpointResponse(int statusCode, String body) {
      this.statusCode = statusCode;
      this.body = body;
    }
  }

  /**
   * Cached token with two timestamps (epoch ms):
   *
   * <ul>
   *   <li>{@code refreshAfterMs} — when to start refreshing proactively (set to expiry minus the
   *       refresh skew). Once exceeded, callers attempt a refetch.
   *   <li>{@code expiresAtMs} — the real expiry. When refetch fails but {@code expiresAtMs > now},
   *       the cached token is still usable and we fall back to it instead of failing.
   * </ul>
   */
  static final class CachedToken {
    final String token;
    final long refreshAfterMs;
    final long expiresAtMs;

    CachedToken(String token, long refreshAfterMs, long expiresAtMs) {
      this.token = token;
      this.refreshAfterMs = refreshAfterMs;
      this.expiresAtMs = expiresAtMs;
    }
  }
}
