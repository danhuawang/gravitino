/*
 * Copyright 2026 Datastrato Pvt Ltd.
 * This software is licensed under the Apache License version 2.
 */

package com.datastrato.gravitino.scim.dto;

import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.AccessLevel;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.EqualsAndHashCode;
import lombok.Getter;
import lombok.ToString;

/** SCIM token metadata returned by create and rotate APIs. */
@Getter
@EqualsAndHashCode
@ToString
@Builder(setterPrefix = "with")
@AllArgsConstructor(access = AccessLevel.PRIVATE)
public class ScimTokenDTO {

  @JsonProperty("metalake")
  private final String metalake;

  @JsonProperty("tokenName")
  private final String tokenName;

  @JsonProperty("tokenValue")
  @ToString.Exclude
  private final String tokenValue;

  @JsonProperty("expiresAt")
  private final long expiresAt;

  /** Default constructor for Jackson deserialization. */
  public ScimTokenDTO() {
    this(null, null, null, 0L);
  }

  /**
   * Creates a create/rotate response DTO.
   *
   * @param metalake target metalake name
   * @param tokenName token name
   * @param tokenValue one-time plaintext bearer token
   * @param expiresAt expiry epoch millis; {@code 0} means no expiry
   * @return response DTO
   */
  public static ScimTokenDTO of(
      String metalake, String tokenName, String tokenValue, long expiresAt) {
    return ScimTokenDTO.builder()
        .withMetalake(metalake)
        .withTokenName(tokenName)
        .withTokenValue(tokenValue)
        .withExpiresAt(expiresAt)
        .build();
  }
}
