/*
 * Copyright 2026 Datastrato Inc.
 */
package com.datastrato.gravitino.dto.authorization;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.google.common.base.Preconditions;
import javax.annotation.Nullable;
import lombok.EqualsAndHashCode;
import lombok.Getter;
import lombok.ToString;
import org.apache.gravitino.dto.AuditDTO;
import org.apache.gravitino.dto.authorization.OwnerDTO;

/** Details about an assigned role, including its members, owner, and assignment audit. */
@Getter
@ToString
@EqualsAndHashCode
public class RoleAssignmentDetailsDTO {

  @JsonProperty("role")
  private AssignedRoleDTO role;

  @JsonProperty("members")
  private RoleMembersDTO members;

  @JsonProperty("owner")
  private @Nullable OwnerDTO owner;

  @JsonProperty("assignment")
  private AuditDTO assignment;

  /** Default constructor for Jackson deserialization. */
  protected RoleAssignmentDetailsDTO() {}

  /**
   * Creates an assigned role details DTO.
   *
   * @param role The assigned role without securable objects or privileges.
   * @param members The direct user and group members of the role.
   * @param owner The role owner, or {@code null} when no owner is assigned.
   * @param assignment The audit information recorded on the assignment relation.
   */
  public RoleAssignmentDetailsDTO(
      AssignedRoleDTO role, RoleMembersDTO members, @Nullable OwnerDTO owner, AuditDTO assignment) {
    this.role = Preconditions.checkNotNull(role, "role cannot be null");
    this.members = Preconditions.checkNotNull(members, "members cannot be null");
    this.owner = owner;
    this.assignment = Preconditions.checkNotNull(assignment, "assignment cannot be null");
  }
}
