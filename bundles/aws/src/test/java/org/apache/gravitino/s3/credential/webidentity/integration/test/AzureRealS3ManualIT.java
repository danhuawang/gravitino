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
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import com.google.common.collect.ImmutableSet;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.UUID;
import org.apache.commons.lang3.StringUtils;
import org.apache.gravitino.credential.AwsIrsaCredential;
import org.apache.gravitino.credential.PathBasedCredentialContext;
import org.apache.gravitino.s3.credential.AwsIrsaCredentialGenerator;
import org.apache.gravitino.s3.credential.webidentity.AzureOAuthGeneratorProps;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import software.amazon.awssdk.auth.credentials.AwsSessionCredentials;
import software.amazon.awssdk.auth.credentials.StaticCredentialsProvider;
import software.amazon.awssdk.core.ResponseBytes;
import software.amazon.awssdk.core.sync.RequestBody;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.S3ClientBuilder;
import software.amazon.awssdk.services.s3.model.DeleteObjectRequest;
import software.amazon.awssdk.services.s3.model.GetObjectRequest;
import software.amazon.awssdk.services.s3.model.GetObjectResponse;
import software.amazon.awssdk.services.s3.model.PutObjectRequest;

/**
 * Manual end-to-end integration test against <b>real</b> Azure Entra ID and <b>real</b> AWS S3.
 *
 * <p>This test exercises the full Gravitino credential vending path with no LocalStack or Keycloak
 * in the loop:
 *
 * <ol>
 *   <li>{@link OAuthClientCredentialsTokenSource} fetches a JWT from Azure Entra ID using
 *       client_credentials.
 *   <li>{@link AwsIrsaCredentialGenerator} calls real AWS STS {@code AssumeRoleWithWebIdentity}
 *       with that JWT and a scoped session policy.
 *   <li>The returned {@link AwsIrsaCredential} is used to put / get / delete an object in a real S3
 *       bucket.
 * </ol>
 *
 * <p>The test is skipped (via {@code assumeTrue}) unless the required environment variables are
 * set, so it never runs in regular CI by accident:
 *
 * <ul>
 *   <li>{@code GRAVITINO_AZURE_TENANT_ID}
 *   <li>{@code GRAVITINO_AZURE_CLIENT_ID}
 *   <li>{@code GRAVITINO_AZURE_CLIENT_SECRET}
 *   <li>{@code GRAVITINO_AZURE_SCOPE} — usually {@code api://<aud>/.default}; the audience must
 *       match the OIDC provider trust configured on the AWS IAM role
 *   <li>{@code GRAVITINO_AWS_ROLE_ARN} — IAM role configured to trust the Azure OIDC issuer
 *   <li>{@code GRAVITINO_AWS_REGION}
 *   <li>{@code GRAVITINO_AWS_S3_BUCKET} — bucket the role is allowed to read/write
 *   <li>{@code GRAVITINO_AWS_S3_PREFIX} (optional) — key prefix; defaults to {@code gravitino-it/}
 *   <li>{@code GRAVITINO_AWS_STS_ENDPOINT} (optional) — STS endpoint override for S3-compatible
 *       services
 *   <li>{@code GRAVITINO_AWS_S3_ENDPOINT} (optional) — S3 endpoint override for S3-compatible
 *       services
 *   <li>{@code GRAVITINO_AWS_S3_PATH_STYLE_ACCESS} (optional) — set to {@code true} for path-style
 *       S3 access
 *   <li>{@code GRAVITINO_AWS_S3_KEEP_OBJECT} (optional) — set to {@code true} to keep the test
 *       object for manual verification
 * </ul>
 *
 * <p>Example invocation:
 *
 * <pre>
 *   GRAVITINO_AZURE_TENANT_ID=...     GRAVITINO_AZURE_CLIENT_ID=... \
 *   GRAVITINO_AZURE_CLIENT_SECRET=... GRAVITINO_AZURE_SCOPE=api://.../.default \
 *   GRAVITINO_AWS_ROLE_ARN=arn:aws:iam::123:role/azure-fed \
 *   GRAVITINO_AWS_REGION=us-east-1    GRAVITINO_AWS_S3_BUCKET=my-bucket \
 *   ./gradlew :bundles:aws:test \
 *     --tests org.apache.gravitino.s3.credential.webidentity.integration.test.AzureRealS3ManualIT
 * </pre>
 */
class AzureRealS3ManualIT {

  private static final String ENV_TENANT_ID = "GRAVITINO_AZURE_TENANT_ID";
  private static final String ENV_CLIENT_ID = "GRAVITINO_AZURE_CLIENT_ID";
  private static final String ENV_CLIENT_SECRET = "GRAVITINO_AZURE_CLIENT_SECRET";
  private static final String ENV_AZURE_SCOPE = "GRAVITINO_AZURE_SCOPE";
  private static final String ENV_AWS_ROLE_ARN = "GRAVITINO_AWS_ROLE_ARN";
  private static final String ENV_AWS_REGION = "GRAVITINO_AWS_REGION";
  private static final String ENV_S3_BUCKET = "GRAVITINO_AWS_S3_BUCKET";
  private static final String ENV_S3_PREFIX = "GRAVITINO_AWS_S3_PREFIX";
  private static final String ENV_STS_ENDPOINT = "GRAVITINO_AWS_STS_ENDPOINT";
  private static final String ENV_S3_ENDPOINT = "GRAVITINO_AWS_S3_ENDPOINT";
  private static final String ENV_S3_PATH_STYLE_ACCESS = "GRAVITINO_AWS_S3_PATH_STYLE_ACCESS";
  private static final String ENV_S3_KEEP_OBJECT = "GRAVITINO_AWS_S3_KEEP_OBJECT";

