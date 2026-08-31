/*
 * Copyright 2026 Datastrato Inc.
 */
package com.datastrato.gravitino.dto.authorization;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.google.common.base.Preconditions;
import lombok.EqualsAndHashCode;
import lombok.Getter;
import lombok.ToString;
import org.apache.gravitino.dto.AuditDTO;
import org.apache.gravitino.dto.authorization.RoleDTO;

/** A role and the audit information of its assignment to a user or group. */
@Getter
@ToString
@EqualsAndHashCode
public class RoleAssignmentDTO {

  @JsonProperty("role")
  private RoleDTO role;

  @JsonProperty("assignmentAudit")
  private AuditDTO assignmentAudit;

  /** Default constructor for Jackson deserialization. */
  protected RoleAssignmentDTO() {}

  /**
   * Creates a role assignment DTO.
   *
   * @param role The assigned role including its securable objects and privileges.
   * @param assignmentAudit The audit information recorded on the assignment relation.
   */
  public RoleAssignmentDTO(RoleDTO role, AuditDTO assignmentAudit) {
    this.role = Preconditions.checkNotNull(role, "role cannot be null");
    this.assignmentAudit =
        Preconditions.checkNotNull(assignmentAudit, "assignment audit cannot be null");
  }
}
