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
 * <p>For Directory Users/Groups lists: {@code Local} from {@code idp_*}, {@code Provisioned} from
 * {@code scim_*} (excluding IdP names), {@code JIT} from {@code jit_*} (excluding IdP and SCIM
 * names). For metalake security Users/Groups lists, origin priority is {@code scim_*} →
 * Provisioned, else {@code idp_*} → Local, else {@code jit_*} → JIT; otherwise {@code origin} is
 * omitted ({@code null}).
 *
 * <p>{@link #JIT} is also used for write-time JWT discovery; list SQL maps JIT from {@code
 * jit_user_meta} / {@code jit_group_meta}, not from mere absence in IdP/SCIM.
 */
public enum IdentitySource {
  /** Present in the built-in IdP. */
  LOCAL("Local"),

  /** Present in SCIM. */
  PROVISIONED("Provisioned"),

  /**
   * Present in {@code jit_user_meta} / {@code jit_group_meta} (e.g. first discovered from a JWT
   * claim with no SCIM/IdP record).
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
   * @param originCode Origin code from SQL, or {@code null} when neither SCIM, IdP, nor JIT
   *     matched.
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
