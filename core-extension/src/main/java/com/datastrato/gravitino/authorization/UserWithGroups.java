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

  /**
   * @param user The metalake user.
   * @param groups Metalake group names for the user.
   * @param origin Local / Provisioned / JIT from identity-store presence.
   */
  public UserWithGroups(User user, @Nullable List<String> groups, IdentitySource origin) {
    this.user = Preconditions.checkNotNull(user, "user cannot be null");
    this.groups = groups == null ? Collections.emptyList() : groups;
    this.origin = Preconditions.checkNotNull(origin, "origin cannot be null");
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
  public IdentitySource origin() {
    return origin;
  }
}
