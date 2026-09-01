/*
 * Copyright 2026 Datastrato Pvt Ltd.
 */

package com.datastrato.gravitino.scim.v2.service.adapter;

import com.google.common.collect.Lists;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import org.apache.commons.lang3.StringUtils;
import org.apache.directory.scim.spec.exception.ResourceException;
import org.apache.directory.scim.spec.filter.AttributeComparisonExpression;
import org.apache.directory.scim.spec.filter.CompareOperator;
import org.apache.directory.scim.spec.filter.FilterExpression;
import org.apache.directory.scim.spec.filter.ValuePathExpression;
import org.apache.directory.scim.spec.patch.PatchOperation;
import org.apache.directory.scim.spec.resources.GroupMembership;

/** Parses supported SCIM PATCH operations for repository adapters. */
public final class ScimPatchSupport {

  private static final Set<String> IGNORED_PATHLESS_GROUP_KEYS =
      Set.of("id", "schemas", "meta", "urn:ietf:params:scim:schemas:core:2.0:Group");

  private ScimPatchSupport() {}

  /** Kind of a Group PATCH operation after path/value classification. */
  public enum GroupPatchKind {
    /** Add/remove/replace memberships. */
    MEMBERS,
    /** Replace {@code externalId}. */
    EXTERNAL_ID,
    /** Replace {@code displayName} (immutable in Gravitino). */
    DISPLAY_NAME
  }

  /** Parsed Group PATCH operation. */
  public static final class GroupPatchOperation {
    private final GroupPatchKind kind;
    private final List<GroupMembership> members;
    private final boolean membersFromPathFilter;
    private final List<GroupMembership> replacementMembers;
    private final String externalId;
    private final String displayName;

    private GroupPatchOperation(
        GroupPatchKind kind,
        List<GroupMembership> members,
        boolean membersFromPathFilter,
        List<GroupMembership> replacementMembers,
        String externalId,
        String displayName) {
      this.kind = kind;
      this.members = members;
      this.membersFromPathFilter = membersFromPathFilter;
      this.replacementMembers = replacementMembers;
      this.externalId = externalId;
      this.displayName = displayName;
    }

    /** Returns the classified patch kind. */
    public GroupPatchKind kind() {
      return kind;
    }

    /** Returns memberships for {@link GroupPatchKind#MEMBERS}. */
    public List<GroupMembership> members() {
      return members;
    }

    /**
     * Returns whether member ids came from a path filter such as {@code members[value eq "..."]}.
     *
     * <p>Path-filtered member ops are scoped to the matched entries only. {@code REPLACE} updates
     * the matched row in place (old id from the path, new id from the operation value).
     */
    public boolean membersFromPathFilter() {
      return membersFromPathFilter;
    }

    /**
     * Returns replacement memberships from the PATCH value for path-filtered {@code REPLACE}.
     *
     * @return empty when not a path-filtered replace with a value body
     */
    public List<GroupMembership> replacementMembers() {
      return replacementMembers;
    }

    /** Returns the replacement external id for {@link GroupPatchKind#EXTERNAL_ID}. */
    public String externalId() {
      return externalId;
    }

    /** Returns the replacement display name for {@link GroupPatchKind#DISPLAY_NAME}. */
    public String displayName() {
      return displayName;
    }

    static GroupPatchOperation members(List<GroupMembership> members) {
      return members(members, false, List.of());
    }

    static GroupPatchOperation members(
        List<GroupMembership> members,
        boolean fromPathFilter,
        List<GroupMembership> replacementMembers) {
      return new GroupPatchOperation(
          GroupPatchKind.MEMBERS,
          members,
          fromPathFilter,
          replacementMembers == null ? List.of() : replacementMembers,
          null,
          null);
    }

    static GroupPatchOperation externalId(String externalId) {
      return new GroupPatchOperation(
          GroupPatchKind.EXTERNAL_ID, List.of(), false, List.of(), externalId, null);
    }

