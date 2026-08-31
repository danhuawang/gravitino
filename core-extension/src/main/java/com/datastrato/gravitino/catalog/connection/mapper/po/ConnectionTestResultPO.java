/*
 * Copyright 2026 Datastrato Inc.
 */
package com.datastrato.gravitino.catalog.connection.mapper.po;

import javax.annotation.Nullable;

/** Relational persistence object for a Catalog or credential connection test result. */
public class ConnectionTestResultPO {
  private Long catalogId;
  private String type;
  private Long catalogVersion;
  private String testStatus;
  private Long lastTestedAt;
  @Nullable private String errorMessage;

  /** Creates an empty persistence object for MyBatis. */
  public ConnectionTestResultPO() {}

  /**
   * Creates a populated persistence object.
   *
   * @param catalogId The Catalog ID.
   * @param type The Catalog or credential connection test type.
   * @param catalogVersion The tested Catalog version.
   * @param testStatus The persisted test status.
   * @param lastTestedAt The completion time in epoch milliseconds.
   * @param errorMessage The safe failure message, or {@code null}.
   */
  public ConnectionTestResultPO(
      Long catalogId,
      String type,
      Long catalogVersion,
      String testStatus,
      Long lastTestedAt,
      @Nullable String errorMessage) {
    this.catalogId = catalogId;
    this.type = type;
    this.catalogVersion = catalogVersion;
    this.testStatus = testStatus;
    this.lastTestedAt = lastTestedAt;
    this.errorMessage = errorMessage;
  }

  /**
   * @return The Catalog ID.
   */
  public Long getCatalogId() {
    return catalogId;
  }

  /**
   * @return The Catalog or credential connection test type.
   */
  public String getType() {
    return type;
  }

  /**
   * @return The tested Catalog version.
   */
  public Long getCatalogVersion() {
    return catalogVersion;
  }

  /**
   * @return The persisted test status.
   */
  public String getTestStatus() {
    return testStatus;
  }

  /**
   * @return The completion time in epoch milliseconds.
   */
  public Long getLastTestedAt() {
    return lastTestedAt;
  }

  /**
   * @return The safe failure message, or {@code null}.
   */
  @Nullable
  public String getErrorMessage() {
    return errorMessage;
  }
}
