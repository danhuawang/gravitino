/*
 * Copyright 2026 Datastrato Inc.
 */
package com.datastrato.gravitino.dto.authorization;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.google.common.base.Preconditions;
import lombok.EqualsAndHashCode;
import lombok.Getter;
import lombok.ToString;
import org.apache.commons.lang3.StringUtils;

/** A role with its assigned user and group members. */
@Getter
@ToString
@EqualsAndHashCode
public class RoleMembershipDTO {

  @JsonProperty("role")
  private String role;

  @JsonProperty("users")
  private String[] users;

  @JsonProperty("groups")
  private String[] groups;

  @JsonProperty("userCount")
  private int userCount;

  @JsonProperty("groupCount")
  private int groupCount;

  @JsonProperty("memberCount")
  private int memberCount;

  @JsonProperty("assigned")
  private boolean assigned;

  @JsonProperty("catalogs")
  private String[] catalogs;

  @JsonProperty("objectCount")
  private int objectCount;

  @JsonProperty("privilegeCount")
  private int privilegeCount;

  /** Default constructor for Jackson deserialization. */
  protected RoleMembershipDTO() {}

  /**
   * Creates a role membership DTO.
   *
   * @param role The role name.
   * @param users The users assigned to the role.
   * @param groups The groups assigned to the role.
   * @param catalogs The catalogs containing explicitly privileged objects for the role.
   * @param objectCount The number of explicitly privileged objects in the role.
   * @param privilegeCount The number of explicit privilege entries in the role.
   */
  public RoleMembershipDTO(
      String role,
      String[] users,
      String[] groups,
      String[] catalogs,
      int objectCount,
      int privilegeCount) {
    Preconditions.checkArgument(StringUtils.isNotBlank(role), "role cannot be blank");
    Preconditions.checkArgument(users != null, "users cannot be null");
    Preconditions.checkArgument(groups != null, "groups cannot be null");
    Preconditions.checkArgument(catalogs != null, "catalogs cannot be null");
    Preconditions.checkArgument(objectCount >= 0, "object count cannot be negative");
    Preconditions.checkArgument(privilegeCount >= 0, "privilege count cannot be negative");
    this.role = role;
    this.users = users;
    this.groups = groups;
    this.userCount = users.length;
    this.groupCount = groups.length;
    this.memberCount = users.length + groups.length;
    this.assigned = memberCount > 0;
    this.catalogs = catalogs;
    this.objectCount = objectCount;
    this.privilegeCount = privilegeCount;
  }
}
