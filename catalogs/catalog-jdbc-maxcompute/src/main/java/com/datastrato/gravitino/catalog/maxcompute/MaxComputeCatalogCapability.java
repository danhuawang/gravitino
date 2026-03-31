/*
 * Copyright 2026 Datastrato Pvt Ltd.
 * This software is licensed under the Apache License version 2.
 */

package com.datastrato.gravitino.catalog.maxcompute;

import java.util.Set;
import org.apache.gravitino.connector.capability.Capability;
import org.apache.gravitino.connector.capability.CapabilityResult;

/**
 * Capability for MaxCompute catalog.
 *
 * <p>MaxCompute has the following limitations:
 *
 * <ul>
 *   <li>Schema/Table names are case-insensitive
 *   <li>Column default values have limited support (only constant values, no functions like
 *       GETDATE())
 *   <li>Table names must start with a letter or underscore and can only contain letters, digits,
 *       and underscores
 *   <li>Maximum table name length is 128 bytes
 * </ul>
 */
public class MaxComputeCatalogCapability implements Capability {

  /**
   * Regular expression for MaxCompute identifier names.
   *
   * <p>Rules:
   *
   * <ul>
   *   <li>Must start with a letter (a-z, A-Z) or underscore (_)
   *   <li>Can contain letters, digits (0-9), and underscores
   *   <li>Maximum length is 128 characters
   * </ul>
   */
  private static final String MAXCOMPUTE_NAME_PATTERN = "^[a-zA-Z_][a-zA-Z0-9_]{0,127}$";

  /** Reserved schema names in MaxCompute that cannot be used for user-defined schemas. */
  private static final Set<String> MAXCOMPUTE_RESERVED_WORDS = Set.of("information_schema");

  @Override
  public CapabilityResult specificationOnName(Scope scope, String name) {
    // Check name pattern
    if (!name.matches(MAXCOMPUTE_NAME_PATTERN)) {
      return CapabilityResult.unsupported(
          String.format(
              "The %s name '%s' is illegal. MaxCompute names must start with a letter or underscore, "
                  + "contain only letters, digits, and underscores, and be at most 128 characters long.",
              scope, name));
    }

    // Check reserved words for schema
    if (scope == Scope.SCHEMA && MAXCOMPUTE_RESERVED_WORDS.contains(name.toLowerCase())) {
      return CapabilityResult.unsupported(
          String.format("The %s name '%s' is reserved and cannot be used.", scope, name));
    }

    return CapabilityResult.SUPPORTED;
  }

  /**
   * MaxCompute table/schema names are case-insensitive. Names are stored in lowercase internally.
   *
   * @param scope The scope of the capability.
   * @return The capability result indicating case-insensitivity.
   */
  @Override
  public CapabilityResult caseSensitiveOnName(Scope scope) {
    return CapabilityResult.unsupported(
        String.format("MaxCompute does not support case-sensitive %s names.", scope));
  }

  /**
   * MaxCompute has limited support for column default values. Only constant values are supported,
   * function-based defaults (like GETDATE()) are not supported.
   *
   * @return The capability result for column default value support.
   */
  @Override
  public CapabilityResult columnDefaultValue() {
    // MaxCompute supports constant default values but not function-based defaults
    return CapabilityResult.SUPPORTED;
  }
}
