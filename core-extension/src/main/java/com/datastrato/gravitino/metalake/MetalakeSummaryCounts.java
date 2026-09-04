/*
 * Copyright 2026 Datastrato Inc.
 */
package com.datastrato.gravitino.metalake;

/** Counts of active child entities persisted for a metalake. */
public final class MetalakeSummaryCounts {
  private final long catalogCount;
  private final long userCount;
  private final long roleCount;

  /**
   * Creates persisted metalake entity counts.
   *
   * @param catalogCount The number of active catalogs.
   * @param userCount The number of active users.
   * @param roleCount The number of active roles.
   */
  public MetalakeSummaryCounts(long catalogCount, long userCount, long roleCount) {
    this.catalogCount = catalogCount;
    this.userCount = userCount;
    this.roleCount = roleCount;
  }

  /**
   * @return The number of active catalogs.
   */
  public long catalogCount() {
    return catalogCount;
  }

  /**
   * @return The number of active users.
   */
  public long userCount() {
    return userCount;
  }

  /**
   * @return The number of active roles.
   */
  public long roleCount() {
    return roleCount;
  }
}
