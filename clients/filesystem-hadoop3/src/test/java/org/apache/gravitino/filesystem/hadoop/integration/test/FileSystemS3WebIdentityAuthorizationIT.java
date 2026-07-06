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

import com.google.common.collect.Maps;
import java.util.Map;
import org.apache.gravitino.catalog.hadoop.fs.FileSystemUtils;
import org.apache.gravitino.credential.AwsIrsaCredential;
import org.apache.gravitino.s3.fs.S3FileSystemProvider;
import org.apache.gravitino.storage.S3Properties;
import org.apache.hadoop.conf.Configuration;
import org.apache.hadoop.fs.Path;
import org.junit.jupiter.api.condition.EnabledIf;
import org.junit.platform.commons.util.StringUtils;

/**
 * GVFS integration test that verifies WebIdentity-based credential vending with fine-grained
 * authorization on S3.
 *
 * <ul>
 *   <li>{@code READ_FILESET}: can read via GVFS but cannot write.
 *   <li>{@code WRITE_FILESET}: can read and write via GVFS.
 * </ul>
 *
 * <p>See {@link GravitinoVirtualFileSystemS3WebIdentityIT} for the required environment variables.
 */
@EnabledIf(value = "webIdentityIsConfigured", disabledReason = "web identity is not prepared")
public class FileSystemS3WebIdentityAuthorizationIT
    extends AbstractFileSystemCredentialAuthorizationIT {

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

  @Override
  protected String providerName() {
    return "s3";
  }

  @Override
  protected String providerBundleName() {
    return "aws-bundle";
  }

  @Override
  protected String credentialProviderType() {
    return AwsIrsaCredential.AWS_IRSA_CREDENTIAL_TYPE;
  }

  @Override
  protected Map<String, String> catalogBaseProperties() {
    Map<String, String> properties = Maps.newHashMap();
    if (StringUtils.isNotBlank(S3_ENDPOINT)) {
      properties.put(S3Properties.GRAVITINO_S3_ENDPOINT, S3_ENDPOINT);
    }
    properties.put(S3Properties.GRAVITINO_S3_REGION, S3_REGION);
    properties.put(S3Properties.GRAVITINO_S3_ROLE_ARN, S3_ROLE_ARN);
    if (StringUtils.isNotBlank(S3_STS_ENDPOINT)) {
      properties.put(S3Properties.GRAVITINO_S3_STS_ENDPOINT, S3_STS_ENDPOINT);
    }
    // Static AKSK the server uses to create and validate the fileset storage location on S3.
    // Credential vending to the GVFS client still goes through the WebIdentity source below.
    properties.put(S3Properties.GRAVITINO_S3_ACCESS_KEY_ID, S3_ACCESS_KEY_ID);
    properties.put(S3Properties.GRAVITINO_S3_SECRET_ACCESS_KEY, S3_SECRET_ACCESS_KEY);
    // WebIdentity OAuth client_credentials source vends short-lived credentials to the client.
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

  @Override
  protected String genStorageLocation(String fileset) {
    return String.format("s3a://%s/%s", BUCKET_NAME, fileset);
  }

  @Override
  protected Path genGvfsPath(String fileset) {
    return new Path(String.format("gvfs://fileset/%s/%s/%s", catalogName, schemaName, fileset));
  }

  @Override
  protected Configuration convertGvfsConfigToRealFileSystemConfig(Configuration gvfsConf) {
    Configuration s3Conf = new Configuration();
    Map<String, String> map = Maps.newHashMap();
    gvfsConf.forEach(entry -> map.put(entry.getKey(), entry.getValue()));
    map.put(S3Properties.GRAVITINO_S3_REGION, S3_REGION);
    // This direct filesystem bypasses GVFS and is not vended, so it needs the static AKSK for
    // setup and verification. Vended-privilege assertions run through the gvfs:// path instead.
    map.put(S3Properties.GRAVITINO_S3_ACCESS_KEY_ID, S3_ACCESS_KEY_ID);
    map.put(S3Properties.GRAVITINO_S3_SECRET_ACCESS_KEY, S3_SECRET_ACCESS_KEY);
    if (StringUtils.isNotBlank(S3_ENDPOINT)) {
      map.put(S3Properties.GRAVITINO_S3_ENDPOINT, S3_ENDPOINT);
    }
    Map<String, String> hadoopConfMap =
        FileSystemUtils.toHadoopConfigMap(map, S3FileSystemProvider.GRAVITINO_KEY_TO_S3_HADOOP_KEY);
    hadoopConfMap.forEach(s3Conf::set);
    return s3Conf;
  }

  @Override
  protected String providerPrefix() {
    return "gvfs_s3_webid";
  }

  @Override
  protected String providerRoleName() {
    return "gvfs_s3_web_identity_auth_role";
  }

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
