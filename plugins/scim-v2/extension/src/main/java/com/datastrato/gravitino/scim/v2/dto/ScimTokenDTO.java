/*
 * Copyright 2026 Datastrato Inc.
 */

package com.datastrato.gravitino.scim.v2.dto;

import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.AccessLevel;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.EqualsAndHashCode;
import lombok.Getter;
import lombok.ToString;

/** SCIM v2 token metadata returned by create and rotate APIs. */
@Getter
@EqualsAndHashCode
@ToString
@Builder(setterPrefix = "with")
@AllArgsConstructor(access = AccessLevel.PRIVATE)
public class ScimTokenDTO {
  @JsonProperty("tokenName")
  private final String tokenName;

  @JsonProperty("tokenValue")
  @ToString.Exclude
  private final String tokenValue;

  @JsonProperty("expiresAt")
  private final long expiresAt;

  public ScimTokenDTO() {
    this(null, null, 0L);
  }

  public static ScimTokenDTO of(String tokenName, String tokenValue, long expiresAt) {
    return ScimTokenDTO.builder()
        .withTokenName(tokenName)
        .withTokenValue(tokenValue)
        .withExpiresAt(expiresAt)
        .build();
  }
}
