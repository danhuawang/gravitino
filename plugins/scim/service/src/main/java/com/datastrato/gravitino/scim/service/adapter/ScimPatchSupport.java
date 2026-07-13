/*
 * Copyright 2026 Datastrato Pvt Ltd.
 * This software is licensed under the Apache License version 2.
 */

package com.datastrato.gravitino.scim.service.adapter;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.apache.directory.scim.spec.exception.ResourceException;
import org.apache.directory.scim.spec.patch.PatchOperation;

/** Parses supported SCIM PATCH operations for repository adapters. */
public final class ScimPatchSupport {

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
}
