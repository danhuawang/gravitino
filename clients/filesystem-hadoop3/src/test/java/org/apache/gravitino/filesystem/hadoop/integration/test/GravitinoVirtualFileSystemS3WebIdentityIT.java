/*
 *  Licensed to the Apache Software Foundation (ASF) under one
 *  or more contributor license agreements.  See the NOTICE file
 *  distributed with this work for additional information
 *  regarding copyright ownership.  The ASF licenses this file
 *  to you under the Apache License, Version 2.0 (the
 *  "License"); you may not use this file except in compliance
 *  with the License.  You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 *  Unless required by applicable law or agreed to in writing,
 *  software distributed under the License is distributed on an
 *  "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
 *  KIND, either express or implied.  See the License for the
 *  specific language governing permissions and limitations
 *  under the License.
 */

package org.apache.gravitino.filesystem.hadoop.integration.test;

import static org.apache.gravitino.catalog.fileset.FilesetCatalogPropertiesMetadata.FILESYSTEM_PROVIDERS;

import com.google.common.collect.Maps;
import java.io.IOException;
import java.util.Collections;
import java.util.Map;
import org.apache.gravitino.Catalog;
import org.apache.gravitino.catalog.hadoop.fs.FileSystemUtils;
import org.apache.gravitino.credential.AwsIrsaCredential;
import org.apache.gravitino.credential.CredentialConstants;
import org.apache.gravitino.integration.test.util.GravitinoITUtils;
import org.apache.gravitino.s3.fs.S3FileSystemProvider;
import org.apache.gravitino.storage.S3Properties;
import org.apache.hadoop.conf.Configuration;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Disabled;
import org.junit.jupiter.api.condition.EnabledIf;
import org.junit.platform.commons.util.StringUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * GVFS integration test that verifies credential vending against S3 using the {@code aws-irsa}
 * credential provider backed by an OAuth2 {@code client_credentials} WebIdentity token source.
 *
 * <p>The catalog carries static S3 access keys that the Gravitino server uses for its own fileset
 * storage-location operations (creating and validating the location). Credential vending to the
 * GVFS client, however, uses the OAuth path: Gravitino fetches an OIDC token from the configured
 * OAuth2 provider (Keycloak / Azure Entra ID / Okta / Auth0 / etc.), exchanges it via STS {@code
 * AssumeRoleWithWebIdentity} for short-lived AKSK + session token, and vends those back to the GVFS
 * client.
 *
 * <p>Required environment variables:
 *
 * <ul>
 *   <li>{@code S3_BUCKET_NAME_FOR_WEB_IDENTITY} — bucket the role is allowed to read/write
 *   <li>{@code S3_REGION_FOR_WEB_IDENTITY}
 *   <li>{@code S3_ROLE_ARN_FOR_WEB_IDENTITY} — IAM role configured to trust the OIDC provider
 *   <li>{@code S3_ACCESS_KEY_ID_FOR_WEB_IDENTITY} — static access key for server-side location
 *       operations
 *   <li>{@code S3_SECRET_ACCESS_KEY_FOR_WEB_IDENTITY} — static secret key for server-side location
 *       operations
 *   <li>{@code OAUTH_TOKEN_ENDPOINT_FOR_WEB_IDENTITY}
 *   <li>{@code OAUTH_CLIENT_ID_FOR_WEB_IDENTITY}
 *   <li>{@code OAUTH_CLIENT_SECRET_FOR_WEB_IDENTITY}
 * </ul>
 *
 * <p>Optional:
 *
 * <ul>
 *   <li>{@code S3_ENDPOINT_FOR_WEB_IDENTITY} — S3 endpoint override
 *   <li>{@code S3_TOKEN_SERVICE_ENDPOINT_FOR_WEB_IDENTITY} — STS endpoint override
 *   <li>{@code OAUTH_SCOPE_FOR_WEB_IDENTITY}
 *   <li>{@code OAUTH_AUDIENCE_FOR_WEB_IDENTITY}
 * </ul>
 */
@EnabledIf(value = "webIdentityIsConfigured", disabledReason = "web identity is not prepared")
public class GravitinoVirtualFileSystemS3WebIdentityIT extends GravitinoVirtualFileSystemIT {
  private static final Logger LOG =
      LoggerFactory.getLogger(GravitinoVirtualFileSystemS3WebIdentityIT.class);

