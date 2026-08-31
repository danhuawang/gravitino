/*
 * Copyright 2024 Datastrato Inc.
 */
package com.datastrato.gravitino.metrics.storage.relational;

import java.sql.Timestamp;
import javax.annotation.Nullable;

/** Persistent state for a metalake whose dashboard metrics need to be recomputed. */
public class MetricDirtyPO {
  private Long metalakeId;
  private Long revision;
  private Timestamp firstDirtyAt;
  private Timestamp lastEventAt;
  private Integer retryCount;
  @Nullable private Timestamp retryAfter;
  @Nullable private String lastError;

  /**
   * @return the dirty metalake ID
   */
  public Long getMetalakeId() {
    return metalakeId;
  }

  /**
   * @return the monotonically increasing dirty revision
   */
  public Long getRevision() {
    return revision;
  }

  /**
   * @return when the current burst of changes started
   */
  public Timestamp getFirstDirtyAt() {
    return firstDirtyAt;
  }

  /**
   * @return when the most recent event was received
   */
  public Timestamp getLastEventAt() {
    return lastEventAt;
  }

  /**
   * @return the number of consecutive recomputation failures
   */
  public Integer getRetryCount() {
    return retryCount;
  }

  /**
   * @return the earliest retry time, or {@code null} when this is not a retry
   */
  @Nullable
  public Timestamp getRetryAfter() {
    return retryAfter;
  }

  /**
   * @return the most recent truncated error message, or {@code null}
   */
  @Nullable
  public String getLastError() {
    return lastError;
  }
}
