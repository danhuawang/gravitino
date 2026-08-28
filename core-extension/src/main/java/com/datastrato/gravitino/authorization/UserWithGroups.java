/*
 * Copyright 2026 Datastrato Pvt Ltd.
 */
package com.datastrato.gravitino.authorization;

import com.datastrato.gravitino.dto.authorization.ExtendedUserDTO;
import com.google.common.base.Preconditions;
import java.util.Collections;
import java.util.List;
import javax.annotation.Nullable;
import org.apache.gravitino.authorization.User;

/** A metalake user bundled with its metalake group names for list/read APIs. */
public class UserWithGroups implements ExtendedUserDTO.UserWithGroupNames {

  private final User user;
  private final List<String> groups;
  private final boolean inBuiltInIdp;

  /**
   * @param user The metalake user.
   * @param groups Metalake group names for the user.
   * @param inBuiltInIdp {@code true} when the name exists in {@code idp_user_meta}.
   */
  public UserWithGroups(User user, @Nullable List<String> groups, boolean inBuiltInIdp) {
    this.user = Preconditions.checkNotNull(user, "user cannot be null");
    this.groups = groups == null ? Collections.emptyList() : groups;
    this.inBuiltInIdp = inBuiltInIdp;
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
  public boolean inBuiltInIdp() {
    return inBuiltInIdp;
  }
}