  public static final String BUCKET_NAME = System.getenv("S3_BUCKET_NAME_FOR_WEB_IDENTITY");
  public static final String S3_ENDPOINT = System.getenv("S3_ENDPOINT_FOR_WEB_IDENTITY");
  public static final String S3_REGION = System.getenv("S3_REGION_FOR_WEB_IDENTITY");
  public static final String S3_ROLE_ARN = System.getenv("S3_ROLE_ARN_FOR_WEB_IDENTITY");
  public static final String S3_ACCESS_KEY_ID = System.getenv("S3_ACCESS_KEY_ID_FOR_WEB_IDENTITY");
  public static final String S3_SECRET_ACCESS_KEY =
      System.getenv("S3_SECRET_ACCESS_KEY_FOR_WEB_IDENTITY");
  public static final String S3_STS_ENDPOINT =
      System.getenv("S3_TOKEN_SERVICE_ENDPOINT_FOR_WEB_IDENTITY");
  public static final String OAUTH_TOKEN_ENDPOINT =
      System.getenv("OAUTH_TOKEN_ENDPOINT_FOR_WEB_IDENTITY");
  public static final String OAUTH_CLIENT_ID = System.getenv("OAUTH_CLIENT_ID_FOR_WEB_IDENTITY");
  public static final String OAUTH_CLIENT_SECRET =
      System.getenv("OAUTH_CLIENT_SECRET_FOR_WEB_IDENTITY");
  public static final String OAUTH_SCOPE = System.getenv("OAUTH_SCOPE_FOR_WEB_IDENTITY");
  public static final String OAUTH_AUDIENCE = System.getenv("OAUTH_AUDIENCE_FOR_WEB_IDENTITY");

  @BeforeAll
  public void startIntegrationTest() {
    // Do nothing — startUp() takes over.
  }

  @BeforeAll
  public void startUp() throws Exception {
    copyBundleJarsToHadoop("aws-bundle");

    // Need to download jars to gravitino server
    super.startIntegrationTest();

    defaultBlockSize = 32 * 1024 * 1024;
    defaultReplication = 1;

    metalakeName = GravitinoITUtils.genRandomName("gvfs_it_metalake");
    catalogName = GravitinoITUtils.genRandomName("catalog");
    schemaName = GravitinoITUtils.genRandomName("schema");

    Assertions.assertFalse(client.metalakeExists(metalakeName));
    metalake = client.createMetalake(metalakeName, "metalake comment", Collections.emptyMap());
    Assertions.assertTrue(client.metalakeExists(metalakeName));

    Map<String, String> properties = catalogProperties();

    Catalog catalog =
        metalake.createCatalog(
            catalogName, Catalog.Type.FILESET, "hadoop", "catalog comment", properties);
    Assertions.assertTrue(metalake.catalogExists(catalogName));

    catalog.asSchemas().createSchema(schemaName, "schema comment", properties);
    Assertions.assertTrue(catalog.asSchemas().schemaExists(schemaName));

    conf.set("fs.gvfs.impl", "org.apache.gravitino.filesystem.hadoop.GravitinoVirtualFileSystem");
    conf.set("fs.AbstractFileSystem.gvfs.impl", "org.apache.gravitino.filesystem.hadoop.Gvfs");
    conf.set("fs.gvfs.impl.disable.cache", "true");
    conf.set("fs.gravitino.server.uri", serverUri);
    conf.set("fs.gravitino.client.metalake", metalakeName);
    conf.set("fs.gravitino.enableCredentialVending", "true");

    if (StringUtils.isNotBlank(S3_ENDPOINT)) {
      conf.set(S3Properties.GRAVITINO_S3_ENDPOINT, S3_ENDPOINT);
    }
    conf.set(S3Properties.GRAVITINO_S3_REGION, S3_REGION);
    conf.set(S3Properties.GRAVITINO_S3_ROLE_ARN, S3_ROLE_ARN);
  }

  @AfterAll
  public void tearDown() throws IOException {
    Catalog catalog = metalake.loadCatalog(catalogName);
    catalog.asSchemas().dropSchema(schemaName, true);
    metalake.dropCatalog(catalogName, true);
    client.dropMetalake(metalakeName, true);

    if (client != null) {
      client.close();
      client = null;
    }

    try {
      closer.close();
    } catch (Exception e) {
      LOG.error("Exception in closing CloseableGroup", e);
    }
  }

