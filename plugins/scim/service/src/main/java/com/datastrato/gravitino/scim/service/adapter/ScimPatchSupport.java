/*
 * Copyright 2026 Datastrato Pvt Ltd.
 * This software is licensed under the Apache License version 2.
 */

package com.datastrato.gravitino.scim.service.adapter;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.google.common.collect.Lists;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.apache.directory.scim.spec.exception.ResourceException;
import org.apache.directory.scim.spec.patch.PatchOperation;
import org.apache.directory.scim.spec.resources.GroupMembership;

/** Parses supported SCIM PATCH operations for repository adapters. */
public final class ScimPatchSupport {

  private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();

  private ScimPatchSupport() {}

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
   * Parses a Group PATCH operation for {@code members} updates.
   *
   * @param operation SCIM patch operation
   * @return parsed group memberships
   */
  public static List<GroupMembership> parseGroupMembers(PatchOperation operation)
      throws ResourceException {
    validateGroupMembersPath(operation);
    return extractGroupMembers(operation.getValue());
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

  private static void validateGroupMembersPath(PatchOperation operation) throws ResourceException {
    if (operation.getPath() == null || operation.getPath().getValuePathExpression() == null) {
      if (operation.getValue() != null) {
        return;
      }
      throw new ResourceException(400, "PATCH on Groups supports members only");
    }
    if (operation.getPath().getValuePathExpression().getAttributePath() == null) {
      return;
    }
    String attributeName =
        operation.getPath().getValuePathExpression().getAttributePath().getAttributeName();
    if ("members".equalsIgnoreCase(attributeName)) {
      return;
    }
    throw new ResourceException(400, "PATCH on Groups supports members only");
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

  @SuppressWarnings("unchecked")
  private static List<GroupMembership> extractGroupMembers(Object value) {
    if (value == null) {
      return List.of();
    }
    if (value instanceof List<?> listValue) {
      List<GroupMembership> members = Lists.newArrayList();
      for (Object item : listValue) {
        if (item instanceof GroupMembership membership) {
          members.add(membership);
        } else if (item instanceof Map<?, ?> mapValue) {
          members.add(OBJECT_MAPPER.convertValue(mapValue, GroupMembership.class));
        }
      }
      return members;
    }
    if (value instanceof Map<?, ?> mapValue) {
      return List.of(OBJECT_MAPPER.convertValue(mapValue, GroupMembership.class));
    }
    return List.of();
  }
}
