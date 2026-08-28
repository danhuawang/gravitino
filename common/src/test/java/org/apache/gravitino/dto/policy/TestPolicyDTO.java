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
package org.apache.gravitino.dto.policy;

import com.fasterxml.jackson.annotation.JsonAutoDetect;
import com.fasterxml.jackson.annotation.PropertyAccessor;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.exc.InvalidDefinitionException;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.ImmutableSet;
import java.time.Instant;
import java.util.Optional;
import org.apache.gravitino.MetadataObject;
import org.apache.gravitino.dto.AuditDTO;
import org.apache.gravitino.dto.encryption.kms.KmsReferenceDTO;
import org.apache.gravitino.dto.util.DTOConverters;
import org.apache.gravitino.encryption.kms.KmsReference;
import org.apache.gravitino.json.JsonUtils;
import org.apache.gravitino.policy.IcebergDataCompactionContent;
import org.apache.gravitino.policy.IcebergEncryptionContent;
import org.apache.gravitino.policy.PolicyContent;
import org.apache.gravitino.policy.PolicyContents;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

public class TestPolicyDTO {

  @Test
  public void testPolicySerDe() throws JsonProcessingException {
    AuditDTO audit = AuditDTO.builder().withCreator("user1").withCreateTime(Instant.now()).build();
    PolicyContentDTO.CustomContentDTO customContent =
        PolicyContentDTO.CustomContentDTO.builder()
            .withCustomRules(ImmutableMap.of("key1", "value1"))
            .withSupportedObjectTypes(
                ImmutableSet.of(MetadataObject.Type.CATALOG, MetadataObject.Type.TABLE))
            .withProperties(ImmutableMap.of("prop1", "value1"))
            .build();

    PolicyDTO policyDTO =
        PolicyDTO.builder()
            .withName("policy_test")
            .withComment("policy comment")
            .withPolicyType("my_compaction")
            .withEnabled(true)
            .withContent(customContent)
            .withAudit(audit)
            .build();

    String serJson = JsonUtils.objectMapper().writeValueAsString(policyDTO);
    PolicyDTO deserPolicyDTO = JsonUtils.objectMapper().readValue(serJson, PolicyDTO.class);
    Assertions.assertEquals(policyDTO, deserPolicyDTO);

    Assertions.assertEquals("policy_test", deserPolicyDTO.name());
    Assertions.assertEquals("policy comment", deserPolicyDTO.comment());
    Assertions.assertEquals("my_compaction", deserPolicyDTO.policyType());
    Assertions.assertTrue(deserPolicyDTO.enabled());
    Assertions.assertEquals(customContent, deserPolicyDTO.content());
    Assertions.assertEquals(audit, deserPolicyDTO.auditInfo());

    // Test policy with inherited
    PolicyDTO policyDTO2 =
        PolicyDTO.builder()
            .withName("policy_test")
            .withComment("policy comment")
            .withPolicyType("my_compaction")
            .withContent(customContent)
            .withAudit(audit)
            .withInherited(Optional.empty())
            .build();

    serJson = JsonUtils.objectMapper().writeValueAsString(policyDTO2);
    PolicyDTO deserPolicyDTO2 = JsonUtils.objectMapper().readValue(serJson, PolicyDTO.class);
    Assertions.assertEquals(policyDTO2, deserPolicyDTO2);
    Assertions.assertEquals(Optional.empty(), deserPolicyDTO2.inherited());

    PolicyDTO policyDTO3 =
        PolicyDTO.builder()
            .withName("policy_test")
            .withComment("policy comment")
            .withPolicyType("my_compaction")
            .withContent(customContent)
            .withAudit(audit)
            .withInherited(Optional.of(false))
            .build();

    serJson = JsonUtils.objectMapper().writeValueAsString(policyDTO3);
    PolicyDTO deserPolicyDTO3 = JsonUtils.objectMapper().readValue(serJson, PolicyDTO.class);
    Assertions.assertEquals(Optional.of(false), deserPolicyDTO3.inherited());

    PolicyDTO policyDTO4 =
        PolicyDTO.builder()
            .withName("policy_test")
            .withComment("policy comment")
            .withPolicyType("my_compaction")
            .withContent(customContent)
            .withAudit(audit)
            .withInherited(Optional.of(true))
            .build();

    serJson = JsonUtils.objectMapper().writeValueAsString(policyDTO4);
    PolicyDTO deserPolicyDTO4 = JsonUtils.objectMapper().readValue(serJson, PolicyDTO.class);
    Assertions.assertEquals(Optional.of(true), deserPolicyDTO4.inherited());
  }

