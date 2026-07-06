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
package org.apache.gravitino.s3.credential.webidentity.integration.test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Base64;
import java.util.HashMap;
import java.util.Map;
import org.apache.gravitino.s3.credential.webidentity.OAuthClientCredentialsTokenSource;
import org.apache.gravitino.s3.credential.webidentity.WebIdentityTokenSource;
import org.apache.gravitino.s3.credential.webidentity.WebIdentityTokenSourceConfig;
import org.apache.gravitino.s3.credential.webidentity.WebIdentityTokenSources;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.wait.strategy.Wait;
import org.testcontainers.utility.MountableFile;

/**
 * Integration test for {@link OAuthClientCredentialsTokenSource} against a real Keycloak server.
 *
 * <p>Spins up Keycloak via Testcontainers, imports a realm that has a {@code gravitino-s3}
 * confidential client with service accounts enabled, and exercises the source against the realm's
 * token endpoint.
 */
@Tag("gravitino-docker-test")
class OAuthClientCredentialsTokenSourceKeycloakIT {

  private static final String KEYCLOAK_IMAGE = "quay.io/keycloak/keycloak:23.0";
  private static final String REALM = "gravitino";
  private static final String CLIENT_ID = "gravitino-s3";
  private static final String CLIENT_SECRET = "gravitino-s3-secret";

  private static GenericContainer<?> keycloak;
  private static String tokenEndpoint;

  @BeforeAll
  static void startKeycloak() {
    keycloak =
        new GenericContainer<>(KEYCLOAK_IMAGE)
            .withCommand("start-dev", "--import-realm")
            .withEnv("KEYCLOAK_ADMIN", "admin")
            .withEnv("KEYCLOAK_ADMIN_PASSWORD", "admin")
            .withCopyFileToContainer(
                MountableFile.forClasspathResource("keycloak/gravitino-oauth-realm.json"),
                "/opt/keycloak/data/import/gravitino-oauth-realm.json")
            .withExposedPorts(8080)
            .waitingFor(
                Wait.forHttp("/realms/" + REALM + "/.well-known/openid-configuration")
                    .forStatusCode(200)
                    .withStartupTimeout(Duration.ofMinutes(3)));
    keycloak.start();

    tokenEndpoint =
        "http://"
            + keycloak.getHost()
            + ":"
            + keycloak.getMappedPort(8080)
            + "/realms/"
            + REALM
            + "/protocol/openid-connect/token";
  }

  @AfterAll
  static void stopKeycloak() {
    if (keycloak != null) {
      keycloak.stop();
    }
  }

  @Test
  void fetchesAccessTokenWithClientSecretPost() {
    OAuthClientCredentialsTokenSource source = new OAuthClientCredentialsTokenSource();
    source.initialize(propsForPost());

    String token = source.getToken();
    assertJwtShape(token);
    assertJwtIssuedToClient(token, CLIENT_ID);
  }

  @Test
  void fetchesAccessTokenWithClientSecretBasic() {
    Map<String, String> props = propsForPost();
    props.put(
        OAuthClientCredentialsTokenSource.OAUTH_CLIENT_AUTH_METHOD,
        OAuthClientCredentialsTokenSource.CLIENT_SECRET_BASIC);

    OAuthClientCredentialsTokenSource source = new OAuthClientCredentialsTokenSource();
    source.initialize(props);

    String token = source.getToken();
    assertJwtShape(token);
    assertJwtIssuedToClient(token, CLIENT_ID);
  }

  @Test
  void cachesTokenAcrossInvocations() {
    OAuthClientCredentialsTokenSource source = new OAuthClientCredentialsTokenSource();
    source.initialize(propsForPost());

    String first = source.getToken();
    String second = source.getToken();
    assertEquals(first, second, "second call within validity window should reuse cached token");
  }

  @Test
  void factoryProducesWorkingOAuthSource() {
    Map<String, String> props = propsForPost();
    props.put(WebIdentityTokenSourceConfig.SOURCE, "oauth-client-credentials");

    WebIdentityTokenSource source = WebIdentityTokenSources.create(props);
    assertEquals("oauth-client-credentials", source.name());
    assertJwtShape(source.getToken());
  }

  private Map<String, String> propsForPost() {
    Map<String, String> props = new HashMap<>();
    props.put(OAuthClientCredentialsTokenSource.OAUTH_TOKEN_ENDPOINT, tokenEndpoint);
    props.put(OAuthClientCredentialsTokenSource.OAUTH_CLIENT_ID, CLIENT_ID);
    props.put(OAuthClientCredentialsTokenSource.OAUTH_CLIENT_SECRET, CLIENT_SECRET);
    return props;
  }

  private static void assertJwtShape(String token) {
    assertNotNull(token);
    String[] parts = token.split("\\.");
    assertEquals(3, parts.length, "JWT must have header.payload.signature; got: " + token);
  }

  private static void assertJwtIssuedToClient(String token, String expectedClientId) {
    String payload = token.split("\\.")[1];
    byte[] decoded = Base64.getUrlDecoder().decode(padBase64(payload));
    String json = new String(decoded, StandardCharsets.UTF_8);
    // Keycloak service-account tokens carry the client id in either `azp` or `clientId` claims.
    assertTrue(
        json.contains("\"" + expectedClientId + "\""),
        "JWT payload should reference client id '" + expectedClientId + "': " + json);
  }

  private static String padBase64(String value) {
    int pad = (4 - value.length() % 4) % 4;
    StringBuilder sb = new StringBuilder(value);
    for (int i = 0; i < pad; i++) {
      sb.append('=');
    }
    return sb.toString();
  }
}
