/*
 * Copyright 2026 Datastrato Pvt Ltd.
 */
package com.datastrato.gravitino.dto;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.google.common.base.Preconditions;
import com.google.common.collect.ImmutableSet;
import java.time.Instant;
import java.util.Set;
import javax.annotation.Nullable;
import lombok.EqualsAndHashCode;
import lombok.Getter;
import lombok.ToString;

/** Connection test support and latest valid manual test status for one target. */
@Getter
@ToString
@EqualsAndHashCode
public class ConnectionTestStatusDTO {
  /** The derived state for a supported target without a valid persisted result. */
  public static final String NOT_TESTED = "NOT_TESTED";

  /** The state for a completed successful connection probe. */
  public static final String PASSED = "PASSED";

  /** The state for a completed connection probe that could not connect. */
  public static final String FAILED = "FAILED";

  private static final Set<String> VALID_STATUSES = ImmutableSet.of(NOT_TESTED, PASSED, FAILED);

  @JsonProperty("supported")
  private final boolean supported;

  @JsonProperty("status")
  @JsonInclude(JsonInclude.Include.ALWAYS)
  @Nullable
  private final String status;

  @JsonProperty("lastTestedAt")
  @JsonInclude(JsonInclude.Include.ALWAYS)
  @Nullable
  private final Instant lastTestedAt;

  @JsonProperty("error")
  @JsonInclude(JsonInclude.Include.ALWAYS)
  @Nullable
  private final ConnectionTestErrorDTO error;

  /**
   * Creates a connection test status.
   *
   * @param supported Whether the provider implements connection testing.
   * @param status The latest state, or {@code null} when unsupported.
   * @param lastTestedAt The completion time, or {@code null} when not tested or unsupported.
   * @param error The safe failure error, or {@code null} for other states.
   */
  public ConnectionTestStatusDTO(
      boolean supported,
      @Nullable String status,
      @Nullable Instant lastTestedAt,
      @Nullable ConnectionTestErrorDTO error) {
    this.supported = supported;
    this.status = status;
    this.lastTestedAt = lastTestedAt;
    this.error = error;
  }

  /** Creates an unsupported empty instance for Jackson deserialization. */
  public ConnectionTestStatusDTO() {
    this(false, null, null, null);
  }

  /**
   * Validates status-dependent nullable fields.
   *
   * @throws IllegalArgumentException If the field combination is inconsistent.
   */
  public void validate() {
    if (!supported) {
      Preconditions.checkArgument(status == null, "unsupported status must be null");
      Preconditions.checkArgument(lastTestedAt == null, "unsupported lastTestedAt must be null");
      Preconditions.checkArgument(error == null, "unsupported error must be null");
      return;
    }

    Preconditions.checkArgument(VALID_STATUSES.contains(status), "invalid test status");
    if (NOT_TESTED.equals(status)) {
      Preconditions.checkArgument(lastTestedAt == null, "NOT_TESTED cannot have lastTestedAt");
      Preconditions.checkArgument(error == null, "NOT_TESTED cannot have an error");
    } else {
      Preconditions.checkArgument(lastTestedAt != null, "completed tests require lastTestedAt");
      if (PASSED.equals(status)) {
        Preconditions.checkArgument(error == null, "PASSED cannot have an error");
      } else {
        Preconditions.checkArgument(error != null, "FAILED requires an error");
        error.validate();
      }
    }
  }
}