  @Test
  public void testIcebergCompactionPolicySerDe() throws JsonProcessingException {
    AuditDTO audit = AuditDTO.builder().withCreator("user1").withCreateTime(Instant.now()).build();
    PolicyContentDTO.IcebergCompactionContentDTO typedContent =
        PolicyContentDTO.IcebergCompactionContentDTO.builder()
            .withMinDataFileMse(1000L)
            .withMinDeleteFileNumber(1L)
            .withDataFileMseWeight(2L)
            .withDeleteFileNumberWeight(150L)
            .withMaxPartitionNum(99L)
            .withRewriteOptions(
                ImmutableMap.of("target-file-size-bytes", "1048576", "min-input-files", "1"))
            .build();

    PolicyDTO policyDTO =
        PolicyDTO.builder()
            .withName("iceberg-compaction")
            .withComment("typed policy")
            .withPolicyType("system_iceberg_compaction")
            .withEnabled(true)
            .withContent(typedContent)
            .withAudit(audit)
            .build();

    String serJson = JsonUtils.objectMapper().writeValueAsString(policyDTO);
    PolicyDTO deserPolicyDTO = JsonUtils.objectMapper().readValue(serJson, PolicyDTO.class);

    Assertions.assertEquals(policyDTO, deserPolicyDTO);
    Assertions.assertInstanceOf(
        PolicyContentDTO.IcebergCompactionContentDTO.class, deserPolicyDTO.content());
  }

  @Test
  public void testIcebergCompactionPolicyDefaultValues() throws JsonProcessingException {
    String json =
        "{"
            + "\"name\":\"iceberg-compaction-default\","
            + "\"comment\":\"typed policy\","
            + "\"policyType\":\"system_iceberg_compaction\","
            + "\"enabled\":true,"
            + "\"content\":{}"
            + "}";

    PolicyDTO policyDTO = JsonUtils.objectMapper().readValue(json, PolicyDTO.class);
    PolicyContentDTO.IcebergCompactionContentDTO contentDTO =
        (PolicyContentDTO.IcebergCompactionContentDTO) policyDTO.content();

    Assertions.assertEquals(
        IcebergDataCompactionContent.DEFAULT_MIN_DATA_FILE_MSE, contentDTO.minDataFileMse());
    Assertions.assertEquals(
        IcebergDataCompactionContent.DEFAULT_MIN_DELETE_FILE_NUMBER,
        contentDTO.minDeleteFileNumber());
    Assertions.assertEquals(
        IcebergDataCompactionContent.DEFAULT_DATA_FILE_MSE_WEIGHT, contentDTO.dataFileMseWeight());
    Assertions.assertEquals(
        IcebergDataCompactionContent.DEFAULT_DELETE_FILE_NUMBER_WEIGHT,
        contentDTO.deleteFileNumberWeight());
    Assertions.assertEquals(
        IcebergDataCompactionContent.DEFAULT_MAX_PARTITION_NUM, contentDTO.maxPartitionNum());
    Assertions.assertTrue(contentDTO.rewriteOptions().isEmpty());
    Assertions.assertDoesNotThrow(contentDTO::validate);
  }

  @Test
  public void testIcebergEncryptionPolicySerDe() throws JsonProcessingException {
    AuditDTO audit = AuditDTO.builder().withCreator("user1").withCreateTime(Instant.now()).build();
    PolicyContentDTO.IcebergEncryptionContentDTO content = encryptionContent();
    PolicyDTO policy =
        PolicyDTO.builder()
            .withName("customer-data-encryption")
            .withComment("Require an approved customer key")
            .withPolicyType("system_iceberg_encryption")
            .withEnabled(true)
            .withContent(content)
            .withAudit(audit)
            .build();

    String json = JsonUtils.objectMapper().writeValueAsString(policy);
    PolicyDTO deserialized = JsonUtils.objectMapper().readValue(json, PolicyDTO.class);

    Assertions.assertEquals(policy, deserialized);
    Assertions.assertInstanceOf(
        PolicyContentDTO.IcebergEncryptionContentDTO.class, deserialized.content());
    PolicyContentDTO.IcebergEncryptionContentDTO deserializedContent =
        (PolicyContentDTO.IcebergEncryptionContentDTO) deserialized.content();
    Assertions.assertEquals("aws-prod", deserializedContent.allowedKeys().get(0).getProvider());
    Assertions.assertEquals("Key-A", deserializedContent.allowedKeys().get(0).getKeyId());
    Assertions.assertFalse(json.contains("\"selector\""));
    Assertions.assertFalse(json.contains("\"api\""));
    Assertions.assertFalse(json.contains("\"source\""));
    Assertions.assertTrue(json.contains("\"enforcement\":\"deny-create\""));
  }

