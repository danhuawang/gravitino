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

import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.google.common.collect.ImmutableSet;
import java.time.Duration;
import java.util.HashMap;
import java.util.Map;
import org.apache.gravitino.credential.AwsIrsaCredential;
import org.apache.gravitino.credential.PathBasedCredentialContext;
import org.apache.gravitino.s3.credential.AwsIrsaCredentialGenerator;
import org.apache.gravitino.s3.credential.webidentity.OAuthClientCredentialsTokenSource;
import org.apache.gravitino.s3.credential.webidentity.WebIdentityTokenSourceConfig;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.localstack.LocalStackContainer;
import org.testcontainers.containers.wait.strategy.Wait;
import org.testcontainers.utility.DockerImageName;
import org.testcontainers.utility.MountableFile;

/**
 * End-to-end integration test exercising the full credential vending flow:
 *
 * <ol>
 *   <li>{@link OAuthClientCredentialsTokenSource} fetches an OIDC token from Keycloak using
 *       client_credentials.
 *   <li>{@link AwsIrsaCredentialGenerator} calls STS {@code AssumeRoleWithWebIdentity} on
 *       LocalStack with a scoped session policy.
 *   <li>The generator returns an {@link AwsIrsaCredential} containing access key id, secret access
 *       key, session token, and expiration.
 * </ol>
 *
 * <p>This is the only test that verifies the wiring across SPI source → STS → final S3 credential.
 */
@Tag("gravitino-docker-test")
class OAuthSTSEndToEndIT {

  private static final String KEYCLOAK_IMAGE = "quay.io/keycloak/keycloak:23.0";
  private static final String LOCALSTACK_IMAGE = "localstack/localstack:3.5";
  private static final String REALM = "gravitino";
  private static final String CLIENT_ID = "gravitino-s3";
  private static final String CLIENT_SECRET = "gravitino-s3-secret";
  private static final String ROLE_ARN = "arn:aws:iam::000000000000:role/gravitino-s3-role";
  private static final String AWS_REGION = "us-east-1";

  private static GenericContainer<?> keycloak;
  private static LocalStackContainer localstack;
  private static String tokenEndpoint;
  private static String stsEndpoint;

  @BeforeAll
  static void startContainers() {
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

    localstack =
        new LocalStackContainer(DockerImageName.parse(LOCALSTACK_IMAGE))
            .withServices(LocalStackContainer.Service.STS)
            .withEnv("AWS_DEFAULT_REGION", AWS_REGION);
    localstack.start();
    stsEndpoint = localstack.getEndpointOverride(LocalStackContainer.Service.STS).toString();
  }

  @AfterAll
  static void stopContainers() {
    if (keycloak != null) {
      keycloak.stop();
    }
    if (localstack != null) {
      localstack.stop();
    }
  }

  @Test
  void oauthTokenIsExchangedWithStsForTemporaryS3Credentials() {
    AwsIrsaCredentialGenerator generator = new AwsIrsaCredentialGenerator();
    generator.initialize(generatorProps());

    PathBasedCredentialContext context =
        new PathBasedCredentialContext(
            "test-user",
            ImmutableSet.of("s3://gravitino-test-bucket/write/"),
            ImmutableSet.of("s3://gravitino-test-bucket/read/"));

    AwsIrsaCredential credential = generator.generate(context);
    assertNotNull(credential, "credential must not be null");
    assertNotNull(credential.accessKeyId(), "accessKeyId must not be null");
    assertTrue(!credential.accessKeyId().isEmpty(), "accessKeyId must not be empty");
    assertNotNull(credential.secretAccessKey(), "secretAccessKey must not be null");
    assertTrue(!credential.secretAccessKey().isEmpty(), "secretAccessKey must not be empty");
    assertNotNull(credential.sessionToken(), "sessionToken must not be null");
    assertTrue(!credential.sessionToken().isEmpty(), "sessionToken must not be empty");
    assertTrue(
        credential.expireTimeInMs() > System.currentTimeMillis(),
        "expireTimeInMs must be in the future, got: " + credential.expireTimeInMs());
  }

  private static Map<String, String> generatorProps() {
    Map<String, String> props = new HashMap<>();
    // STS / role
    props.put("s3-role-arn", ROLE_ARN);
    props.put("s3-region", AWS_REGION);
    props.put("s3-token-service-endpoint", stsEndpoint);
    // WebIdentity token source = OAuth client_credentials against Keycloak
    props.put(WebIdentityTokenSourceConfig.SOURCE, OAuthClientCredentialsTokenSource.NAME);
    props.put(OAuthClientCredentialsTokenSource.OAUTH_TOKEN_ENDPOINT, tokenEndpoint);
    props.put(OAuthClientCredentialsTokenSource.OAUTH_CLIENT_ID, CLIENT_ID);
    props.put(OAuthClientCredentialsTokenSource.OAUTH_CLIENT_SECRET, CLIENT_SECRET);
    return props;
  }
}
