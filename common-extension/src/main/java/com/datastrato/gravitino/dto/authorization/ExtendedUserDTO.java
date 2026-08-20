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
import org.apache.gravitino.authorization.User;
import org.apache.gravitino.dto.AuditDTO;
import org.apache.gravitino.dto.authorization.UserDTO;
import org.apache.gravitino.dto.util.DTOConverters;

/**
 * Enterprise user DTO for the security Users page. Extends OSS {@link UserDTO} with {@link
 * IdentitySource} derived from {@code externalId} and serialized as {@code origin}.
 */
public class ExtendedUserDTO extends UserDTO {

  @JsonProperty("origin")
  private IdentitySource origin;

  /** Default constructor for Jackson deserialization. */
  private ExtendedUserDTO() {}

  private ExtendedUserDTO(
      Long id,
      String name,
      String externalId,
      List<String> roles,
      AuditDTO audit,
      boolean enabled,
      IdentitySource origin) {
    super(id, name, externalId, roles, audit, enabled);
    this.origin = origin;
  }

  /**
   * @return {@link IdentitySource} for the security UI ({@code origin} in JSON).
   */
  public IdentitySource origin() {
    return origin;
  }

  /**
   * Builds an {@link ExtendedUserDTO} from a {@link User}.
   *
   * @param user The metalake user.
   * @return The extended user DTO.
   */
  public static ExtendedUserDTO from(User user) {
    Preconditions.checkArgument(user != null, "user cannot be null");
    Preconditions.checkArgument(StringUtils.isNotBlank(user.name()), "user name cannot be blank");
    List<String> roles = user.roles() == null ? Collections.emptyList() : user.roles();
    return new ExtendedUserDTO(
        user.id(),
        user.name(),
        user.externalId(),
        roles,
        DTOConverters.toDTO(user.auditInfo()),
        user.enabled(),
        IdentitySource.fromExternalId(user.externalId()));
  }

  /**
   * Converts users to extended DTOs.
   *
   * @param users The users.
   * @return Extended user DTOs.
   */
  public static ExtendedUserDTO[] from(User[] users) {
    Preconditions.checkArgument(users != null, "users cannot be null");
    ExtendedUserDTO[] result = new ExtendedUserDTO[users.length];
    for (int i = 0; i < users.length; i++) {
      result[i] = from(users[i]);
    }
    return result;
  }
}
