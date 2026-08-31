/*
 * Copyright 2026 Datastrato Inc.
 */
package com.datastrato.gravitino.dto.responses;

import com.datastrato.gravitino.dto.authorization.RoleAssignmentDTO;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.google.common.base.Preconditions;
import lombok.EqualsAndHashCode;
import lombok.Getter;
import lombok.ToString;
import org.apache.gravitino.dto.responses.BaseResponse;

/** Response containing the roles assigned to one user or group. */
@Getter
@ToString
@EqualsAndHashCode(callSuper = true)
public class RoleAssignmentListResponse extends BaseResponse {

  @JsonProperty("roles")
  private RoleAssignmentDTO[] roles;

  /** Default constructor for Jackson deserialization. */
  public RoleAssignmentListResponse() {
    super();
  }

  /**
   * Creates a role assignment list response.
   *
   * @param roles The assigned roles.
   */
  public RoleAssignmentListResponse(RoleAssignmentDTO[] roles) {
    super(0);
    this.roles = Preconditions.checkNotNull(roles, "roles cannot be null");
  }

  /**
   * Validates this response.
   *
   * @throws IllegalArgumentException If the assigned roles are missing.
   */
  @Override
  public void validate() throws IllegalArgumentException {
    super.validate();
    Preconditions.checkArgument(roles != null, "roles cannot be null");
  }
}
