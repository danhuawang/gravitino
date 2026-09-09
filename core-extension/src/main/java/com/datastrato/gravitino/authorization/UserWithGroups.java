/*
 * Copyright 2026 Datastrato Inc.
 */
package com.datastrato.gravitino.authorization;

import com.datastrato.gravitino.dto.authorization.ExtendedUserDTO;
import com.datastrato.gravitino.dto.authorization.IdentitySource;
import com.google.common.base.Preconditions;
import java.util.Collections;
import java.util.List;
import javax.annotation.Nullable;
import org.apache.gravitino.authorization.User;

/** A metalake user bundled with its metalake group names for list/read APIs. */
public class UserWithGroups implements ExtendedUserDTO.UserWithGroupNames {

  private final User user;
  private final List<String> groups;
  private final IdentitySource origin;
  private final boolean enabled;
  private final String externalId;

  /**
   * @param user The metalake user.
   * @param groups Metalake group names for the user.
   * @param origin Local / Provisioned when known; {@code null} when neither identity store matched.
   * @param enabled Identity-store enabled flag.
   * @param externalId SCIM external id when Provisioned; otherwise {@code null}.
   */
  public UserWithGroups(
      User user,
      @Nullable List<String> groups,
      @Nullable IdentitySource origin,
      boolean enabled,
      @Nullable String externalId) {
    this.user = Preconditions.checkNotNull(user, "user cannot be null");
    this.groups = groups == null ? Collections.emptyList() : groups;
    this.origin = origin;
    this.enabled = enabled;
    this.externalId = externalId;
  }

  /**
   * @param user The metalake user.
   * @param groups Metalake group names for the user.
   * @param origin Local / Provisioned when known; {@code null} when neither identity store matched.
   */
  public UserWithGroups(User user, @Nullable List<String> groups, @Nullable IdentitySource origin) {
    this(user, groups, origin, true, null);
  }

  @Override
  public User user() {
    return user;
  }

  @Override
  public List<String> groups() {
    return groups;
  }

  @Override
  @Nullable
  public IdentitySource origin() {
    return origin;
  }

  @Override
  public boolean enabled() {
    return enabled;
  }

  @Override
  @Nullable
  public String externalId() {
    return externalId;
  }
}
