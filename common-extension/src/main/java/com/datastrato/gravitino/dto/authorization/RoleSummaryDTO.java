/*
 * Copyright 2026 Datastrato Inc.
 */
package com.datastrato.gravitino.dto.authorization;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.google.common.base.Preconditions;
import java.time.Instant;
import javax.annotation.Nullable;
import lombok.EqualsAndHashCode;
import lombok.Getter;
import lombok.ToString;
import org.apache.commons.lang3.StringUtils;
import org.apache.gravitino.dto.authorization.OwnerDTO;

/** Summary of a role, its owner, creation time, and direct principal counts. */
@Getter
@ToString
@EqualsAndHashCode
public class RoleSummaryDTO {

  @JsonProperty("role")
  private String role;

  @JsonProperty("owner")
  private @Nullable OwnerDTO owner;

  @JsonProperty("createTime")
  private Instant createTime;

  @JsonProperty("userCount")
  private int userCount;

  @JsonProperty("groupCount")
  private int groupCount;

  @JsonProperty("assignCount")
  private int assignCount;

  /** Default constructor for Jackson deserialization. */
  protected RoleSummaryDTO() {}

  /**
   * Creates a role summary.
   *
   * @param role The role name.
   * @param owner The role owner, or {@code null} when no owner is assigned.
   * @param createTime The role creation time.
   * @param userCount The number of users directly assigned to the role.
   * @param groupCount The number of groups directly assigned to the role.
   */
  public RoleSummaryDTO(
      String role, @Nullable OwnerDTO owner, Instant createTime, int userCount, int groupCount) {
    this.role = role;
    this.owner = owner;
    this.createTime = createTime;
    this.userCount = userCount;
    this.groupCount = groupCount;
    this.assignCount = userCount + groupCount;
    validate();
  }

  /**
   * Validates the role summary.
   *
   * @throws IllegalArgumentException If a required field is missing or a count is negative.
   */
  public void validate() throws IllegalArgumentException {
    Preconditions.checkArgument(StringUtils.isNotBlank(role), "role cannot be blank");
    Preconditions.checkArgument(createTime != null, "create time cannot be null");
    Preconditions.checkArgument(userCount >= 0, "user count cannot be negative");
    Preconditions.checkArgument(groupCount >= 0, "group count cannot be negative");
    Preconditions.checkArgument(
        assignCount == userCount + groupCount,
        "assign count must equal user count plus group count");
    if (owner != null) {
      Preconditions.checkArgument(
          StringUtils.isNotBlank(owner.name()), "owner name cannot be blank");
      Preconditions.checkArgument(owner.type() != null, "owner type cannot be null");
    }
  }
}
