/*
 * Copyright 2026 Datastrato Pvt Ltd.
 */
package com.datastrato.gravitino.dto.authorization;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.google.common.base.Preconditions;
import java.time.Instant;
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

  @JsonProperty("createTime")
  private Instant createTime;

  @JsonProperty("assignCount")
  private int assignCount;

  /** Default constructor for Jackson deserialization. */
  protected RolePrivilegeDTO() {}

  /**
   * Creates a new instance of RolePrivilegeDTO.
   *
   * @param role The role name.
   * @param privileges The privileges granted to the role.
   * @param createTime The role creation time.
   * @param assignCount The number of users and groups directly assigned to the role.
   */
  protected RolePrivilegeDTO(
      String role, PrivilegeDTO[] privileges, Instant createTime, int assignCount) {
    this.role = role;
    this.privileges = privileges;
    this.createTime = createTime;
    this.assignCount = assignCount;
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
    private Instant createTime;
    private int assignCount;

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
     * Sets the role creation time.
     *
     * @param createTime The role creation time.
     * @return The builder instance.
     */
    public Builder withCreateTime(Instant createTime) {
      this.createTime = createTime;
      return this;
    }

    /**
     * Sets the number of direct user and group assignments.
     *
     * @param assignCount The direct assignment count.
     * @return The builder instance.
     */
    public Builder withAssignCount(int assignCount) {
      this.assignCount = assignCount;
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
      Preconditions.checkArgument(createTime != null, "create time cannot be null");
      Preconditions.checkArgument(assignCount >= 0, "assign count cannot be negative");

      return new RolePrivilegeDTO(role, privileges, createTime, assignCount);
    }
  }
}
