/*
 * Copyright 2026 Datastrato Pvt Ltd.
 */
package com.datastrato.gravitino.dto.responses;

import com.datastrato.gravitino.dto.authorization.RoleUserAssignmentDTO;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.google.common.base.Preconditions;
import lombok.EqualsAndHashCode;
import lombok.Getter;
import lombok.ToString;
import org.apache.gravitino.dto.responses.BaseResponse;

/** Response containing users assigned to one role. */
@Getter
@ToString
@EqualsAndHashCode(callSuper = true)
public class RoleUserAssignmentListResponse extends BaseResponse {

  @JsonProperty("users")
  private final RoleUserAssignmentDTO[] users;

  /**
   * Creates a role user assignment list response.
   *
   * @param users The users assigned to the role.
   */
  public RoleUserAssignmentListResponse(RoleUserAssignmentDTO[] users) {
    super(0);
    this.users = Preconditions.checkNotNull(users, "users cannot be null");
  }

  /** Default constructor for Jackson deserialization. */
  public RoleUserAssignmentListResponse() {
    super(0);
    this.users = null;
  }

  /**
   * Validates this response.
   *
   * @throws IllegalArgumentException If the assigned users are missing.
   */
  @Override
  public void validate() throws IllegalArgumentException {
    super.validate();
    Preconditions.checkArgument(users != null, "users cannot be null");
  }
}
