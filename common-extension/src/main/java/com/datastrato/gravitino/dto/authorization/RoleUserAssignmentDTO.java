/*
 * Copyright 2026 Datastrato Pvt Ltd.
 */
package com.datastrato.gravitino.dto.authorization;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.google.common.base.Preconditions;
import java.util.Collections;
import java.util.List;
import org.apache.gravitino.Audit;
import org.apache.gravitino.authorization.User;
import org.apache.gravitino.dto.AuditDTO;
import org.apache.gravitino.dto.authorization.UserDTO;
import org.apache.gravitino.dto.util.DTOConverters;

/** A role-assigned user with assignment audit and identity origin. */
public class RoleUserAssignmentDTO extends UserDTO {

  @JsonProperty("origin")
  private IdentitySource origin;

  @JsonProperty("assignmentAudit")
  private AuditDTO assignmentAudit;

  /** Default constructor for Jackson deserialization. */
  protected RoleUserAssignmentDTO() {}

  /**
   * Creates a role user assignment DTO.
   *
   * @param user The user assigned to the role.
   * @param assignmentAudit The audit information recorded on the assignment relation.
   * @param inBuiltInIdp Whether the user exists in the built-in IdP.
   */
  public RoleUserAssignmentDTO(User user, Audit assignmentAudit, boolean inBuiltInIdp) {
    super(
        requireUser(user).id(),
        user.name(),
        user.externalId(),
        rolesOrEmpty(user),
        DTOConverters.toDTO(user.auditInfo()),
        user.enabled());
    this.origin = IdentitySource.fromIdpMembership(inBuiltInIdp);
    this.assignmentAudit =
        DTOConverters.toDTO(
            Preconditions.checkNotNull(assignmentAudit, "assignment audit cannot be null"));
  }

  /**
   * Returns the user's identity origin.
   *
   * @return {@link IdentitySource#LOCAL} or {@link IdentitySource#PROVISIONED}.
   */
  public IdentitySource origin() {
    return origin;
  }

  /**
   * Returns the audit information recorded when the role was assigned.
   *
   * @return The assignment audit information.
   */
  public AuditDTO assignmentAudit() {
    return assignmentAudit;
  }

  private static User requireUser(User user) {
    return Preconditions.checkNotNull(user, "user cannot be null");
  }

  private static List<String> rolesOrEmpty(User user) {
    return user.roles() == null ? Collections.emptyList() : user.roles();
  }
}
