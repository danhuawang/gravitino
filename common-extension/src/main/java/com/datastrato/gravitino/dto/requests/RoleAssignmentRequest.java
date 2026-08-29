/*
 * Copyright 2026 Datastrato Pvt Ltd.
 */
package com.datastrato.gravitino.dto.requests;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.google.common.base.Preconditions;
import java.util.HashSet;
import java.util.List;
import lombok.EqualsAndHashCode;
import lombok.Getter;
import lombok.ToString;
import org.apache.commons.lang3.StringUtils;
import org.apache.gravitino.rest.RESTRequest;

/** Represents a request to assign multiple roles to multiple users and groups. */
@Getter
@EqualsAndHashCode
@ToString
public class RoleAssignmentRequest implements RESTRequest {

  @JsonProperty("roles")
  private List<String> roles;

  @JsonProperty("users")
  private List<String> users;

  @JsonProperty("groups")
  private List<String> groups;

  /** Default constructor for Jackson deserialization. */
  private RoleAssignmentRequest() {
    this(null, null, null);
  }

  /**
   * Creates a role assignment request.
   *
   * @param roles The roles to assign.
   * @param users The users to assign the roles to.
   * @param groups The groups to assign the roles to.
   */
  public RoleAssignmentRequest(List<String> roles, List<String> users, List<String> groups) {
    this.roles = roles;
    this.users = users;
    this.groups = groups;
  }

  /** {@inheritDoc} */
  @Override
  public void validate() throws IllegalArgumentException {
    Preconditions.checkArgument(roles != null && !roles.isEmpty(), "roles cannot be null or empty");
    Preconditions.checkArgument(
        (users != null && !users.isEmpty()) || (groups != null && !groups.isEmpty()),
        "users and groups cannot both be null or empty");
    validateNames(roles, "roles");
    validateNames(users, "users");
    validateNames(groups, "groups");
  }

  private static void validateNames(List<String> names, String fieldName) {
    if (names == null) {
      return;
    }

    Preconditions.checkArgument(
        names.stream().allMatch(StringUtils::isNotBlank),
        "%s cannot contain null or blank names",
        fieldName);
    Preconditions.checkArgument(
        new HashSet<>(names).size() == names.size(),
        "%s cannot contain duplicate names",
        fieldName);
  }
}
