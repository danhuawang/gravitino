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
import javax.annotation.Nullable;
import org.apache.commons.lang3.StringUtils;

/**
 * Instance-scoped directory user for Configure → Directory → Users.
 *
 * <p>Rows come from {@code idp_user_meta} ({@code Local}), {@code scim_user_meta} ({@code
 * Provisioned}), or metalake-only {@code user_meta} names ({@code origin} null).
 */
public final class DirectoryUser implements DirectoryUserDTO.DirectoryUserView {

  private final String name;
  private final boolean enabled;
  @Nullable private final IdentitySource origin;
  private final List<String> groups;
  private final List<String> metalakes;
  private final boolean canDelete;
  private final boolean canSuspend;

  /**
   * Creates a directory user without mutation flags (defaults to {@code false}).
   *
   * @param name Username.
   * @param enabled Whether the user is enabled (Active / Suspended in the UI).
   * @param origin Local / Provisioned, or {@code null} when metalake-only.
   * @param groups Identity-store group names (empty for metalake-only).
   * @param metalakes Metalake names where the username exists in {@code user_meta}.
   */
  public DirectoryUser(
      String name,
      boolean enabled,
      @Nullable IdentitySource origin,
      List<String> groups,
      List<String> metalakes) {
    this(name, enabled, origin, groups, metalakes, false, false);
  }

  /**
   * Creates a directory user including mutation capability flags for the Directory list UI.
   *
   * @param name Username.
   * @param enabled Whether the user is enabled (Active / Suspended in the UI).
   * @param origin Local / Provisioned, or {@code null} when metalake-only.
   * @param groups Identity-store group names (empty for metalake-only).
   * @param metalakes Metalake names where the username exists in {@code user_meta}.
   * @param canDelete Whether the caller may delete this user.
   * @param canSuspend Whether the caller may suspend this user.
   */
  public DirectoryUser(
      String name,
      boolean enabled,
      @Nullable IdentitySource origin,
      List<String> groups,
      List<String> metalakes,
      boolean canDelete,
      boolean canSuspend) {
    Preconditions.checkArgument(StringUtils.isNotBlank(name), "name cannot be blank");
    this.name = name;
    this.enabled = enabled;
    this.origin = origin;
    this.groups = groups == null ? Collections.emptyList() : List.copyOf(groups);
    this.metalakes = metalakes == null ? Collections.emptyList() : List.copyOf(metalakes);
    this.canDelete = canDelete;
    this.canSuspend = canSuspend;
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
  @Nullable
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
  public boolean canDelete() {
    return canDelete;
  }

  @Override
  public boolean canSuspend() {
    return canSuspend;
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
        && canDelete == that.canDelete
        && canSuspend == that.canSuspend
        && Objects.equals(name, that.name)
        && origin == that.origin
        && Objects.equals(groups, that.groups)
        && Objects.equals(metalakes, that.metalakes);
  }

  @Override
  public int hashCode() {
    return Objects.hash(name, enabled, origin, groups, metalakes, canDelete, canSuspend);
  }
}
