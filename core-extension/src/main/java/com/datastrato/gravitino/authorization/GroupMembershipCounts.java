/*
 * Copyright 2026 Datastrato Pvt Ltd.
 */
package com.datastrato.gravitino.authorization;

/** Aggregated metalake group counts including empty groups. */
public final class GroupMembershipCounts {

  private final long total;
  private final long empty;

  /**
   * Creates group membership counts.
   *
   * @param total Total metalake groups.
   * @param empty Groups with no metalake members.
   */
  public GroupMembershipCounts(long total, long empty) {
    this.total = total;
    this.empty = empty;
  }

  /**
   * @return Zero group counts.
   */
  public static GroupMembershipCounts zero() {
    return new GroupMembershipCounts(0L, 0L);
  }

  /**
   * @return Total metalake groups.
   */
  public long total() {
    return total;
  }

  /**
   * @return Groups with no metalake members.
   */
  public long empty() {
    return empty;
  }
}
