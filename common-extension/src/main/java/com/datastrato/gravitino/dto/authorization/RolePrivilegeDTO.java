/*
 * Copyright 2026 Datastrato Pvt Ltd.
 * This software is licensed under the Apache License version 2.
 */
package com.datastrato.gravitino.dto.authorization;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.google.common.base.Preconditions;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import lombok.EqualsAndHashCode;
import lombok.Getter;
import lombok.ToString;
import org.apache.commons.lang3.StringUtils;
import org.apache.gravitino.authorization.Privilege;
import org.apache.gravitino.dto.authorization.PrivilegeDTO;

/** Data transfer object representing privileges granted to a role on a metadata object. */
@Getter
@ToString
@EqualsAndHashCode
public class RolePrivilegeDTO {

  @JsonProperty("role")
  private String role;

  @JsonProperty("privileges")
  private PrivilegeDTO[] privileges;

  /** Default constructor for Jackson deserialization. */
  protected RolePrivilegeDTO() {}

  /**
   * Creates a new instance of RolePrivilegeDTO.
   *
   * @param role The role name.
   * @param privileges The privileges granted to the role.
   */
  protected RolePrivilegeDTO(String role, PrivilegeDTO[] privileges) {
    this.role = role;
    this.privileges = privileges;
  }

  /**
   * The role name.
   *
   * @return The role name.
   */
  public String role() {
    return role;
  }

  /**
   * The privileges granted to the role.
   *
   * @return The privileges granted to the role.
   */
  public List<Privilege> privileges() {
    if (privileges == null) {
      return Collections.emptyList();
    }

    return Arrays.asList(privileges);
  }

  /**
   * Creates a new Builder for constructing a RolePrivilegeDTO.
   *
   * @return A new Builder instance.
   */
  public static Builder builder() {
    return new Builder();
  }

  /** Builder class for constructing a RolePrivilegeDTO instance. */
  public static class Builder {
    private String role;
    private PrivilegeDTO[] privileges;

    /**
     * Sets the role name.
     *
     * @param role The role name.
     * @return The builder instance.
     */
    public Builder withRole(String role) {
      this.role = role;
      return this;
    }

    /**
     * Sets the privileges granted to the role.
     *
     * @param privileges The privileges granted to the role.
     * @return The builder instance.
     */
    public Builder withPrivileges(PrivilegeDTO[] privileges) {
      this.privileges = privileges;
      return this;
    }

    /**
     * Builds an instance of RolePrivilegeDTO using the builder's properties.
     *
     * @return An instance of RolePrivilegeDTO.
     * @throws IllegalArgumentException If the role or privileges are not set.
     */
    public RolePrivilegeDTO build() {
      Preconditions.checkArgument(StringUtils.isNotBlank(role), "role cannot be null or empty");
      Preconditions.checkArgument(
          privileges != null && privileges.length != 0, "privileges can't be null or empty");

      return new RolePrivilegeDTO(role, privileges);
    }
  }
}
