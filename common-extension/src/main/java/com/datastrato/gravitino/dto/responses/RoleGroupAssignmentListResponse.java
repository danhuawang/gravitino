/*
 * Copyright 2026 Datastrato Pvt Ltd.
 */
package com.datastrato.gravitino.dto.responses;

import com.datastrato.gravitino.dto.authorization.RoleGroupAssignmentDTO;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.google.common.base.Preconditions;
import lombok.EqualsAndHashCode;
import lombok.Getter;
import lombok.ToString;
import org.apache.gravitino.dto.responses.BaseResponse;

/** Response containing groups assigned to one role. */
@Getter
@ToString
@EqualsAndHashCode(callSuper = true)
public class RoleGroupAssignmentListResponse extends BaseResponse {

  @JsonProperty("groups")
  private final RoleGroupAssignmentDTO[] groups;

  /**
   * Creates a role group assignment list response.
   *
   * @param groups The groups assigned to the role.
   */
  public RoleGroupAssignmentListResponse(RoleGroupAssignmentDTO[] groups) {
    super(0);
    this.groups = Preconditions.checkNotNull(groups, "groups cannot be null");
  }

  /** Default constructor for Jackson deserialization. */
  public RoleGroupAssignmentListResponse() {
    super(0);
    this.groups = null;
  }

  /**
   * Validates this response.
   *
   * @throws IllegalArgumentException If the assigned groups are missing.
   */
  @Override
  public void validate() throws IllegalArgumentException {
    super.validate();
    Preconditions.checkArgument(groups != null, "groups cannot be null");
  }
}
