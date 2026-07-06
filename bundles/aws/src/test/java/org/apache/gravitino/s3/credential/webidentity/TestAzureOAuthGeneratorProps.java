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

import java.util.Map;
import org.junit.jupiter.api.Test;

class TestAzureOAuthGeneratorProps {

  @Test
  void omitsStsEndpointWhenBlank() {
    Map<String, String> props =
        AzureOAuthGeneratorProps.build(
            "tenant",
            "client",
            "secret",
            "api://client/.default",
            "arn:aws:iam::1:role/r",
            "us-east-1",
            "");
    assertFalse(props.containsKey("s3-token-service-endpoint"));
  }

  @Test
  void includesStsEndpointWhenConfigured() {
    Map<String, String> props =
        AzureOAuthGeneratorProps.build(
            "tenant",
            "client",
            "secret",
            "api://client/.default",
            "arn:minio:iam:::role/test",
            "ap-northeast-1",
            "http://127.0.0.1:19000");
    assertEquals("http://127.0.0.1:19000", props.get("s3-token-service-endpoint"));
  }

  @Test
  void wiresOAuthTokenSourceForAzure() {
    Map<String, String> props =
        AzureOAuthGeneratorProps.build(
            "my-tenant",
            "my-client",
            "my-secret",
            "api://my-client/.default",
            "arn:aws:iam::1:role/r",
            "us-east-1",
            "");
    assertEquals(
        OAuthClientCredentialsTokenSource.NAME, props.get(WebIdentityTokenSourceConfig.SOURCE));
    assertEquals(
        "https://login.microsoftonline.com/my-tenant/oauth2/v2.0/token",
        props.get(OAuthClientCredentialsTokenSource.OAUTH_TOKEN_ENDPOINT));
    assertEquals("my-client", props.get(OAuthClientCredentialsTokenSource.OAUTH_CLIENT_ID));
    assertEquals("my-secret", props.get(OAuthClientCredentialsTokenSource.OAUTH_CLIENT_SECRET));
    assertEquals(
        "api://my-client/.default", props.get(OAuthClientCredentialsTokenSource.OAUTH_SCOPE));
  }
}
