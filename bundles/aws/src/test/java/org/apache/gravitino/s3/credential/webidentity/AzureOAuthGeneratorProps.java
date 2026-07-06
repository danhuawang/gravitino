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

import java.util.HashMap;
import java.util.Map;
import org.apache.commons.lang3.StringUtils;

/**
 * Builds {@code AwsIrsaCredentialGenerator} properties wired to the Azure Entra ID OAuth token
 * source. Shared by the Azure manual integration test and its unit test so the property-building
 * logic can be verified without touching real Azure or S3.
 */
public final class AzureOAuthGeneratorProps {

  private AzureOAuthGeneratorProps() {}

  /**
   * Builds the credential generator properties for the Azure OAuth token source. The STS endpoint
   * is only included when {@code stsEndpoint} is non-blank, so S3-compatible services (for example
   * MinIO) can be targeted while real AWS relies on the default endpoint.
   *
   * @param tenantId the Azure directory (tenant) ID
   * @param clientId the Azure application (client) ID
   * @param clientSecret the Azure client secret
   * @param azureScope the OAuth2 scope, usually {@code api://<client-id>/.default}
   * @param roleArn the IAM role ARN to assume
   * @param region the AWS region for STS operations
   * @param stsEndpoint an optional STS endpoint override, ignored when blank
   * @return the generator properties
   */
  public static Map<String, String> build(
      String tenantId,
      String clientId,
      String clientSecret,
      String azureScope,
      String roleArn,
      String region,
      String stsEndpoint) {
    Map<String, String> props = new HashMap<>();
    props.put("s3-role-arn", roleArn);
    props.put("s3-region", region);
    if (StringUtils.isNotBlank(stsEndpoint)) {
      props.put("s3-token-service-endpoint", stsEndpoint);
    }
    props.put(WebIdentityTokenSourceConfig.SOURCE, OAuthClientCredentialsTokenSource.NAME);
    props.put(
        OAuthClientCredentialsTokenSource.OAUTH_TOKEN_ENDPOINT,
        "https://login.microsoftonline.com/" + tenantId + "/oauth2/v2.0/token");
    props.put(OAuthClientCredentialsTokenSource.OAUTH_CLIENT_ID, clientId);
    props.put(OAuthClientCredentialsTokenSource.OAUTH_CLIENT_SECRET, clientSecret);
    props.put(OAuthClientCredentialsTokenSource.OAUTH_SCOPE, azureScope);
    return props;
  }
}
