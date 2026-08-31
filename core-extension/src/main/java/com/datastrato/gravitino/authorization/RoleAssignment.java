/*
 * Copyright 2026 Datastrato Inc.
 */
package com.datastrato.gravitino.authorization;

import com.google.common.base.Preconditions;
import org.apache.gravitino.Audit;
import org.apache.gravitino.authorization.Role;

/** A role assigned to a user or group together with the assignment audit information. */
public final class RoleAssignment {
  private final Role role;
  private final Audit assignmentAudit;

  /**
   * Creates a role assignment.
   *
   * @param role The assigned role.
   * @param assignmentAudit The audit information recorded on the assignment relation.
   */
  public RoleAssignment(Role role, Audit assignmentAudit) {
    this.role = Preconditions.checkNotNull(role, "role cannot be null");
    this.assignmentAudit =
        Preconditions.checkNotNull(assignmentAudit, "assignment audit cannot be null");
  }

  /**
   * Returns the assigned role including its securable objects and privileges.
   *
   * @return The assigned role.
   */
  public Role role() {
    return role;
  }

  /**
   * Returns the audit information recorded when the role was assigned.
   *
   * @return The assignment audit information.
   */
  public Audit assignmentAudit() {
    return assignmentAudit;
  }
}
