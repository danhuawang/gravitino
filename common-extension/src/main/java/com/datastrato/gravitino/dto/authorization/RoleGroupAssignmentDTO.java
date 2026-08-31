/*
 * Copyright 2026 Datastrato Inc.
 */
package com.datastrato.gravitino.dto.authorization;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.google.common.base.Preconditions;
import java.util.Collections;
import java.util.List;
import org.apache.gravitino.Audit;
import org.apache.gravitino.authorization.Group;
import org.apache.gravitino.dto.AuditDTO;
import org.apache.gravitino.dto.authorization.GroupDTO;
import org.apache.gravitino.dto.util.DTOConverters;

/** A role-assigned group with assignment audit and its metalake user count. */
public class RoleGroupAssignmentDTO extends GroupDTO {

  @JsonProperty("assignmentAudit")
  private AuditDTO assignmentAudit;

  @JsonProperty("userCount")
  private int userCount;

  /** Default constructor for Jackson deserialization. */
  protected RoleGroupAssignmentDTO() {}

  /**
   * Creates a role group assignment DTO.
   *
   * @param group The group assigned to the role.
   * @param assignmentAudit The audit information recorded on the assignment relation.
   * @param userCount The number of metalake users in the group.
   */
  public RoleGroupAssignmentDTO(Group group, Audit assignmentAudit, int userCount) {
    super(
        requireGroup(group).id(),
        group.name(),
        group.externalId(),
        rolesOrEmpty(group),
        DTOConverters.toDTO(group.auditInfo()));
    Preconditions.checkArgument(userCount >= 0, "user count cannot be negative");
    this.assignmentAudit =
        DTOConverters.toDTO(
            Preconditions.checkNotNull(assignmentAudit, "assignment audit cannot be null"));
    this.userCount = userCount;
  }

  /**
   * Returns the audit information recorded when the role was assigned.
   *
   * @return The assignment audit information.
   */
  public AuditDTO assignmentAudit() {
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

  private static Group requireGroup(Group group) {
    return Preconditions.checkNotNull(group, "group cannot be null");
  }

  private static List<String> rolesOrEmpty(Group group) {
    return group.roles() == null ? Collections.emptyList() : group.roles();
  }
}
