/*
 * Copyright 2026 Datastrato Inc.
 */
package com.datastrato.gravitino.authorization.po;

import javax.annotation.Nullable;

/**
 * Read model for a username's metalake membership and optional direct role assignment.
 *
 * <p>Populated by JOIN over {@code user_meta} / {@code metalake_meta} / {@code user_role_rel} /
 * {@code role_meta}; not persisted. When the user has no roles in a metalake, role fields are
 * {@code null}.
 */
public class UserMetalakeRolePO {

  private String metalakeName;

  @Nullable private Long roleId;
  @Nullable private String roleName;

  /**
   * @return Metalake name.
   */
  public String getMetalakeName() {
    return metalakeName;
  }

  /**
   * @param metalakeName Metalake name.
   */
  public void setMetalakeName(String metalakeName) {
    this.metalakeName = metalakeName;
  }

  /**
   * @return Role id, or {@code null} when the metalake user has no direct roles.
   */
  @Nullable
  public Long getRoleId() {
    return roleId;
  }

  /**
   * @param roleId Role id, or {@code null} when none.
   */
  public void setRoleId(@Nullable Long roleId) {
    this.roleId = roleId;
  }

  /**
   * @return Role name, or {@code null} when the metalake user has no direct roles.
   */
  @Nullable
  public String getRoleName() {
    return roleName;
  }

  /**
   * @param roleName Role name, or {@code null} when none.
   */
  public void setRoleName(@Nullable String roleName) {
    this.roleName = roleName;
  }
}
