/*
 * Copyright 2026 Datastrato Inc.
 */
package com.datastrato.gravitino.dto;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.google.common.base.Preconditions;
import javax.annotation.Nullable;
import lombok.EqualsAndHashCode;
import lombok.Getter;
import lombok.ToString;

/** A direct-child count together with collection freshness and completeness. */
@Getter
@ToString
@EqualsAndHashCode
public class DirectChildCountDTO {
  @JsonProperty("value")
  @JsonInclude(JsonInclude.Include.ALWAYS)
  @Nullable
  private final Long value;

  @JsonProperty("state")
  @Nullable
  private final DirectChildCountState state;

  @JsonProperty("updatedAt")
  @JsonInclude(JsonInclude.Include.ALWAYS)
  @Nullable
  private final Long updatedAt;

  @JsonProperty("refreshPending")
  private final boolean refreshPending;

  /**
   * Creates a direct-child count status.
   *
   * @param value complete count, or {@code null} for an incomplete state
   * @param state collection state
   * @param updatedAt metric collection time in epoch milliseconds, or {@code null} when missing
   * @param refreshPending whether the metalake has a pending metric refresh
   */
  public DirectChildCountDTO(
      @Nullable Long value,
      DirectChildCountState state,
      @Nullable Long updatedAt,
      boolean refreshPending) {
    this.value = value;
    this.state = state;
    this.updatedAt = updatedAt;
    this.refreshPending = refreshPending;
  }

  /** Default constructor for Jackson deserialization. */
  public DirectChildCountDTO() {
    this.value = null;
    this.state = null;
    this.updatedAt = null;
    this.refreshPending = false;
  }

  /** Validates value and timestamp semantics against the collection state. */
  public void validate() {
    Preconditions.checkArgument(state != null, "directChildCount state cannot be null");
    if (state == DirectChildCountState.COMPLETE) {
      Preconditions.checkArgument(value != null, "complete directChildCount value cannot be null");
      Preconditions.checkArgument(value >= 0, "directChildCount value cannot be negative");
      Preconditions.checkArgument(
          updatedAt != null, "complete directChildCount updatedAt cannot be null");
    } else {
      Preconditions.checkArgument(value == null, "incomplete directChildCount value must be null");
    }
    if (updatedAt != null) {
      Preconditions.checkArgument(updatedAt >= 0, "directChildCount updatedAt cannot be negative");
    }
  }
}
