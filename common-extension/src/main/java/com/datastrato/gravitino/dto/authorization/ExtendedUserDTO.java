/*
 * Copyright 2026 Datastrato Inc.
 */
package com.datastrato.gravitino.dto.authorization;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.google.common.base.Preconditions;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import javax.annotation.Nullable;
import org.apache.commons.lang3.StringUtils;
import org.apache.gravitino.authorization.User;
import org.apache.gravitino.dto.AuditDTO;
import org.apache.gravitino.dto.authorization.UserDTO;
import org.apache.gravitino.dto.util.DTOConverters;

/**
 * Enterprise user DTO for the security Users page. Extends OSS {@link UserDTO} with {@link
 * IdentitySource} derived from built-in IdP membership and serialized as {@code origin}, plus
 * metalake group membership for the Users table.
 */
public class ExtendedUserDTO extends UserDTO {

  /** Read model pairing a metalake user with its group names and identity origin. */
  public interface UserWithGroupNames {
    /**
     * @return The metalake user.
     */
    User user();

    /**
     * @return Metalake group names for the user.
     */
    List<String> groups();

    /**
     * @return Local / Provisioned / JIT for the security Users table.
     */
    IdentitySource origin();
  }

  @JsonProperty("origin")
  private IdentitySource origin;

  @JsonProperty("groups")
  private List<String> groups = Collections.emptyList();

  /** Default constructor for Jackson deserialization. */
  private ExtendedUserDTO() {}

  private ExtendedUserDTO(
      Long id,
      String name,
      List<String> roles,
      List<String> groups,
      AuditDTO audit,
      IdentitySource origin) {
    super(id, name, roles, audit);
    this.origin = origin;
    this.groups = groups == null ? Collections.emptyList() : groups;
  }

  /**
   * @return {@link IdentitySource} for the security UI ({@code origin} in JSON).
   */
  public IdentitySource origin() {
    return origin;
  }

  /**
   * @return Metalake group names for the security Users table.
   */
  public List<String> groups() {
    return groups;
  }

  /**
   * Builds an {@link ExtendedUserDTO} from a {@link User}, deriving {@code origin} from built-in
   * IdP membership.
   *
   * @param user The metalake user.
   * @param inBuiltInIdp {@code true} when the name exists in {@code idp_user_meta}.
   * @return The extended user DTO.
   */
  public static ExtendedUserDTO from(User user, boolean inBuiltInIdp) {
    return from(user, inBuiltInIdp, Collections.emptyList());
  }

  /**
   * Builds an {@link ExtendedUserDTO} from a {@link User}, IdP membership, and group names.
   *
   * @param user The metalake user.
   * @param inBuiltInIdp {@code true} when the name exists in {@code idp_user_meta}.
   * @param groups Metalake group names for the user.
   * @return The extended user DTO.
   */
  public static ExtendedUserDTO from(
      User user, boolean inBuiltInIdp, @Nullable List<String> groups) {
    return from(user, IdentitySource.fromIdpMembership(inBuiltInIdp), groups);
  }

  /**
   * Builds an {@link ExtendedUserDTO} from a {@link User}, identity origin, and group names.
   *
   * @param user The metalake user.
   * @param origin Local / Provisioned / JIT.
   * @param groups Metalake group names for the user.
   * @return The extended user DTO.
   */
  public static ExtendedUserDTO from(
      User user, IdentitySource origin, @Nullable List<String> groups) {
    Preconditions.checkArgument(user != null, "user cannot be null");
    Preconditions.checkArgument(origin != null, "origin cannot be null");
    Preconditions.checkArgument(StringUtils.isNotBlank(user.name()), "user name cannot be blank");
    List<String> roles = user.roles() == null ? Collections.emptyList() : user.roles();
    List<String> groupNames = groups == null ? Collections.emptyList() : groups;
    return new ExtendedUserDTO(
        user.id(), user.name(), roles, groupNames, DTOConverters.toDTO(user.auditInfo()), origin);
  }

  /**
   * Converts users with group names to extended DTOs.
   *
   * @param usersWithGroups Users bundled with metalake group names.
   * @return Extended user DTOs.
   */
  public static ExtendedUserDTO[] from(Iterable<? extends UserWithGroupNames> usersWithGroups) {
    Preconditions.checkArgument(usersWithGroups != null, "usersWithGroups cannot be null");
    List<ExtendedUserDTO> result = new ArrayList<>();
    for (UserWithGroupNames userWithGroups : usersWithGroups) {
      result.add(from(userWithGroups.user(), userWithGroups.origin(), userWithGroups.groups()));
    }
    return result.toArray(new ExtendedUserDTO[0]);
  }
}
