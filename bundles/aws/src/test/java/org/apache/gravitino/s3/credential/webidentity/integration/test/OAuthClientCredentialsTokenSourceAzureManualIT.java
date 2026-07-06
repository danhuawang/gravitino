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
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import java.util.HashMap;
import java.util.Map;
import org.apache.commons.lang3.StringUtils;
import org.apache.gravitino.s3.credential.webidentity.OAuthClientCredentialsTokenSource;
import org.apache.gravitino.s3.credential.webidentity.WebIdentityTokenSource;
import org.apache.gravitino.s3.credential.webidentity.WebIdentityTokenSourceConfig;
import org.apache.gravitino.s3.credential.webidentity.WebIdentityTokenSources;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

/**
 * Manual integration test for {@link OAuthClientCredentialsTokenSource} against Azure Entra ID.
 *
 * <p>This test exists to validate the source against a real cloud OIDC provider. It is not part of
 * the regular CI run; the test methods are skipped unless the required Azure environment variables
 * are set:
 *
 * <ul>
 *   <li>{@code GRAVITINO_AZURE_TENANT_ID}
 *   <li>{@code GRAVITINO_AZURE_CLIENT_ID}
 *   <li>{@code GRAVITINO_AZURE_CLIENT_SECRET}
 *   <li>{@code GRAVITINO_AZURE_SCOPE} — e.g. {@code api://<app-id-uri>/.default}
 * </ul>
 *
 * <p>To run manually:
 *
 * <pre>
 *   GRAVITINO_AZURE_TENANT_ID=... \
 *   GRAVITINO_AZURE_CLIENT_ID=... \
 *   GRAVITINO_AZURE_CLIENT_SECRET=... \
 *   GRAVITINO_AZURE_SCOPE=api://&lt;app-id-uri&gt;/.default \
 *   ./gradlew :bundles:aws:test \
 *     --tests org.apache.gravitino.s3.credential.webidentity.integration.test.OAuthClientCredentialsTokenSourceAzureManualIT
 * </pre>
 */
class OAuthClientCredentialsTokenSourceAzureManualIT {

  private static final String ENV_TENANT_ID = "GRAVITINO_AZURE_TENANT_ID";
  private static final String ENV_CLIENT_ID = "GRAVITINO_AZURE_CLIENT_ID";
  private static final String ENV_CLIENT_SECRET = "GRAVITINO_AZURE_CLIENT_SECRET";
  private static final String ENV_SCOPE = "GRAVITINO_AZURE_SCOPE";

  private static String tenantId;
  private static String clientId;
  private static String clientSecret;
  private static String scope;

  @BeforeAll
  static void loadEnv() {
    tenantId = System.getenv(ENV_TENANT_ID);
    clientId = System.getenv(ENV_CLIENT_ID);
    clientSecret = System.getenv(ENV_CLIENT_SECRET);
    scope = System.getenv(ENV_SCOPE);
    assumeTrue(
        StringUtils.isNoneBlank(tenantId, clientId, clientSecret, scope),
        "Azure manual IT requires "
            + ENV_TENANT_ID
            + ", "
            + ENV_CLIENT_ID
            + ", "
            + ENV_CLIENT_SECRET
            + ", and "
            + ENV_SCOPE
            + " to be set.");
  }

  @Test
  void fetchesAccessTokenFromAzureEntraId() {
    OAuthClientCredentialsTokenSource source = new OAuthClientCredentialsTokenSource();
    source.initialize(buildProps());

    String token = source.getToken();
    assertNotNull(token);
    String[] parts = token.split("\\.");
    assertEquals(3, parts.length, "Azure access token must be a JWT");
  }

  @Test
  void factoryProducesWorkingOAuthSourceForAzure() {
    Map<String, String> props = buildProps();
    props.put(WebIdentityTokenSourceConfig.SOURCE, "oauth-client-credentials");

    WebIdentityTokenSource source = WebIdentityTokenSources.create(props);
    String token = source.getToken();
    assertNotNull(token);
    String[] parts = token.split("\\.");
    assertEquals(3, parts.length, "Azure access token must be a JWT");
  }

  private Map<String, String> buildProps() {
    Map<String, String> props = new HashMap<>();
    props.put(
        OAuthClientCredentialsTokenSource.OAUTH_TOKEN_ENDPOINT,
        "https://login.microsoftonline.com/" + tenantId + "/oauth2/v2.0/token");
    props.put(OAuthClientCredentialsTokenSource.OAUTH_CLIENT_ID, clientId);
    props.put(OAuthClientCredentialsTokenSource.OAUTH_CLIENT_SECRET, clientSecret);
    props.put(OAuthClientCredentialsTokenSource.OAUTH_SCOPE, scope);
    return props;
  }
}
