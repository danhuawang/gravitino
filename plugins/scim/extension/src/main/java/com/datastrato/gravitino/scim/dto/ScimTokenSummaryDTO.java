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

/** One SCIM token row in the per-metalake token list. */
@Getter
@EqualsAndHashCode
@ToString
@Builder(setterPrefix = "with")
@AllArgsConstructor(access = AccessLevel.PRIVATE)
public class ScimTokenSummaryDTO {

  @JsonProperty("tokenName")
  private final String tokenName;

  @JsonProperty("expiresAt")
  private final long expiresAt;

  @JsonProperty("status")
  private final String status;

  @JsonProperty("createdAt")
  private final long createdAt;

  @JsonProperty("lastUsedAt")
  private final long lastUsedAt;

  /** Default constructor for Jackson deserialization. */
  public ScimTokenSummaryDTO() {
    this(null, 0L, null, 0L, 0L);
  }

  /**
   * Creates a token list item DTO.
   *
   * @param tokenName token name
   * @param expiresAt expiry epoch millis; {@code 0} means no expiry
   * @param status {@code valid} or {@code expired}
   * @param createdAt creation epoch millis
   * @param lastUsedAt last use epoch millis; {@code 0} means never
   * @return DTO
   */
  public static ScimTokenSummaryDTO of(
      String tokenName, long expiresAt, String status, long createdAt, long lastUsedAt) {
    return ScimTokenSummaryDTO.builder()
        .withTokenName(tokenName)
        .withExpiresAt(expiresAt)
        .withStatus(status)
        .withCreatedAt(createdAt)
        .withLastUsedAt(lastUsedAt)
        .build();
  }

  /**
   * Creates a token list item DTO from a service-layer summary.
   *
   * @param summary token list summary row
   * @return DTO
   */
  public static ScimTokenSummaryDTO from(ScimTokenSummary summary) {
    return of(
        summary.getTokenName(),
        summary.getExpiresAt(),
        summary.getStatus(),
        summary.getCreatedAt(),
        summary.getLastUsedAt());
  }
}