    static GroupPatchOperation displayName(String displayName) {
      return new GroupPatchOperation(
          GroupPatchKind.DISPLAY_NAME, List.of(), false, List.of(), null, displayName);
    }
  }

  /**
   * Parses User PATCH operations and returns the {@code active} value.
   *
   * @param patchOperations SCIM patch operations
   * @return parsed active flag when present in patch operations
   */
  public static Optional<Boolean> parseUserActive(List<PatchOperation> patchOperations)
      throws ResourceException {
    Optional<Boolean> active = Optional.empty();
    for (PatchOperation operation : patchOperations) {
      validateUserActivePath(operation);
      Optional<Boolean> operationValue = parseActiveValue(operation.getValue());
      if (operationValue.isPresent()) {
        active = operationValue;
      }
    }
    return active;
  }

  /**
   * Classifies and parses a Group PATCH operation into one or more concrete updates.
   *
   * <p>Supports:
   *
   * <ul>
   *   <li>{@code members} add/replace with a value array
   *   <li>{@code members[value eq "..."]} path filters (REMOVE/ADD; REPLACE updates that row)
   *   <li>path-less replace of {@code externalId} / {@code displayName} / {@code members} (Okta
   *       Group Push may send several of these in one path-less {@code value} map)
   *   <li>path {@code externalId} / {@code displayName} replace
   * </ul>
   *
   * @param operation SCIM patch operation
   * @return classified patch operations in application order
   */
  public static List<GroupPatchOperation> parseGroupPatches(PatchOperation operation)
      throws ResourceException {
    String pathAttribute = pathAttributeName(operation);
    List<GroupMembership> filteredMembers = extractMembersFromPathFilter(operation);
    if (!filteredMembers.isEmpty()) {
      List<GroupMembership> replacement = List.of();
      if (operation.getValue() != null) {
        replacement = extractGroupMembers(operation.getValue());
      }
      return List.of(GroupPatchOperation.members(filteredMembers, true, replacement));
    }

    if (pathAttribute == null) {
      return parsePathlessGroupPatch(operation.getValue());
    }
    if ("members".equalsIgnoreCase(pathAttribute)) {
      return List.of(GroupPatchOperation.members(extractGroupMembers(operation.getValue())));
    }
    if ("externalId".equalsIgnoreCase(pathAttribute)) {
      return List.of(
          GroupPatchOperation.externalId(requireStringValue(operation.getValue(), "externalId")));
    }
    if ("displayName".equalsIgnoreCase(pathAttribute)) {
      return List.of(
          GroupPatchOperation.displayName(requireStringValue(operation.getValue(), "displayName")));
    }
    throw new ResourceException(400, "Unsupported Group PATCH path: " + pathAttribute);
  }

  /**
   * Classifies and parses a Group PATCH operation that expands to exactly one update.
   *
   * <p>Prefer {@link #parseGroupPatches(PatchOperation)} when the client may send multiple
   * attributes in one path-less replace (for example Okta Group Push).
   *
   * @param operation SCIM patch operation
   * @return classified patch operation
   */
  public static GroupPatchOperation parseGroupPatch(PatchOperation operation)
      throws ResourceException {
    List<GroupPatchOperation> parsed = parseGroupPatches(operation);
    if (parsed.size() != 1) {
      throw new ResourceException(
          400,
          "Group PATCH expands to "
              + parsed.size()
              + " updates; use parseGroupPatches for multi-attribute values");
    }
    return parsed.get(0);
  }

  /**
   * Parses a Group PATCH operation for {@code members} updates.
   *
   * @param operation SCIM patch operation
   * @return parsed group memberships
   * @deprecated use {@link #parseGroupPatches(PatchOperation)}
   */
  @Deprecated
  public static List<GroupMembership> parseGroupMembers(PatchOperation operation)
      throws ResourceException {
    List<GroupPatchOperation> parsed = parseGroupPatches(operation);
    if (parsed.size() != 1 || parsed.get(0).kind() != GroupPatchKind.MEMBERS) {
      throw new ResourceException(400, "PATCH on Groups supports members only");
    }
    return parsed.get(0).members();
  }