  @Test
  public void testIcebergEncryptionPolicyDefaults() throws JsonProcessingException {
    String json =
        "{"
            + "\"name\":\"iceberg-encryption-default\","
            + "\"policyType\":\"system_iceberg_encryption\","
            + "\"enabled\":true,"
            + "\"content\":{"
            + "\"schemaVersion\":1,"
            + "\"allowedKeys\":[{\"provider\":\"openbao-production\",\"keyId\":\"key-a\"}]"
            + "}"
            + "}";

    PolicyDTO policy = JsonUtils.objectMapper().readValue(json, PolicyDTO.class);
    PolicyContentDTO.IcebergEncryptionContentDTO content =
        (PolicyContentDTO.IcebergEncryptionContentDTO) policy.content();

    Assertions.assertTrue(content.required());
    Assertions.assertEquals(IcebergEncryptionContent.Enforcement.REPORT, content.enforcement());
    Assertions.assertDoesNotThrow(content::validate);
  }

  @Test
  public void testIcebergEncryptionPolicyRejectsNonCanonicalWireValues() {
    String base =
        "{"
            + "\"name\":\"iceberg-encryption\","
            + "\"policyType\":\"system_iceberg_encryption\","
            + "\"enabled\":true,"
            + "\"content\":{\"schemaVersion\":1,"
            + "\"allowedKeys\":[{\"provider\":\"aws-prod\",\"keyId\":\"key-a\"}],"
            + "\"enforcement\":\"%s\"}}";

    Assertions.assertThrows(
        JsonProcessingException.class,
        () -> JsonUtils.objectMapper().readValue(String.format(base, "REPORT"), PolicyDTO.class));
  }

  @Test
  public void testIcebergEncryptionPolicySupportsNamedProviders() throws JsonProcessingException {
    for (String provider : ImmutableList.of("aws-prod", "openbao-production")) {
      PolicyContentDTO.IcebergEncryptionContentDTO content = encryptionContent(provider);
      PolicyDTO policy =
          PolicyDTO.builder()
              .withName("encryption-" + provider)
              .withPolicyType("system_iceberg_encryption")
              .withEnabled(true)
              .withContent(content)
              .build();

      String json = JsonUtils.objectMapper().writeValueAsString(policy);
      PolicyDTO deserialized = JsonUtils.objectMapper().readValue(json, PolicyDTO.class);
      PolicyContentDTO.IcebergEncryptionContentDTO deserializedContent =
          (PolicyContentDTO.IcebergEncryptionContentDTO) deserialized.content();

      Assertions.assertEquals(provider, deserializedContent.allowedKeys().get(0).getProvider());
      Assertions.assertDoesNotThrow(deserializedContent::validate);
    }
  }

  @Test
  public void testIcebergEncryptionPolicyDoesNotAcceptLegacyIdField()
      throws JsonProcessingException {
    String json =
        "{"
            + "\"name\":\"iceberg-encryption\","
            + "\"policyType\":\"system_iceberg_encryption\","
            + "\"enabled\":true,"
            + "\"content\":{\"schemaVersion\":1,"
            + "\"allowedKeys\":[{\"api\":\"aws-kms\","
            + "\"source\":\"production\",\"id\":\"legacy-key\"}]}}";

    PolicyDTO policy =
        JsonUtils.objectMapper()
            .copy()
            .disable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES)
            .readValue(json, PolicyDTO.class);
    PolicyContentDTO.IcebergEncryptionContentDTO content =
        (PolicyContentDTO.IcebergEncryptionContentDTO) policy.content();

