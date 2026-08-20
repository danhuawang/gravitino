/*
 * Copyright 2026 Datastrato Pvt Ltd.
 * This software is licensed under the Apache License version 2.
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
 * IdentitySource} derived from {@code externalId} and serialized as {@code origin}.
 */
public class ExtendedGroupDTO extends GroupDTO {

  @JsonProperty("origin")
  private IdentitySource origin;

  /** Default constructor for Jackson deserialization. */
  private ExtendedGroupDTO() {}

  private ExtendedGroupDTO(
      Long id,
      String name,
      String externalId,
      List<String> roles,
      AuditDTO audit,
      IdentitySource origin) {
    super(id, name, externalId, roles, audit);
    this.origin = origin;
  }

  /**
   * @return {@link IdentitySource} for the security UI ({@code origin} in JSON).
   */
  public IdentitySource origin() {
    return origin;
  }

  /**
   * Builds an {@link ExtendedGroupDTO} from a {@link Group}.
   *
   * @param group The metalake group.
   * @return The extended group DTO.
   */
  public static ExtendedGroupDTO from(Group group) {
    Preconditions.checkArgument(group != null, "group cannot be null");
    Preconditions.checkArgument(StringUtils.isNotBlank(group.name()), "group name cannot be blank");
    List<String> roles = group.roles() == null ? Collections.emptyList() : group.roles();
    return new ExtendedGroupDTO(
        group.id(),
        group.name(),
        group.externalId(),
        roles,
        DTOConverters.toDTO(group.auditInfo()),
        IdentitySource.fromExternalId(group.externalId()));
  }

  /**
   * Converts groups to extended DTOs.
   *
   * @param groups The groups.
   * @return Extended group DTOs.
   */
  public static ExtendedGroupDTO[] from(Group[] groups) {
    Preconditions.checkArgument(groups != null, "groups cannot be null");
    ExtendedGroupDTO[] result = new ExtendedGroupDTO[groups.length];
    for (int i = 0; i < groups.length; i++) {
      result[i] = from(groups[i]);
    }
    return result;
  }
}
