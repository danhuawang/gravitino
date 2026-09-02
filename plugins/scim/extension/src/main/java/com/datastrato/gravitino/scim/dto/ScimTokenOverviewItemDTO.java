/*
 * Copyright 2026 Datastrato Inc.
 */

package com.datastrato.gravitino.scim.dto;

import com.datastrato.gravitino.scim.model.ScimTokenSummary;
import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.AccessLevel;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.EqualsAndHashCode;
import lombok.Getter;
import lombok.ToString;

/** One SCIM token row in the Identity Provider overview response. */
@Getter
@EqualsAndHashCode
@ToString
@Builder(setterPrefix = "with")
@AllArgsConstructor(access = AccessLevel.PRIVATE)
public class ScimTokenOverviewItemDTO {

  @JsonProperty("tokenName")
  private final String tokenName;

  @JsonProperty("expiresAt")
  private final long expiresAt;

  @JsonProperty("createdAt")
  private final long createdAt;

  @JsonProperty("lastUsedAt")
  private final long lastUsedAt;

  /** Default constructor for Jackson deserialization. */
  public ScimTokenOverviewItemDTO() {
    this(null, 0L, 0L, 0L);
  }

  /**
   * Creates an overview token row DTO.
   *
   * @param tokenName token name
   * @param expiresAt expiry epoch millis; {@code 0} means no expiry
   * @param createdAt creation epoch millis
   * @param lastUsedAt last use epoch millis; {@code 0} means never
   * @return DTO
   */
  public static ScimTokenOverviewItemDTO of(
      String tokenName, long expiresAt, long createdAt, long lastUsedAt) {
    return ScimTokenOverviewItemDTO.builder()
        .withTokenName(tokenName)
        .withExpiresAt(expiresAt)
        .withCreatedAt(createdAt)
        .withLastUsedAt(lastUsedAt)
        .build();
  }

  /**
   * Creates an overview token row DTO from a service-layer summary.
   *
   * @param summary token list summary row
   * @return DTO
   */
  public static ScimTokenOverviewItemDTO from(ScimTokenSummary summary) {
    return of(
        summary.getTokenName(),
        summary.getExpiresAt(),
        summary.getCreatedAt(),
        summary.getLastUsedAt());
  }
}
