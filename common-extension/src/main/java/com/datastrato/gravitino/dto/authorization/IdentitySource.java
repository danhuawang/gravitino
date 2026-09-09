/*
 * Copyright 2026 Datastrato Inc.
 */
package com.datastrato.gravitino.dto.authorization;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonValue;
import javax.annotation.Nullable;

/**
 * Source of a metalake user or group identity for the security UI. Serialized as JSON {@code
 * origin}.
 *
 * <p>For Directory Users/Groups lists: {@code Local} from IdP tables, {@code Provisioned} from SCIM
 * tables (Directory lists do not include metalake-only rows). For metalake security Users/Groups
 * lists: {@code Local} / {@code Provisioned} when present in the corresponding identity store;
 * otherwise {@code origin} is omitted ({@code null}) rather than inferred as {@code JIT}.
 *
 * <p>{@link #JIT} remains for write-time JWT discovery and explicit responses; list SQL no longer
 * maps "neither IdP nor SCIM" to JIT.
 */
public enum IdentitySource {
  /** Present in the built-in IdP. */
  LOCAL("Local"),

  /** Present in SCIM (or otherwise not in the built-in IdP for metalake pages). */
  PROVISIONED("Provisioned"),

  /**
   * Reserved for identities first discovered from a JWT claim with no SCIM record. Not inferred by
   * metalake or Directory list SQL from mere absence in IdP/SCIM.
   */
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
   * Derives identity source from a non-null Directory / metalake SQL origin code.
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

  /**
   * Derives identity source from a nullable SQL origin code.
   *
   * @param originCode Origin code from SQL, or {@code null} when neither IdP nor SCIM matched.
   * @return Matching identity source, or {@code null} when {@code originCode} is null.
   */
  @Nullable
  public static IdentitySource fromOriginCodeOrNull(@Nullable Integer originCode) {
    if (originCode == null) {
      return null;
    }
    return fromOriginCode(originCode);
  }

  /**
   * @return SQL / PO origin code for this identity source.
   */
  public int toOriginCode() {
    switch (this) {
      case LOCAL:
        return ORIGIN_CODE_LOCAL;
      case PROVISIONED:
        return ORIGIN_CODE_PROVISIONED;
      case JIT:
        return ORIGIN_CODE_JIT;
      default:
        throw new IllegalStateException("Unknown identity source: " + this);
    }
  }
}
