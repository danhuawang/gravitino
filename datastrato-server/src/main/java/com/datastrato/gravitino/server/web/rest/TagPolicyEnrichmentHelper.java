/*
 * Copyright 2026 Datastrato Inc.
 */
package com.datastrato.gravitino.server.web.rest;

import com.datastrato.gravitino.tag.DatastratoTagPolicyBatchHelper;
import com.datastrato.gravitino.tag.DatastratoTagPolicyBatchHelper.TagPolicyBatchResult;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.function.UnaryOperator;
import org.apache.gravitino.MetadataObject;
import org.apache.gravitino.dto.policy.PolicyDTO;
import org.apache.gravitino.dto.tag.TagDTO;
import org.apache.gravitino.dto.util.DTOConverters;
import org.apache.gravitino.meta.PolicyEntity;
import org.apache.gravitino.meta.TagEntity;
import org.apache.gravitino.utils.MetadataObjectUtil;

/** Resolves visible tag and policy inheritance and converts the final entities to REST DTOs. */
final class TagPolicyEnrichmentHelper {

  private TagPolicyEnrichmentHelper() {}

  static Result getVisibleTagPoliciesWithInheritance(
      String metalake,
      List<MetadataObject> objects,
      Map<MetadataObject, Optional<Long>> knownEntityIds,
      UnaryOperator<TagEntity[]> tagFilter,
      UnaryOperator<PolicyEntity[]> policyFilter) {
    if (objects == null || objects.isEmpty()) {
      return new Result(new LinkedHashMap<>(), new LinkedHashMap<>());
    }

    Set<MetadataObject> objectsAndParents = new LinkedHashSet<>();
    for (MetadataObject object : objects) {
      objectsAndParents.add(object);
      objectsAndParents.addAll(MetadataObjectUtil.getParentMetadataObjects(object));
    }
    TagPolicyBatchResult directTagPolicies =
        DatastratoTagPolicyBatchHelper.batchFetchDirectTagPolicies(
            metalake, new ArrayList<>(objectsAndParents), knownEntityIds);
    return mergeTagPoliciesWithInheritance(objects, directTagPolicies, tagFilter, policyFilter);
  }

  static Result mergeTagPoliciesWithInheritance(
      List<MetadataObject> objects,
      TagPolicyBatchResult directTagPolicies,
      UnaryOperator<TagEntity[]> tagFilter,
      UnaryOperator<PolicyEntity[]> policyFilter) {
    Set<String> visibleTagNames =
        visibleTagNames(directTagPolicies.tags(), Objects.requireNonNull(tagFilter));
    Set<String> visiblePolicyNames =
        visiblePolicyNames(directTagPolicies.policies(), Objects.requireNonNull(policyFilter));

    Map<MetadataObject, TagDTO[]> tagsByObject = new LinkedHashMap<>();
    Map<MetadataObject, PolicyDTO[]> policiesByObject = new LinkedHashMap<>();
    for (MetadataObject object : objects) {
      Map<String, TagEntity> mergedTags = new LinkedHashMap<>();
      Map<String, PolicyEntity> mergedPolicies = new LinkedHashMap<>();
      List<MetadataObject> parents = MetadataObjectUtil.getParentMetadataObjects(object);
      for (int i = parents.size() - 1; i >= 0; i--) {
        MetadataObject parent = parents.get(i);
        addTags(
            mergedTags,
            directTagPolicies.tags().getOrDefault(parent, new TagEntity[0]),
            visibleTagNames);
        addPolicies(
            mergedPolicies,
            directTagPolicies.policies().getOrDefault(parent, new PolicyEntity[0]),
            visiblePolicyNames);
      }

      Set<String> directTagNames = new LinkedHashSet<>();
      TagEntity[] directTags = directTagPolicies.tags().getOrDefault(object, new TagEntity[0]);
      Arrays.stream(directTags)
          .filter(tag -> visibleTagNames.contains(tag.name()))
          .forEach(
              tag -> {
                mergedTags.put(tag.name(), tag);
                directTagNames.add(tag.name());
              });
      Set<String> directPolicyNames = new LinkedHashSet<>();
      PolicyEntity[] directPolicies =
          directTagPolicies.policies().getOrDefault(object, new PolicyEntity[0]);
      Arrays.stream(directPolicies)
          .filter(policy -> visiblePolicyNames.contains(policy.name()))
          .forEach(
              policy -> {
                mergedPolicies.put(policy.name(), policy);
                directPolicyNames.add(policy.name());
              });

      tagsByObject.put(
          object,
          mergedTags.values().stream()
              .map(tag -> toTagDTO(tag, !directTagNames.contains(tag.name())))
              .toArray(TagDTO[]::new));
      policiesByObject.put(
          object,
          mergedPolicies.values().stream()
              .map(policy -> toPolicyDTO(policy, !directPolicyNames.contains(policy.name())))
              .toArray(PolicyDTO[]::new));
    }
    return new Result(tagsByObject, policiesByObject);
  }

