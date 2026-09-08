/*
 * Copyright 2026 Datastrato Inc.
 */
package com.datastrato.gravitino.dto.authorization;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.google.common.base.Preconditions;
import lombok.EqualsAndHashCode;
import lombok.Getter;
import lombok.ToString;

/** Direct user and group members of a role. */
@Getter
@ToString
@EqualsAndHashCode
public class RoleMembersDTO {

  @JsonProperty("users")
  private String[] users;

  @JsonProperty("groups")
  private String[] groups;

  /** Default constructor for Jackson deserialization. */
  protected RoleMembersDTO() {}

  /**
   * Creates role members.
   *
   * @param users The users directly assigned to the role.
   * @param groups The groups directly assigned to the role.
   */
  public RoleMembersDTO(String[] users, String[] groups) {
    this.users = Preconditions.checkNotNull(users, "users cannot be null");
    this.groups = Preconditions.checkNotNull(groups, "groups cannot be null");
  }
}
