/*
 * Copyright 2026 Datastrato Inc.
 */

package com.datastrato.gravitino.scim.model;

import com.google.common.base.Preconditions;
import java.util.List;
import lombok.AccessLevel;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.EqualsAndHashCode;
import lombok.Getter;
import lombok.ToString;

/** Per-metalake SCIM token overview for the Identity Provider admin UI. */
@Getter
@EqualsAndHashCode
@ToString
@Builder(setterPrefix = "with")
@AllArgsConstructor(access = AccessLevel.PRIVATE)
public class ScimTokenOverview {

  /** Epoch millis of the most recent token use; {@code 0} means never used. */
  private final long lastUsedAt;

  /** Number of active SCIM tokens in the metalake. */
  private final long tokenCount;

  private final List<ScimTokenSummary> tokens;

  /**
   * Creates a token overview row.
   *
   * @param lastUsedAt max last use epoch millis across active tokens
   * @param tokens active token rows
   * @return overview row
   */
  public static ScimTokenOverview of(long lastUsedAt, List<ScimTokenSummary> tokens) {
    Preconditions.checkArgument(tokens != null, "tokens must not be null");
    return ScimTokenOverview.builder()
        .withLastUsedAt(lastUsedAt)
        .withTokenCount(tokens.size())
        .withTokens(List.copyOf(tokens))
        .build();
  }
}
