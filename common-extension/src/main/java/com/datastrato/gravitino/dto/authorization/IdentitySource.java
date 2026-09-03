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
 * <p>For Directory and metalake security Users: {@code Local} from {@code idp_user_meta}, {@code
 * Provisioned} from {@code scim_user_meta}, {@code JIT} when present in neither. Directory Groups
 * use the same three-way split against group identity stores.
 */
public enum IdentitySource {
  /** Present in the built-in IdP. */
  LOCAL("Local"),

  /** Present in SCIM (or otherwise not in the built-in IdP for metalake pages). */
  PROVISIONED("Provisioned"),

  /** Present in metalake tables but not in the corresponding IdP or SCIM identity store. */
  JIT("JIT");

  /** SQL / PO origin code for Local. */
  public static final int ORIGIN_CODE_LOCAL = 1;

  /** SQL / PO origin code for Provisioned. */
  public static final int ORIGIN_CODE_PROVISIONED = 0;

  /** SQL / PO origin code for JIT. */
  public static final int ORIGIN_CODE_JIT = 2;

  private final String value;

  IdentitySource(String value) {
    this.value = value;
  }

  /**
   * @return JSON / UI value ({@code Local}, {@code Provisioned}, or {@code JIT}).
   */
  @JsonValue
  public String value() {
    return value;
  }

  /**
   * Parses the JSON / UI value.
   *
   * @param value {@code Local}, {@code Provisioned}, or {@code JIT}.
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

  /**
   * Derives identity source from Directory Groups SQL origin codes.
   *
   * @param originCode {@link #ORIGIN_CODE_LOCAL}, {@link #ORIGIN_CODE_PROVISIONED}, or {@link
   *     #ORIGIN_CODE_JIT}.
   * @return Matching identity source.
   */
  public static IdentitySource fromOriginCode(int originCode) {
    switch (originCode) {
      case ORIGIN_CODE_LOCAL:
        return LOCAL;
      case ORIGIN_CODE_PROVISIONED:
        return PROVISIONED;
      case ORIGIN_CODE_JIT:
        return JIT;
      default:
        throw new IllegalArgumentException("Unknown origin code: " + originCode);
    }
  }
}
