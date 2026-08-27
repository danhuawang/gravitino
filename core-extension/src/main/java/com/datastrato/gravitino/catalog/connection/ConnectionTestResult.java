/*
 * Copyright 2026 Datastrato Pvt Ltd.
 */
package com.datastrato.gravitino.catalog.connection;

import com.google.common.base.Preconditions;
import javax.annotation.Nullable;

/** Immutable persisted result for one Catalog connection test type. */
public final class ConnectionTestResult {

  /** Persisted result states. NOT_TESTED is derived and is never stored. */
  public enum Status {
    /** A connection probe completed successfully. */
    PASSED,
    /** A connection probe completed with a connection failure. */
    FAILED
  }

  private final long catalogId;
  private final String testType;
  private final long catalogVersion;
  private final Status status;
  private final long lastTestedAt;
  @Nullable private final String errorMessage;

  /**
   * Creates a persisted connection test result.
   *
   * @param catalogId The Catalog ID.
   * @param testType The Catalog or credential connection test type.
   * @param catalogVersion The Catalog version tested.
   * @param status The completed test status.
   * @param lastTestedAt The completion time in epoch milliseconds.
   * @param errorMessage The safe failure message, or {@code null} for a passed test.
   */
  public ConnectionTestResult(
      long catalogId,
      String testType,
      long catalogVersion,
      Status status,
      long lastTestedAt,
      @Nullable String errorMessage) {
    Preconditions.checkArgument(catalogId > 0, "Catalog ID must be positive");
    ConnectionTestType.validate(testType);
    Preconditions.checkArgument(catalogVersion > 0, "Catalog version must be positive");
    Preconditions.checkArgument(status != null, "Connection test status cannot be null");
    Preconditions.checkArgument(lastTestedAt >= 0, "Last tested time cannot be negative");
    if (status == Status.PASSED) {
      Preconditions.checkArgument(errorMessage == null, "Passed tests cannot contain an error");
    } else {
      Preconditions.checkArgument(
          errorMessage != null && !errorMessage.trim().isEmpty(),
          "Failed tests must contain a safe error message");
    }
    this.catalogId = catalogId;
    this.testType = testType;
    this.catalogVersion = catalogVersion;
    this.status = status;
    this.lastTestedAt = lastTestedAt;
    this.errorMessage = errorMessage;
  }

  /**
   * @return The Catalog ID.
   */
  public long catalogId() {
    return catalogId;
  }

  /**
   * @return The Catalog or credential connection test type.
   */
  public String testType() {
    return testType;
  }

  /**
   * @return The Catalog version tested.
   */
  public long catalogVersion() {
    return catalogVersion;
  }

  /**
   * @return The completed test status.
   */
  public Status status() {
    return status;
  }

  /**
   * @return The completion time in epoch milliseconds.
   */
  public long lastTestedAt() {
    return lastTestedAt;
  }

  /**
   * @return The safe failure message, or {@code null} for a passed test.
   */
  @Nullable
  public String errorMessage() {
    return errorMessage;
  }
}
