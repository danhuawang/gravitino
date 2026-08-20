/*
 * Copyright 2026 Datastrato Pvt Ltd.
 * This software is licensed under the Apache License version 2.
 */
package com.datastrato.gravitino.dto.authorization;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonValue;
import org.apache.commons.lang3.StringUtils;

/**
 * Source of a metalake user or group identity for the security UI. Serialized as JSON {@code
 * origin}: {@code Provisioned} when {@code externalId} is set, otherwise {@code Local}.
 */
public enum IdentitySource {
  /** Created in Gravitino (no {@code externalId}). */
  LOCAL("Local"),

  /** Provisioned from an IdP / SCIM ({@code externalId} present). */
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
   * Derives identity source from {@code externalId}.
   *
   * @param externalId External identifier; blank means local.
   * @return {@link #LOCAL} or {@link #PROVISIONED}.
   */
  public static IdentitySource fromExternalId(String externalId) {
    return StringUtils.isBlank(externalId) ? LOCAL : PROVISIONED;
  }
}
