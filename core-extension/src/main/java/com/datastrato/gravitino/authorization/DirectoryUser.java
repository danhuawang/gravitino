/*
 * Copyright 2026 Datastrato Inc.
 */
package com.datastrato.gravitino.authorization;

import com.datastrato.gravitino.dto.authorization.DirectoryUserDTO;
import com.datastrato.gravitino.dto.authorization.IdentitySource;
import com.google.common.base.Preconditions;
import java.util.Collections;
import java.util.List;
import java.util.Objects;
import org.apache.commons.lang3.StringUtils;

/**
 * Instance-scoped directory user for Configure → Directory → Users.
 *
 * <p>Rows come from {@code idp_user_meta} ({@code Local}), {@code scim_user_meta} ({@code
 * Provisioned}), or metalake {@code user_meta} only ({@code JIT}).
 */
public final class DirectoryUser implements DirectoryUserDTO.DirectoryUserView {

  private final String name;
  private final boolean enabled;
  private final IdentitySource origin;
  private final List<String> groups;
  private final List<String> metalakes;

  /**
   * Creates a directory user.
   *
   * @param name Username.
   * @param enabled Whether the user is enabled (Active / Suspended in the UI).
   * @param origin Local, Provisioned, or JIT.
   * @param groups Identity-store group names (empty for JIT).
   * @param metalakes Metalake names where the username exists in {@code user_meta}.
   */
  public DirectoryUser(
      String name,
      boolean enabled,
      IdentitySource origin,
      List<String> groups,
      List<String> metalakes) {
    Preconditions.checkArgument(StringUtils.isNotBlank(name), "name cannot be blank");
    Preconditions.checkArgument(origin != null, "origin cannot be null");
    this.name = name;
    this.enabled = enabled;
    this.origin = origin;
    this.groups = groups == null ? Collections.emptyList() : List.copyOf(groups);
    this.metalakes = metalakes == null ? Collections.emptyList() : List.copyOf(metalakes);
  }

  @Override
  public String name() {
    return name;
  }

  @Override
  public boolean enabled() {
    return enabled;
  }

  @Override
  public IdentitySource origin() {
    return origin;
  }

  @Override
  public List<String> groups() {
    return groups;
  }

  @Override
  public List<String> metalakes() {
    return metalakes;
  }

  @Override
  public boolean equals(Object o) {
    if (this == o) {
      return true;
    }
    if (!(o instanceof DirectoryUser)) {
      return false;
    }
    DirectoryUser that = (DirectoryUser) o;
    return enabled == that.enabled
        && Objects.equals(name, that.name)
        && origin == that.origin
        && Objects.equals(groups, that.groups)
        && Objects.equals(metalakes, that.metalakes);
  }

  @Override
  public int hashCode() {
    return Objects.hash(name, enabled, origin, groups, metalakes);
  }
}