  private static List<GroupPatchOperation> parsePathlessGroupPatch(Object value)
      throws ResourceException {
    if (value == null) {
      throw new ResourceException(400, "Group PATCH value is required when path is omitted");
    }
    // Switch pattern matching (`case X when`) needs Java 21; flatten with instanceof patterns.
    if (value instanceof List<?> list) {
      return List.of(GroupPatchOperation.members(extractGroupMembers(list)));
    }
    if (value instanceof Map<?, ?> map) {
      return parsePathlessGroupMap(map);
    }
    throw new ResourceException(400, "Unsupported Group PATCH value");
  }

  /**
   * Parses a path-less Group PATCH {@code value} object.
   *
   * <p>Okta Group Push often sends a resource fragment such as {@code {externalId, displayName,
   * members, id}}. Identity attributes are applied before memberships. Resource meta keys ({@code
   * id}, {@code schemas}, {@code meta}) are ignored.
   */
  private static List<GroupPatchOperation> parsePathlessGroupMap(Map<?, ?> map)
      throws ResourceException {
    List<GroupPatchOperation> ops = new ArrayList<>();

    if (map.containsKey("externalId")) {
      ops.add(
          GroupPatchOperation.externalId(requireStringValue(map.get("externalId"), "externalId")));
    }
    if (map.containsKey("displayName")) {
      ops.add(
          GroupPatchOperation.displayName(
              requireStringValue(map.get("displayName"), "displayName")));
    }
    if (map.containsKey("members")) {
      ops.add(GroupPatchOperation.members(extractGroupMembers(map.get("members"))));
    } else if (map.containsKey("value")
        && !map.containsKey("externalId")
        && !map.containsKey("displayName")) {
      // Single membership object: {"value":"...","display":"..."}.
      ops.add(GroupPatchOperation.members(extractGroupMembers(map)));
    }

    if (ops.isEmpty()) {
      throw new ResourceException(400, "Unsupported Group PATCH value");
    }

    // Reject unknown non-meta keys so typos do not silently drop attributes.
    for (Object rawKey : map.keySet()) {
      if (!(rawKey instanceof String key)) {
        throw new ResourceException(400, "Unsupported Group PATCH value key type");
      }
      if (!IGNORED_PATHLESS_GROUP_KEYS.contains(key)
          && !"externalId".equals(key)
          && !"displayName".equals(key)
          && !"members".equals(key)
          && !"value".equals(key)
          && !"display".equals(key)) {
        throw new ResourceException(400, "Unsupported Group PATCH value attribute: " + key);
      }
    }
    return ops;
  }

  private static void validateUserActivePath(PatchOperation operation) throws ResourceException {
    if (operation.getPath() == null) {
      return;
    }
    if (operation.getPath().getValuePathExpression() == null
        || operation.getPath().getValuePathExpression().getAttributePath() == null) {
      return;
    }
    if (!"active"
        .equalsIgnoreCase(
            operation.getPath().getValuePathExpression().getAttributePath().getAttributeName())) {
      throw new ResourceException(400, "PATCH on Users supports active only");
    }
  }

  private static String pathAttributeName(PatchOperation operation) {
    if (operation.getPath() == null || operation.getPath().getValuePathExpression() == null) {
      return null;
    }
    ValuePathExpression valuePath = operation.getPath().getValuePathExpression();
    if (valuePath.getAttributePath() == null) {
      return null;
    }
    return valuePath.getAttributePath().getAttributeName();
  }

