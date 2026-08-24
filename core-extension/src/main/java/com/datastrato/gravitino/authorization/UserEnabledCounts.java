/*
 * Copyright 2026 Datastrato Pvt Ltd.
 */
package com.datastrato.gravitino.authorization;

/** Aggregated metalake user counts split by {@code enabled}. */
public final class UserEnabledCounts {

  private final long total;
  private final long active;
  private final long suspended;

  /**
   * Creates user enabled counts.
   *
   * @param total Total metalake users.
   * @param active Enabled users.
   * @param suspended Disabled users.
   */
  public UserEnabledCounts(long total, long active, long suspended) {
    this.total = total;
    this.active = active;
    this.suspended = suspended;
  }

  /**
   * @return Zero user counts.
   */
  public static UserEnabledCounts empty() {
    return new UserEnabledCounts(0L, 0L, 0L);
  }

  /**
   * @return Total metalake users.
   */
  public long total() {
    return total;
  }

  /**
   * @return Enabled users.
   */
  public long active() {
    return active;
  }

  /**
   * @return Disabled users.
   */
  public long suspended() {
    return suspended;
  }
}
