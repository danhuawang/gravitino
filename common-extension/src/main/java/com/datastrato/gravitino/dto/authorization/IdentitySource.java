/*
 * Copyright 2026 Datastrato Inc.
 */
package com.datastrato.gravitino.dto.authorization;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonValue;

/**
 * Source of a metalake user or group identity for the security UI. Serialized as JSON {@code
 * origin}.
 *
 * <p>{@code Local} when the name exists in the built-in IdP ({@code idp_user_meta} / {@code
 * idp_group_meta}), otherwise {@code Provisioned}.
 */
public enum IdentitySource {
  /** Present in the built-in IdP. */
  LOCAL("Local"),

  /** Not present in the built-in IdP (typically SCIM-provisioned). */
  PROVISIONED("Provisioned");

  private final String value;

  IdentitySource(String value) {
    this.value = value;
  }

  /**
   * @return JSON / UI value ({@code Local} or {@code Provisioned}).
   */
  @JsonValue
  public String value() {
    return value;
  }

  /**
   * Parses the JSON / UI value.
   *
   * @param value {@code Local} or {@code Provisioned}.
   * @return The matching identity source.
   */
  @JsonCreator
  public static IdentitySource fromValue(String value) {
    for (IdentitySource source : values()) {
      if (source.value.equals(value)) {
        return source;
      }
    }
    throw new IllegalArgumentException("Unknown identity source: " + value);
  }

  /**
   * Derives identity source from built-in IdP membership.
   *
   * @param inBuiltInIdp {@code true} when the name exists in {@code idp_user_meta} or {@code
   *     idp_group_meta}.
   * @return {@link #LOCAL} or {@link #PROVISIONED}.
   */
  public static IdentitySource fromIdpMembership(boolean inBuiltInIdp) {
    return inBuiltInIdp ? LOCAL : PROVISIONED;
  }
}