  private static Set<String> visibleTagNames(
      Map<MetadataObject, TagEntity[]> tagsByObject, UnaryOperator<TagEntity[]> tagFilter) {
    Map<String, TagEntity> uniqueTags = new LinkedHashMap<>();
    tagsByObject
        .values()
        .forEach(
            tags -> Arrays.stream(tags).forEach(tag -> uniqueTags.putIfAbsent(tag.name(), tag)));
    TagEntity[] visibleTags =
        Objects.requireNonNull(tagFilter.apply(uniqueTags.values().toArray(new TagEntity[0])));
    Set<String> visibleNames = new LinkedHashSet<>();
    Arrays.stream(visibleTags).forEach(tag -> visibleNames.add(tag.name()));
    return visibleNames;
  }

  private static Set<String> visiblePolicyNames(
      Map<MetadataObject, PolicyEntity[]> policiesByObject,
      UnaryOperator<PolicyEntity[]> policyFilter) {
    Map<String, PolicyEntity> uniquePolicies = new LinkedHashMap<>();
    policiesByObject
        .values()
        .forEach(
            policies ->
                Arrays.stream(policies)
                    .forEach(policy -> uniquePolicies.putIfAbsent(policy.name(), policy)));
    PolicyEntity[] visiblePolicies =
        Objects.requireNonNull(
            policyFilter.apply(uniquePolicies.values().toArray(new PolicyEntity[0])));
    Set<String> visibleNames = new LinkedHashSet<>();
    Arrays.stream(visiblePolicies).forEach(policy -> visibleNames.add(policy.name()));
    return visibleNames;
  }

  private static void addTags(
      Map<String, TagEntity> mergedTags, TagEntity[] tags, Set<String> visibleTagNames) {
    Arrays.stream(tags)
        .filter(tag -> visibleTagNames.contains(tag.name()))
        .forEach(tag -> mergedTags.put(tag.name(), tag));
  }

  private static void addPolicies(
      Map<String, PolicyEntity> mergedPolicies,
      PolicyEntity[] policies,
      Set<String> visiblePolicyNames) {
    Arrays.stream(policies)
        .filter(policy -> visiblePolicyNames.contains(policy.name()))
        .forEach(policy -> mergedPolicies.put(policy.name(), policy));
  }

  private static TagDTO toTagDTO(TagEntity entity, boolean inherited) {
    return DTOConverters.toDTO(entity, Optional.of(inherited));
  }

  private static PolicyDTO toPolicyDTO(PolicyEntity entity, boolean inherited) {
    return PolicyDTO.builder()
        .withName(entity.name())
        .withComment(entity.comment())
        .withPolicyType(entity.policyType().policyType())
        .withEnabled(entity.enabled())
        .withContent(DTOConverters.toDTO(entity.content()))
        .withAudit(DTOConverters.toDTO(entity.auditInfo()))
        .withInherited(Optional.of(inherited))
        .build();
  }

  static final class Result {
    private final Map<MetadataObject, TagDTO[]> tags;
    private final Map<MetadataObject, PolicyDTO[]> policies;

    private Result(Map<MetadataObject, TagDTO[]> tags, Map<MetadataObject, PolicyDTO[]> policies) {
      this.tags = tags;
      this.policies = policies;
    }

    Map<MetadataObject, TagDTO[]> tags() {
      return tags;
    }

    Map<MetadataObject, PolicyDTO[]> policies() {
      return policies;
    }
  }
}
