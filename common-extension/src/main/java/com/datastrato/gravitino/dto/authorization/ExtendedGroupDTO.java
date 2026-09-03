/*
 * Copyright 2026 Datastrato Inc.
 */
package com.datastrato.gravitino.dto.authorization;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.google.common.base.Preconditions;
import java.util.Collections;
import java.util.List;
import org.apache.commons.lang3.StringUtils;
import org.apache.gravitino.authorization.Group;
import org.apache.gravitino.dto.AuditDTO;
import org.apache.gravitino.dto.authorization.GroupDTO;
import org.apache.gravitino.dto.util.DTOConverters;

/**
 * Enterprise group DTO for the security Groups page. Extends OSS {@link GroupDTO} with {@link
 * IdentitySource} serialized as {@code origin}.
 */
public class ExtendedGroupDTO extends GroupDTO {

  @JsonProperty("origin")
  private IdentitySource origin;

  @JsonProperty("userCount")
  private int userCount;

  /** Default constructor for Jackson deserialization. */
  private ExtendedGroupDTO() {}

  private ExtendedGroupDTO(
      Long id,
      String name,
      List<String> roles,
      AuditDTO audit,
      IdentitySource origin,
      int userCount) {
    super(id, name, roles, audit);
    this.origin = origin;
    this.userCount = userCount;
  }

  /**
   * @return {@link IdentitySource} for the security UI ({@code origin} in JSON).
   */
  public IdentitySource origin() {
    return origin;
  }

  /**
   * @return Number of metalake users in the group for the security Groups table.
   */
  public int userCount() {
    return userCount;
  }

  /**
   * Builds an {@link ExtendedGroupDTO} from a {@link Group}, IdP membership, and user count.
   *
   * @param group The metalake group.
   * @param inBuiltInIdp {@code true} when the name exists in {@code idp_group_meta}.
   * @param userCount Number of metalake users in the group.
   * @return The extended group DTO.
   */
  public static ExtendedGroupDTO from(Group group, boolean inBuiltInIdp, int userCount) {
    return from(group, IdentitySource.fromIdpMembership(inBuiltInIdp), userCount);
  }

  private static ExtendedGroupDTO from(Group group, IdentitySource origin, int userCount) {
    Preconditions.checkArgument(group != null, "group cannot be null");
    Preconditions.checkArgument(StringUtils.isNotBlank(group.name()), "group name cannot be blank");
    List<String> roles = group.roles() == null ? Collections.emptyList() : group.roles();
    return new ExtendedGroupDTO(
        group.id(), group.name(), roles, DTOConverters.toDTO(group.auditInfo()), origin, userCount);
  }
}
