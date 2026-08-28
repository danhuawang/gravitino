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

/** Represents a request to assign one role to multiple users and groups. */
@Getter
@EqualsAndHashCode
@ToString
public class RoleAssignmentRequest implements RESTRequest {

  @JsonProperty("users")
  private List<String> users;

  @JsonProperty("groups")
  private List<String> groups;

  /** Default constructor for Jackson deserialization. */
  private RoleAssignmentRequest() {
    this(null, null);
  }

  /**
   * Creates a role assignment request.
   *
   * @param users The users to assign the role to.
   * @param groups The groups to assign the role to.
   */
  public RoleAssignmentRequest(List<String> users, List<String> groups) {
    this.users = users;
    this.groups = groups;
  }

  /** {@inheritDoc} */
  @Override
  public void validate() throws IllegalArgumentException {
    Preconditions.checkArgument(
        (users != null && !users.isEmpty()) || (groups != null && !groups.isEmpty()),
        "users and groups cannot both be null or empty");
    validatePrincipals(users, "users");
    validatePrincipals(groups, "groups");
  }

  private static void validatePrincipals(List<String> principals, String fieldName) {
    if (principals == null) {
      return;
    }

    Preconditions.checkArgument(
        principals.stream().allMatch(StringUtils::isNotBlank),
        "%s cannot contain null or blank names",
        fieldName);
    Preconditions.checkArgument(
        new HashSet<>(principals).size() == principals.size(),
        "%s cannot contain duplicate names",
        fieldName);
  }
}
