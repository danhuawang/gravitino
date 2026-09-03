/*
 * Copyright 2026 Datastrato Inc.
 */
package com.datastrato.gravitino.authorization.po;

import com.datastrato.gravitino.dto.authorization.IdentitySource;
import com.google.common.base.Objects;
import com.google.common.base.Preconditions;

/**
 * Read model for Directory Users: identity / metalake user with groups and metalake names.
 *
 * <p>Populated by a single JOIN query; not persisted. {@code originCode} uses {@link
 * IdentitySource} constants.
 */
public class DirectoryUserPO {

  private String userName;
  private Boolean enabled;
  private Integer originCode;
  private String groupNames;
  private String metalakeNames;

  /**
   * @return Username.
   */
  public String getUserName() {
    return userName;
  }

  /**
   * @param userName Username.
   */
  public void setUserName(String userName) {
    this.userName = userName;
  }

  /**
   * @return Whether the user is enabled.
   */
  public Boolean getEnabled() {
    return enabled;
  }

  /**
   * @param enabled Whether the user is enabled.
   */
  public void setEnabled(Boolean enabled) {
    this.enabled = enabled;
  }

  /**
   * @return Origin code: {@link IdentitySource#ORIGIN_CODE_LOCAL}, {@link
   *     IdentitySource#ORIGIN_CODE_PROVISIONED}, or {@link IdentitySource#ORIGIN_CODE_JIT}.
   */
  public Integer getOriginCode() {
    return originCode;
  }

  /**
   * @param originCode Origin code from SQL.
   */
  public void setOriginCode(Integer originCode) {
    this.originCode = originCode;
  }

  /**
   * @return JSON array of identity-store group names.
   */
  public String getGroupNames() {
    return groupNames;
  }

  /**
   * @param groupNames JSON array of identity-store group names.
   */
  public void setGroupNames(String groupNames) {
    this.groupNames = groupNames;
  }

  /**
   * @return JSON array of metalake names.
   */
  public String getMetalakeNames() {
    return metalakeNames;
  }

  /**
   * @param metalakeNames JSON array of metalake names.
   */
  public void setMetalakeNames(String metalakeNames) {
    this.metalakeNames = metalakeNames;
  }

  /**
   * @return Resolved identity source.
   */
  public IdentitySource origin() {
    Preconditions.checkArgument(originCode != null, "originCode cannot be null");
    return IdentitySource.fromOriginCode(originCode);
  }

  /**
   * @return {@code true} when enabled; defaults to {@code true} when null.
   */
  public boolean enabledOrDefault() {
    return enabled == null || enabled;
  }

  @Override
  public boolean equals(Object o) {
    if (this == o) {
      return true;
    }
    if (!(o instanceof DirectoryUserPO)) {
      return false;
    }
    DirectoryUserPO that = (DirectoryUserPO) o;
    return Objects.equal(getUserName(), that.getUserName())
        && Objects.equal(getEnabled(), that.getEnabled())
        && Objects.equal(getOriginCode(), that.getOriginCode())
        && Objects.equal(getGroupNames(), that.getGroupNames())
        && Objects.equal(getMetalakeNames(), that.getMetalakeNames());
  }

  @Override
  public int hashCode() {
    return Objects.hashCode(
        getUserName(), getEnabled(), getOriginCode(), getGroupNames(), getMetalakeNames());
  }
}
