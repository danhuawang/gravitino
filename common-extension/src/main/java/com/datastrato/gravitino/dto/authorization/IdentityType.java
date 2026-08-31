/*
 * Copyright 2026 Datastrato Inc.
 */
package com.datastrato.gravitino.dto.authorization;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonValue;

/** Identity type for lookups before adding a user or group into a metalake. */
public enum IdentityType {
  /** Built-in IdP identity. */
  LOCAL("local"),
  /** Provisioned (SCIM) identity. */
  PROVISIONED("provisioned");

  private final String value;

  IdentityType(String value) {
    this.value = value;
  }

  /**
   * @return JSON value ({@code local} or {@code provisioned}).
   */
  @JsonValue
  public String value() {
    return value;
  }

  /**
   * Parses the JSON value.
   *
   * @param value {@code local} or {@code provisioned}.
   * @return The matching identity type.
   */
  @JsonCreator
  public static IdentityType fromValue(String value) {
    for (IdentityType type : values()) {
      if (type.value.equalsIgnoreCase(value)) {
        return type;
      }
    }
    throw new IllegalArgumentException("Unknown identity type: " + value);
  }
}