  private static Map<String, String> catalogProperties() {
    Map<String, String> properties = Maps.newHashMap();
    if (StringUtils.isNotBlank(S3_ENDPOINT)) {
      properties.put(S3Properties.GRAVITINO_S3_ENDPOINT, S3_ENDPOINT);
    }
    properties.put(FILESYSTEM_PROVIDERS, "s3");
    properties.put(S3Properties.GRAVITINO_S3_REGION, S3_REGION);
    properties.put(S3Properties.GRAVITINO_S3_ROLE_ARN, S3_ROLE_ARN);
    if (StringUtils.isNotBlank(S3_STS_ENDPOINT)) {
      properties.put(S3Properties.GRAVITINO_S3_STS_ENDPOINT, S3_STS_ENDPOINT);
    }
    // Static AKSK the server uses to create and validate the fileset storage location on S3.
    // Credential vending to the GVFS client still goes through the WebIdentity source below.
    properties.put(S3Properties.GRAVITINO_S3_ACCESS_KEY_ID, S3_ACCESS_KEY_ID);
    properties.put(S3Properties.GRAVITINO_S3_SECRET_ACCESS_KEY, S3_SECRET_ACCESS_KEY);
    // Credential vending: aws-irsa provider + oauth-client-credentials WebIdentity source.
    properties.put(
        CredentialConstants.CREDENTIAL_PROVIDERS, AwsIrsaCredential.AWS_IRSA_CREDENTIAL_TYPE);
    properties.put("s3-web-identity-token-source", "oauth-client-credentials");
    properties.put("s3-web-identity-token-endpoint", OAUTH_TOKEN_ENDPOINT);
    properties.put("s3-web-identity-token-client-id", OAUTH_CLIENT_ID);
    properties.put("s3-web-identity-token-client-secret", OAUTH_CLIENT_SECRET);
    if (StringUtils.isNotBlank(OAUTH_SCOPE)) {
      properties.put("s3-web-identity-token-scope", OAUTH_SCOPE);
    }
    if (StringUtils.isNotBlank(OAUTH_AUDIENCE)) {
      properties.put("s3-web-identity-token-audience", OAUTH_AUDIENCE);
    }
    return properties;
  }

  /**
   * Translate the GVFS configuration into the underlying S3A configuration used to verify objects
   * directly on S3. This direct filesystem bypasses GVFS, so it is not vended and carries the same
   * static AKSK the server uses; credential vending only applies to the {@code gvfs://} client
   * path.
   */
  protected Configuration convertGvfsConfigToRealFileSystemConfig(Configuration gvfsConf) {
    Configuration s3Conf = new Configuration();
    Map<String, String> map = Maps.newHashMap();
    gvfsConf.forEach(entry -> map.put(entry.getKey(), entry.getValue()));
    map.put(S3Properties.GRAVITINO_S3_ACCESS_KEY_ID, S3_ACCESS_KEY_ID);
    map.put(S3Properties.GRAVITINO_S3_SECRET_ACCESS_KEY, S3_SECRET_ACCESS_KEY);
    Map<String, String> hadoopConfMap =
        FileSystemUtils.toHadoopConfigMap(map, S3FileSystemProvider.GRAVITINO_KEY_TO_S3_HADOOP_KEY);
    hadoopConfMap.forEach(s3Conf::set);
    return s3Conf;
  }

  protected String genStorageLocation(String fileset) {
    return String.format("s3a://%s/%s", BUCKET_NAME, fileset);
  }

  @Disabled("S3A append support is not enabled in this test environment")
  public void testAppend() throws IOException {}

  protected static boolean webIdentityIsConfigured() {
    return StringUtils.isNotBlank(System.getenv("S3_BUCKET_NAME_FOR_WEB_IDENTITY"))
        && StringUtils.isNotBlank(System.getenv("S3_REGION_FOR_WEB_IDENTITY"))
        && StringUtils.isNotBlank(System.getenv("S3_ROLE_ARN_FOR_WEB_IDENTITY"))
        && StringUtils.isNotBlank(System.getenv("S3_ACCESS_KEY_ID_FOR_WEB_IDENTITY"))
        && StringUtils.isNotBlank(System.getenv("S3_SECRET_ACCESS_KEY_FOR_WEB_IDENTITY"))
        && StringUtils.isNotBlank(System.getenv("OAUTH_TOKEN_ENDPOINT_FOR_WEB_IDENTITY"))
        && StringUtils.isNotBlank(System.getenv("OAUTH_CLIENT_ID_FOR_WEB_IDENTITY"))
        && StringUtils.isNotBlank(System.getenv("OAUTH_CLIENT_SECRET_FOR_WEB_IDENTITY"));
  }
}