    IllegalArgumentException exception =
        Assertions.assertThrows(IllegalArgumentException.class, content::validate);
    Assertions.assertTrue(exception.getMessage().contains("KMS provider cannot be blank"));
  }

  /**
   * The REST wire format and the persisted storage format are two contracts with independent
   * lifetimes, produced by two different mappers: {@code objectMapper()} over the DTO and {@code
   * anyFieldMapper()} over the domain object. They happen to be identical today. Nothing else
   * asserts that, so a change to either mapper, DTO field, or domain field could silently move one
   * without the other. This pins both against the same literal in one run so the drift is visible.
   */
  @Test
  public void testIcebergEncryptionWireAndStoredFormatsMatch() throws JsonProcessingException {
    PolicyContent domain =
        PolicyContents.icebergEncryption(
            1,
            true,
            ImmutableList.of(
                new KmsReference("aws-prod", "Key-A"),
                new KmsReference("openbao-production", "Key-B")),
            IcebergEncryptionContent.Enforcement.DENY_CREATE);

    String storedJson = JsonUtils.anyFieldMapper().writeValueAsString(domain);
    String wireJson = JsonUtils.objectMapper().writeValueAsString(DTOConverters.toDTO(domain));

    String pinned =
        "{\"schemaVersion\":1,\"required\":true,"
            + "\"allowedKeys\":["
            + "{\"provider\":\"aws-prod\",\"keyId\":\"Key-A\"},"
            + "{\"provider\":\"openbao-production\",\"keyId\":\"Key-B\"}],"
            + "\"enforcement\":\"deny-create\"}";

    Assertions.assertEquals(
        JsonUtils.objectMapper().readTree(pinned), JsonUtils.objectMapper().readTree(storedJson));
    Assertions.assertEquals(
        JsonUtils.objectMapper().readTree(pinned), JsonUtils.objectMapper().readTree(wireJson));

    IcebergEncryptionContent restoredFromStorage =
        JsonUtils.anyFieldMapper().readValue(storedJson, IcebergEncryptionContent.class);
    Assertions.assertEquals(domain, restoredFromStorage);

    PolicyContentDTO.IcebergEncryptionContentDTO restoredFromWire =
        JsonUtils.objectMapper()
            .readValue(wireJson, PolicyContentDTO.IcebergEncryptionContentDTO.class);
    Assertions.assertEquals(2, restoredFromWire.allowedKeys().size());
    Assertions.assertEquals(
        "openbao-production", restoredFromWire.allowedKeys().get(1).getProvider());
    Assertions.assertEquals("Key-B", restoredFromWire.allowedKeys().get(1).getKeyId());
    Assertions.assertDoesNotThrow(restoredFromWire::validate);
  }

  /**
   * The storage mapper rebuilds {@link KmsReference} through a deserializer registered on {@code
   * anyFieldMapper()}, not through field reflection, because the class exposes only a validating
   * two-argument constructor. FIELD/ANY visibility alone is not enough, so an equivalently
   * configured mapper without that module must fail. This pins the registration as load-bearing.
   */
  @Test
  public void testKmsReferenceRequiresRegisteredDeserializer() throws JsonProcessingException {
    String json = "{\"provider\":\"aws-prod\",\"keyId\":\"Key-A\"}";

    ObjectMapper withoutModule =
        new ObjectMapper().setVisibility(PropertyAccessor.FIELD, JsonAutoDetect.Visibility.ANY);
    Assertions.assertThrows(
        InvalidDefinitionException.class, () -> withoutModule.readValue(json, KmsReference.class));

    Assertions.assertEquals(
        new KmsReference("aws-prod", "Key-A"),
        JsonUtils.anyFieldMapper().readValue(json, KmsReference.class));
  }

  private static PolicyContentDTO.IcebergEncryptionContentDTO encryptionContent() {
    return encryptionContent("aws-prod");
  }

  private static PolicyContentDTO.IcebergEncryptionContentDTO encryptionContent(String provider) {
    return PolicyContentDTO.IcebergEncryptionContentDTO.builder()
        .withSchemaVersion(1)
        .withRequired(true)
        .withAllowedKeys(
            ImmutableList.of(
                KmsReferenceDTO.builder().withProvider(provider).withKeyId("Key-A").build()))
        .withEnforcement(IcebergEncryptionContent.Enforcement.DENY_CREATE)
        .build();
  }
}