  private static String tenantId;
  private static String clientId;
  private static String clientSecret;
  private static String azureScope;
  private static String roleArn;
  private static String region;
  private static String bucket;
  private static String prefix;
  private static String stsEndpoint;
  private static String s3Endpoint;
  private static boolean s3PathStyleAccess;
  private static boolean s3KeepObject;

  @BeforeAll
  static void loadEnv() {
    tenantId = System.getenv(ENV_TENANT_ID);
    clientId = System.getenv(ENV_CLIENT_ID);
    clientSecret = System.getenv(ENV_CLIENT_SECRET);
    azureScope = System.getenv(ENV_AZURE_SCOPE);
    roleArn = System.getenv(ENV_AWS_ROLE_ARN);
    region = System.getenv(ENV_AWS_REGION);
    bucket = System.getenv(ENV_S3_BUCKET);
    prefix = StringUtils.defaultIfBlank(System.getenv(ENV_S3_PREFIX), "gravitino-it/");
    stsEndpoint = System.getenv(ENV_STS_ENDPOINT);
    s3Endpoint = System.getenv(ENV_S3_ENDPOINT);
    s3PathStyleAccess = Boolean.parseBoolean(System.getenv(ENV_S3_PATH_STYLE_ACCESS));
    s3KeepObject = Boolean.parseBoolean(System.getenv(ENV_S3_KEEP_OBJECT));
    assumeTrue(
        StringUtils.isNoneBlank(
            tenantId, clientId, clientSecret, azureScope, roleArn, region, bucket),
        "Azure + S3 manual IT requires the following env vars: "
            + ENV_TENANT_ID
            + ", "
            + ENV_CLIENT_ID
            + ", "
            + ENV_CLIENT_SECRET
            + ", "
            + ENV_AZURE_SCOPE
            + ", "
            + ENV_AWS_ROLE_ARN
            + ", "
            + ENV_AWS_REGION
            + ", "
            + ENV_S3_BUCKET
            + ".");
  }

  @Test
  void vendsCredentialsAndReadsWritesRealS3Object() {
    String objectKey = prefix + "vended-" + UUID.randomUUID() + ".txt";
    String payload = "gravitino azure entra id + sts end-to-end check";

    AwsIrsaCredentialGenerator generator = new AwsIrsaCredentialGenerator();
    generator.initialize(generatorProps());

    String objectUriPrefix = "s3://" + bucket + "/";
    PathBasedCredentialContext context =
        new PathBasedCredentialContext(
            "azure-real-s3-it",
            /* writePaths */ ImmutableSet.of(objectUriPrefix),
            /* readPaths */ ImmutableSet.of(objectUriPrefix));

    AwsIrsaCredential credential = generator.generate(context);
    assertNotNull(credential);
    assertTrue(StringUtils.isNotBlank(credential.accessKeyId()), "accessKeyId must not be blank");
    assertTrue(
        StringUtils.isNotBlank(credential.secretAccessKey()), "secretAccessKey must not be blank");
    assertTrue(StringUtils.isNotBlank(credential.sessionToken()), "sessionToken must not be blank");
    assertTrue(
        credential.expireTimeInMs() > System.currentTimeMillis(),
        "expireTimeInMs must be in the future");

    AwsSessionCredentials sessionCredentials =
        AwsSessionCredentials.create(
            credential.accessKeyId(), credential.secretAccessKey(), credential.sessionToken());

    try (S3Client s3 = createS3Client(region, sessionCredentials, s3Endpoint, s3PathStyleAccess)) {

      s3.putObject(
          PutObjectRequest.builder().bucket(bucket).key(objectKey).build(),
          RequestBody.fromString(payload, StandardCharsets.UTF_8));

      ResponseBytes<GetObjectResponse> response =
          s3.getObjectAsBytes(GetObjectRequest.builder().bucket(bucket).key(objectKey).build());
      assertEquals(payload, response.asString(StandardCharsets.UTF_8));

      if (!s3KeepObject) {
        s3.deleteObject(DeleteObjectRequest.builder().bucket(bucket).key(objectKey).build());
      }
    }
  }

  private static Map<String, String> generatorProps() {
    return AzureOAuthGeneratorProps.build(
        tenantId, clientId, clientSecret, azureScope, roleArn, region, stsEndpoint);
  }

  private static S3Client createS3Client(
      String region,
      AwsSessionCredentials sessionCredentials,
      String s3Endpoint,
      boolean s3PathStyleAccess) {
    S3ClientBuilder builder =
        S3Client.builder()
            .region(Region.of(region))
            .credentialsProvider(StaticCredentialsProvider.create(sessionCredentials));
    if (StringUtils.isNotBlank(s3Endpoint)) {
      builder.endpointOverride(URI.create(s3Endpoint));
    }
    if (s3PathStyleAccess) {
      builder.forcePathStyle(true);
    }
    return builder.build();
  }
}
