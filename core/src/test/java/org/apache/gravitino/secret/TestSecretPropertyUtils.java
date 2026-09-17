/*
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *
 *    http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an
 * "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
 * KIND, either express or implied.  See the License for the
 * specific language governing permissions and limitations
 * under the License.
 */
package org.apache.gravitino.secret;

import com.google.common.collect.ImmutableMap;
import java.util.Map;
import org.apache.gravitino.connector.PropertiesMetadata;
import org.apache.gravitino.connector.PropertyEntry;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

public class TestSecretPropertyUtils {

  @Test
  void testIsSensitivePropertyKey() {
    Assertions.assertTrue(SecretPropertyUtils.isSensitivePropertyKey("s3-secret-access-key"));
    Assertions.assertTrue(SecretPropertyUtils.isSensitivePropertyKey("S3_SECRET_ACCESS_KEY"));
    Assertions.assertTrue(SecretPropertyUtils.isSensitivePropertyKey("jdbc-password"));
    Assertions.assertTrue(SecretPropertyUtils.isSensitivePropertyKey("oauth2.token"));
    Assertions.assertTrue(SecretPropertyUtils.isSensitivePropertyKey("aws-access-key-id"));
    Assertions.assertTrue(SecretPropertyUtils.isSensitivePropertyKey("credential-provider"));
    Assertions.assertTrue(SecretPropertyUtils.isSensitivePropertyKey("azure-storage-account-key"));
    Assertions.assertTrue(SecretPropertyUtils.isSensitivePropertyKey("azure-storage-account-name"));
    Assertions.assertTrue(SecretPropertyUtils.isSensitivePropertyKey("gcs-service-account-file"));
    Assertions.assertTrue(SecretPropertyUtils.isSensitivePropertyKey("s3-access-key-id"));
    Assertions.assertFalse(SecretPropertyUtils.isSensitivePropertyKey("jdbc-user"));
    Assertions.assertFalse(SecretPropertyUtils.isSensitivePropertyKey("warehouse"));
    Assertions.assertFalse(SecretPropertyUtils.isSensitivePropertyKey("aws-region"));
    Assertions.assertFalse(SecretPropertyUtils.isSensitivePropertyKey(null));
    Assertions.assertFalse(SecretPropertyUtils.isSensitivePropertyKey(""));
  }

  @Test
  void testBuildSecretsIncludesInlineSensitivePlaintext() {
    Map<String, String> entityProps =
        Map.of(
            "warehouse",
            "s3://bucket/prefix",
            "aws-region",
            "us-east-2",
            "jdbc-user",
            "root",
            "s3-access-key-id",
            "AKIA...",
            "s3-secret-access-key",
            "super-secret",
            "jdbc-password",
            "s3cr3t");
    Map<String, String> secrets = SecretPropertyUtils.buildSecrets(entityProps);
    Assertions.assertEquals("AKIA...", secrets.get("s3-access-key-id"));
    Assertions.assertEquals("super-secret", secrets.get("s3-secret-access-key"));
    Assertions.assertEquals("s3cr3t", secrets.get("jdbc-password"));
    Assertions.assertFalse(secrets.containsKey("jdbc-user"));
    Assertions.assertFalse(secrets.containsKey("warehouse"));
    Assertions.assertFalse(secrets.containsKey("aws-region"));
  }

  @Test
  void testBuildSecretsNullMetadataRecoversNothing() {
    Map<String, String> entityProps =
        Map.of("s3-access-key-id", "AKIA", "jdbc-password", "inline-secret");
    Map<String, String> secrets = SecretPropertyUtils.buildSecrets(entityProps, null);
    Assertions.assertTrue(secrets.isEmpty());
  }

  @Test
  void testBuildSecretsNullAndEmpty() {
    Assertions.assertTrue(SecretPropertyUtils.buildSecrets(null).isEmpty());
    Assertions.assertTrue(SecretPropertyUtils.buildSecrets(Map.of()).isEmpty());
    Assertions.assertTrue(SecretPropertyUtils.buildSecrets(null, null).isEmpty());
  }

  @Test
  void testBuildSecretsExcludesDeclaredNonHiddenSensitiveKeys() {
    PropertiesMetadata metadata =
        new PropertiesMetadata() {
          @Override
          public Map<String, PropertyEntry<?>> propertyEntries() {
            return ImmutableMap.of(
                "credential-providers",
                PropertyEntry.stringOptionalPropertyEntry(
                    "credential-providers", "providers", false, null, false),
                "azure-storage-account-name",
                PropertyEntry.stringOptionalPropertyEntry(
                    "azure-storage-account-name", "account", false, null, false),
                "s3-access-key-id",
                PropertyEntry.stringOptionalPropertyEntry(
                    "s3-access-key-id", "ak", false, null, false),
                "jdbc-password",
                PropertyEntry.stringOptionalPropertyEntry(
                    "jdbc-password", "password", false, null, true),
                "s3-secret-access-key",
                PropertyEntry.stringOptionalPropertyEntry(
                    "s3-secret-access-key", "sk", false, null, true));
          }
        };
    Map<String, String> entityProps =
        Map.of(
            "credential-providers",
            "s3-token",
            "azure-storage-account-name",
            "abs-account",
            "s3-access-key-id",
            "AKIA",
            "jdbc-password",
            "inline-secret",
            "s3-secret-access-key",
            "super-secret",
            "custom-token",
            "tok");
    Map<String, String> secrets = SecretPropertyUtils.buildSecrets(entityProps, metadata);
    Assertions.assertFalse(secrets.containsKey("credential-providers"));
    Assertions.assertFalse(secrets.containsKey("azure-storage-account-name"));
    Assertions.assertFalse(secrets.containsKey("s3-access-key-id"));
    Assertions.assertEquals("inline-secret", secrets.get("jdbc-password"));
    Assertions.assertEquals("super-secret", secrets.get("s3-secret-access-key"));
    Assertions.assertEquals("tok", secrets.get("custom-token"));
  }

  @Test
  void testMergeProperties() {
    Map<String, String> merged =
        SecretPropertyUtils.mergeProperties(Map.of("a", "1"), Map.of("b", "2", "a", "override"));
    Assertions.assertEquals("override", merged.get("a"));
    Assertions.assertEquals("2", merged.get("b"));
    Assertions.assertTrue(SecretPropertyUtils.mergeProperties(null, null).isEmpty());
  }

  @Test
  void testCopyEntityProperties() {
    Assertions.assertTrue(SecretPropertyUtils.copyEntityProperties(null).isEmpty());
    Map<String, String> original = Map.of("a", "b");
    Map<String, String> copy = SecretPropertyUtils.copyEntityProperties(original);
    Assertions.assertEquals(original, copy);
    copy.put("c", "d");
    Assertions.assertFalse(original.containsKey("c"));
  }
}
