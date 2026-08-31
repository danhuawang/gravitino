/*
 * Copyright 2026 Datastrato Inc.
 */
package com.datastrato.gravitino.dto.authorization;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.google.common.base.Preconditions;
import lombok.EqualsAndHashCode;
import lombok.Getter;
import lombok.ToString;

/** Top-level counts for the metalake authorization overview cards. */
@Getter
@ToString
@EqualsAndHashCode
public class AuthorizationSummaryDTO {

  @JsonProperty("userCount")
  private int userCount;

  @JsonProperty("activeUserCount")
  private int activeUserCount;

  @JsonProperty("suspendedUserCount")
  private int suspendedUserCount;

  @JsonProperty("groupCount")
  private int groupCount;

  @JsonProperty("emptyGroupCount")
  private int emptyGroupCount;

  @JsonProperty("roleCount")
  private int roleCount;

  @JsonProperty("unassignedRoleCount")
  private int unassignedRoleCount;

  /** Default constructor for Jackson deserialization. */
  protected AuthorizationSummaryDTO() {}

  /**
   * Creates an authorization summary.
   *
   * @param userCount Total metalake users.
   * @param activeUserCount Users with {@code enabled=true}.
   * @param suspendedUserCount Users with {@code enabled=false}.
   * @param groupCount Total metalake groups.
   * @param emptyGroupCount Groups with no member users.
   * @param roleCount Visible roles in the overview.
   * @param unassignedRoleCount Visible roles with no user or group member.
   */
  public AuthorizationSummaryDTO(
      int userCount,
      int activeUserCount,
      int suspendedUserCount,
      int groupCount,
      int emptyGroupCount,
      int roleCount,
      int unassignedRoleCount) {
    Preconditions.checkArgument(userCount >= 0, "user count cannot be negative");
    Preconditions.checkArgument(activeUserCount >= 0, "active user count cannot be negative");
    Preconditions.checkArgument(suspendedUserCount >= 0, "suspended user count cannot be negative");
    Preconditions.checkArgument(groupCount >= 0, "group count cannot be negative");
    Preconditions.checkArgument(emptyGroupCount >= 0, "empty group count cannot be negative");
    Preconditions.checkArgument(roleCount >= 0, "role count cannot be negative");
    Preconditions.checkArgument(
        unassignedRoleCount >= 0, "unassigned role count cannot be negative");
    Preconditions.checkArgument(
        activeUserCount + suspendedUserCount == userCount,
        "active and suspended user counts must sum to user count");
    this.userCount = userCount;
    this.activeUserCount = activeUserCount;
    this.suspendedUserCount = suspendedUserCount;
    this.groupCount = groupCount;
    this.emptyGroupCount = emptyGroupCount;
    this.roleCount = roleCount;
    this.unassignedRoleCount = unassignedRoleCount;
  }
}
