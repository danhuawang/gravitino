/*
 * Copyright 2026 Datastrato Inc.
 */

package com.datastrato.gravitino.scim.dto;

import com.datastrato.gravitino.scim.model.ScimTokenOverview;
import com.fasterxml.jackson.annotation.JsonProperty;
import java.util.List;
import java.util.stream.Collectors;
import lombok.AccessLevel;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.EqualsAndHashCode;
import lombok.Getter;
import lombok.ToString;

/** SCIM token overview for the Identity Provider admin UI. */
@Getter
@EqualsAndHashCode
@ToString
@Builder(setterPrefix = "with")
@AllArgsConstructor(access = AccessLevel.PRIVATE)
public class ScimTokenOverviewDTO {

  @JsonProperty("lastUsedAt")
  private final long lastUsedAt;

  @JsonProperty("tokenCount")
  private final long tokenCount;

  @JsonProperty("tokens")
  private final List<ScimTokenOverviewItemDTO> tokens;

  /** Default constructor for Jackson deserialization. */
  public ScimTokenOverviewDTO() {
    this(0L, 0L, List.of());
  }

  /**
   * Creates an overview DTO.
   *
   * @param lastUsedAt max last use epoch millis across active tokens
   * @param tokenCount number of active tokens
   * @param tokens token rows
   * @return DTO
   */
  public static ScimTokenOverviewDTO of(
      long lastUsedAt, long tokenCount, List<ScimTokenOverviewItemDTO> tokens) {
    return ScimTokenOverviewDTO.builder()
        .withLastUsedAt(lastUsedAt)
        .withTokenCount(tokenCount)
        .withTokens(tokens)
        .build();
  }

  /**
   * Creates an overview DTO from a service-layer overview.
   *
   * @param overview token overview row
   * @return DTO
   */
  public static ScimTokenOverviewDTO from(ScimTokenOverview overview) {
    List<ScimTokenOverviewItemDTO> tokens =
        overview.getTokens().stream()
            .map(ScimTokenOverviewItemDTO::from)
            .collect(Collectors.toList());
    return of(overview.getLastUsedAt(), overview.getTokenCount(), tokens);
  }
}
