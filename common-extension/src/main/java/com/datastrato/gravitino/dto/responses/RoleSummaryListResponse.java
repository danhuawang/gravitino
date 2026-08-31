/*
 * Copyright 2026 Datastrato Inc.
 */
package com.datastrato.gravitino.dto.responses;

import com.datastrato.gravitino.dto.authorization.RoleSummaryDTO;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.google.common.base.Preconditions;
import java.util.Arrays;
import lombok.EqualsAndHashCode;
import lombok.Getter;
import lombok.ToString;
import org.apache.gravitino.dto.responses.BaseResponse;

/** Response containing summaries for all visible roles in a metalake. */
@Getter
@ToString
@EqualsAndHashCode(callSuper = true)
public class RoleSummaryListResponse extends BaseResponse {

  @JsonProperty("roles")
  private RoleSummaryDTO[] roles;

  /** Default constructor for Jackson deserialization. */
  public RoleSummaryListResponse() {
    super();
  }

  /**
   * Creates a role summary list response.
   *
   * @param roles The visible role summaries.
   */
  public RoleSummaryListResponse(RoleSummaryDTO[] roles) {
    super(0);
    this.roles = Preconditions.checkNotNull(roles, "roles cannot be null");
  }

  /**
   * Validates this response and all role summaries.
   *
   * @throws IllegalArgumentException If the role summaries are missing or invalid.
   */
  @Override
  public void validate() throws IllegalArgumentException {
    super.validate();
    Preconditions.checkArgument(roles != null, "roles cannot be null");
    Arrays.stream(roles)
        .forEach(
            role -> {
              Preconditions.checkArgument(role != null, "role summary cannot be null");
              role.validate();
            });
  }
}
