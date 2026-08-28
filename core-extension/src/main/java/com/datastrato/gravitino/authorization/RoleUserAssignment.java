/*
 * Copyright 2026 Datastrato Pvt Ltd.
 */
package com.datastrato.gravitino.authorization;

import com.google.common.base.Preconditions;
import org.apache.gravitino.Audit;
import org.apache.gravitino.authorization.User;

/** A user assigned to a role together with assignment and identity-source information. */
public final class RoleUserAssignment {
  private final User user;
  private final Audit assignmentAudit;
  private final boolean inBuiltInIdp;

  /**
   * Creates a role user assignment.
   *
   * @param user The user assigned to the role.
   * @param assignmentAudit The audit information recorded on the assignment relation.
   * @param inBuiltInIdp Whether the user exists in the built-in IdP.
   */
  public RoleUserAssignment(User user, Audit assignmentAudit, boolean inBuiltInIdp) {
    this.user = Preconditions.checkNotNull(user, "user cannot be null");
    this.assignmentAudit =
        Preconditions.checkNotNull(assignmentAudit, "assignment audit cannot be null");
    this.inBuiltInIdp = inBuiltInIdp;
  }

  /**
   * Returns the assigned user.
   *
   * @return The assigned user.
   */
  public User user() {
    return user;
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
   * Returns whether the user exists in the built-in IdP.
   *
   * @return {@code true} for a local user, {@code false} for a provisioned user.
   */
  public boolean inBuiltInIdp() {
    return inBuiltInIdp;
  }
}
