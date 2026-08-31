/*
 * Copyright 2026 Datastrato Inc.
 */
package com.datastrato.gravitino.server.web.rest;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.datastrato.gravitino.tag.DatastratoTagPolicyBatchHelper.TagPolicyBatchResult;
import java.time.Instant;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;
import org.apache.gravitino.MetadataObject;
import org.apache.gravitino.MetadataObjects;
import org.apache.gravitino.dto.policy.PolicyDTO;
import org.apache.gravitino.dto.tag.TagDTO;
import org.apache.gravitino.meta.AuditInfo;
import org.apache.gravitino.meta.PolicyEntity;
import org.apache.gravitino.meta.TagEntity;
import org.apache.gravitino.policy.Policy;
import org.apache.gravitino.policy.PolicyContents;
import org.apache.gravitino.utils.MetadataObjectUtil;
import org.apache.gravitino.utils.NamespaceUtil;
import org.junit.jupiter.api.Test;

public class TestTagPolicyEnrichmentHelper {

  @Test
  public void testInheritancePrecedenceAndDeduplicatedFiltering() {
    MetadataObject table =
        MetadataObjects.of(List.of("catalog", "schema", "table"), MetadataObject.Type.TABLE);
    List<MetadataObject> parents = MetadataObjectUtil.getParentMetadataObjects(table);
    MetadataObject schema = parents.get(0);
    MetadataObject catalog = parents.get(1);

    Map<MetadataObject, TagEntity[]> tags = new LinkedHashMap<>();
    tags.put(table, new TagEntity[] {tag(1L, "shared", "direct"), tag(2L, "hidden", "direct")});
    tags.put(
        schema, new TagEntity[] {tag(3L, "shared", "schema"), tag(4L, "schema-tag", "schema")});
    tags.put(catalog, new TagEntity[] {tag(5L, "shared", "catalog")});

    Map<MetadataObject, PolicyEntity[]> policies = new LinkedHashMap<>();
    policies.put(table, new PolicyEntity[] {policy(1L, "shared-policy", "direct")});
    policies.put(
        schema,
        new PolicyEntity[] {
          policy(2L, "shared-policy", "schema"), policy(3L, "schema-policy", "schema")
        });
    policies.put(catalog, new PolicyEntity[] {policy(4L, "shared-policy", "catalog")});

    AtomicInteger tagFilterCalls = new AtomicInteger();
    AtomicInteger policyFilterCalls = new AtomicInteger();
    TagPolicyEnrichmentHelper.Result result =
        TagPolicyEnrichmentHelper.mergeTagPoliciesWithInheritance(
            List.of(table),
            new TagPolicyBatchResult(tags, policies),
            uniqueTags -> {
              tagFilterCalls.incrementAndGet();
              assertEquals(3, uniqueTags.length);
              return Arrays.stream(uniqueTags)
                  .filter(tag -> !tag.name().equals("hidden"))
                  .toArray(TagEntity[]::new);
            },
            uniquePolicies -> {
              policyFilterCalls.incrementAndGet();
              assertEquals(2, uniquePolicies.length);
              return uniquePolicies;
            });

    assertEquals(1, tagFilterCalls.get());
    assertEquals(1, policyFilterCalls.get());
    Map<String, TagDTO> mergedTags = new LinkedHashMap<>();
    Arrays.stream(result.tags().get(table)).forEach(tag -> mergedTags.put(tag.name(), tag));
    assertEquals(Set.of("shared", "schema-tag"), mergedTags.keySet());
    assertEquals("direct", mergedTags.get("shared").comment());
    assertFalse(mergedTags.get("shared").inherited().orElseThrow());
    assertTrue(mergedTags.get("schema-tag").inherited().orElseThrow());

    Map<String, PolicyDTO> mergedPolicies = new LinkedHashMap<>();
    Arrays.stream(result.policies().get(table))
        .forEach(policy -> mergedPolicies.put(policy.name(), policy));
    assertEquals(Set.of("shared-policy", "schema-policy"), mergedPolicies.keySet());
    assertEquals("direct", mergedPolicies.get("shared-policy").comment());
    assertFalse(mergedPolicies.get("shared-policy").inherited().orElseThrow());
    assertTrue(mergedPolicies.get("schema-policy").inherited().orElseThrow());
  }

  private TagEntity tag(long id, String name, String comment) {
    return TagEntity.builder()
        .withId(id)
        .withName(name)
        .withNamespace(NamespaceUtil.ofTag("metalake"))
        .withComment(comment)
        .withAuditInfo(
            AuditInfo.builder().withCreator("creator").withCreateTime(Instant.now()).build())
        .build();
  }

  private PolicyEntity policy(long id, String name, String comment) {
    return PolicyEntity.builder()
        .withId(id)
        .withName(name)
        .withNamespace(NamespaceUtil.ofPolicy("metalake"))
        .withPolicyType(Policy.BuiltInType.CUSTOM)
        .withComment(comment)
        .withContent(
            PolicyContents.custom(
                Map.of("rule", "value"), Set.of(MetadataObject.Type.TABLE), Map.of()))
        .withAuditInfo(
            AuditInfo.builder().withCreator("creator").withCreateTime(Instant.now()).build())
        .build();
  }
}