  /**
   * Extracts member ids from paths such as {@code members[value eq "123"]}.
   *
   * @param operation SCIM patch operation
   * @return memberships derived from the path value filter, or empty when absent
   */
  private static List<GroupMembership> extractMembersFromPathFilter(PatchOperation operation)
      throws ResourceException {
    if (operation.getPath() == null || operation.getPath().getValuePathExpression() == null) {
      return List.of();
    }
    ValuePathExpression valuePath = operation.getPath().getValuePathExpression();
    if (valuePath.getAttributePath() == null
        || !"members".equalsIgnoreCase(valuePath.getAttributePath().getAttributeName())
        || valuePath.getAttributeExpression() == null) {
      return List.of();
    }

    FilterExpression expression = valuePath.getAttributeExpression();
    if (!(expression instanceof AttributeComparisonExpression comparison)) {
      throw new ResourceException(400, "Unsupported members value filter");
    }
    // SCIMple rewrites members[value eq "..."] so attributeName=members, subAttributeName=value.
    String filterAttribute =
        comparison.getAttributePath().getSubAttributeName() != null
            ? comparison.getAttributePath().getSubAttributeName()
            : comparison.getAttributePath().getAttributeName();
    if (!"value".equalsIgnoreCase(filterAttribute)) {
      throw new ResourceException(400, "Unsupported members filter attribute: " + filterAttribute);
    }
    if (comparison.getOperation() != CompareOperator.EQ) {
      throw new ResourceException(400, "Only eq is supported in members value filters");
    }
    Object compareValue = comparison.getCompareValue();
    if (!(compareValue instanceof String stringValue) || StringUtils.isBlank(stringValue)) {
      throw new ResourceException(400, "members value filter requires a non-blank string");
    }
    return List.of(new GroupMembership().setValue(stringValue));
  }

  private static Optional<Boolean> parseActiveValue(Object value) throws ResourceException {
    if (value == null) {
      return Optional.empty();
    }
    if (value instanceof Boolean boolValue) {
      return Optional.of(boolValue);
    }
    if (value instanceof String stringValue) {
      if ("true".equalsIgnoreCase(stringValue)) {
        return Optional.of(true);
      }
      if ("false".equalsIgnoreCase(stringValue)) {
        return Optional.of(false);
      }
      throw new ResourceException(400, "PATCH active value must be a boolean");
    }
    if (value instanceof Map<?, ?> mapValue && mapValue.containsKey("active")) {
      return parseActiveValue(mapValue.get("active"));
    }
    throw new ResourceException(400, "PATCH active value must be a boolean");
  }

  private static List<GroupMembership> extractGroupMembers(Object value) throws ResourceException {
    if (value == null) {
      return List.of();
    }
    if (value instanceof List<?> listValue) {
      List<GroupMembership> members = Lists.newArrayList();
      for (Object item : listValue) {
        members.add(toGroupMembership(item));
      }
      return members;
    }
    if (value instanceof Map<?, ?> mapValue) {
      return List.of(toGroupMembership(mapValue));
    }
    if (value instanceof GroupMembership membership) {
      return List.of(membership);
    }
    throw new ResourceException(400, "Unsupported members PATCH value");
  }

  private static GroupMembership toGroupMembership(Object item) throws ResourceException {
    if (item instanceof GroupMembership membership) {
      return membership;
    }
    if (item instanceof Map<?, ?> mapValue) {
      if (!mapValue.containsKey("value")) {
        throw new ResourceException(400, "Group member object requires value");
      }
      Object rawValue = mapValue.get("value");
      if (!(rawValue instanceof String stringValue) || StringUtils.isBlank(stringValue)) {
        throw new ResourceException(400, "Group member value must be a non-blank string");
      }
      GroupMembership membership = new GroupMembership().setValue(stringValue);
      Object display = mapValue.get("display");
      if (display instanceof String displayValue) {
        membership.setDisplay(displayValue);
      }
      return membership;
    }
    throw new ResourceException(400, "Unsupported group member value");
  }

  private static String requireStringValue(Object value, String attribute)
      throws ResourceException {
    if (value instanceof String stringValue) {
      return stringValue;
    }
    throw new ResourceException(400, attribute + " PATCH value must be a string");
  }
}
