/*
 * Copyright 2026 Datastrato Pvt Ltd.
 */
package com.datastrato.gravitino.authorization;

import com.google.common.base.Preconditions;
import org.apache.gravitino.Audit;
import org.apache.gravitino.authorization.Group;

/** A group assigned to a role together with assignment audit and user-count information. */
public final class RoleGroupAssignment {
  private final Group group;
  private final Audit assignmentAudit;
  private final int userCount;

  /**
   * Creates a role group assignment.
   *
   * @param group The group assigned to the role.
   * @param assignmentAudit The audit information recorded on the assignment relation.
   * @param userCount The number of metalake users in the group.
   */
  public RoleGroupAssignment(Group group, Audit assignmentAudit, int userCount) {
    this.group = Preconditions.checkNotNull(group, "group cannot be null");
    this.assignmentAudit =
        Preconditions.checkNotNull(assignmentAudit, "assignment audit cannot be null");
    Preconditions.checkArgument(userCount >= 0, "user count cannot be negative");
    this.userCount = userCount;
  }

  /**
   * Returns the assigned group.
   *
   * @return The assigned group.
   */
  public Group group() {
    return group;
  }

  /**
   * Returns the role assignment audit information.
   *
   * @return The assignment audit information.
   */
  public Audit assignmentAudit() {
    return assignmentAudit;
  }

  /**
   * Returns the number of metalake users in the group.
   *
   * @return The group user count.
   */
  public int userCount() {
    return userCount;
  }
}
