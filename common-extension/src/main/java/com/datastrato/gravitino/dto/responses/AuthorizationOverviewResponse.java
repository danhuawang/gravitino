/*
 * Copyright 2026 Datastrato Pvt Ltd.
 */
package com.datastrato.gravitino.dto.responses;

import com.datastrato.gravitino.dto.authorization.CatalogAuthorizationDTO;
import com.datastrato.gravitino.dto.authorization.RoleMembershipDTO;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.google.common.base.Preconditions;
import lombok.EqualsAndHashCode;
import lombok.Getter;
import lombok.ToString;
import org.apache.gravitino.dto.responses.BaseResponse;

/** Response containing a metalake authorization overview. */
@Getter
@ToString
@EqualsAndHashCode(callSuper = true)
public class AuthorizationOverviewResponse extends BaseResponse {

  @JsonProperty("catalogs")
  private CatalogAuthorizationDTO[] catalogs;

  @JsonProperty("roles")
  private RoleMembershipDTO[] roles;

  @JsonProperty("unassignedRoles")
  private String[] unassignedRoles;

  /** Default constructor for Jackson deserialization. */
  public AuthorizationOverviewResponse() {
    super();
  }

  /**
   * Creates an authorization overview response.
   *
   * @param catalogs Authorization information grouped by catalog.
   * @param roles All visible roles and their members.
   * @param unassignedRoles Roles that have no user or group member.
   */
  public AuthorizationOverviewResponse(
      CatalogAuthorizationDTO[] catalogs, RoleMembershipDTO[] roles, String[] unassignedRoles) {
    super(0);
    Preconditions.checkArgument(catalogs != null, "catalogs cannot be null");
    Preconditions.checkArgument(roles != null, "roles cannot be null");
    Preconditions.checkArgument(unassignedRoles != null, "unassigned roles cannot be null");
    this.catalogs = catalogs;
    this.roles = roles;
    this.unassignedRoles = unassignedRoles;
  }

  @Override
  public void validate() throws IllegalArgumentException {
    super.validate();
    Preconditions.checkArgument(catalogs != null, "catalogs cannot be null");
    Preconditions.checkArgument(roles != null, "roles cannot be null");
    Preconditions.checkArgument(unassignedRoles != null, "unassigned roles cannot be null");
  }
}
