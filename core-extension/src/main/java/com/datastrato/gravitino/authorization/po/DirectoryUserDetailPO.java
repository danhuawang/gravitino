/*
 * Copyright 2026 Datastrato Inc.
 */
package com.datastrato.gravitino.authorization.po;

import com.datastrato.gravitino.dto.authorization.IdentitySource;
import com.google.common.base.Objects;
import com.google.common.base.Preconditions;
import javax.annotation.Nullable;

/**
 * Read model for Directory User Overview: identity-store user with optional SCIM {@code
 * externalId}.
 */
public class DirectoryUserDetailPO {

  private String userName;
  private Boolean enabled;
  private Integer originCode;
  private String externalId;

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
   * @return Origin code: {@link IdentitySource#ORIGIN_CODE_LOCAL} or {@link
   *     IdentitySource#ORIGIN_CODE_PROVISIONED}.
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
   * @return SCIM external id when Provisioned; may be null.
   */
  @Nullable
  public String getExternalId() {
    return externalId;
  }

  /**
   * @param externalId SCIM external id.
   */
  public void setExternalId(String externalId) {
    this.externalId = externalId;
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
    if (!(o instanceof DirectoryUserDetailPO)) {
      return false;
    }
    DirectoryUserDetailPO that = (DirectoryUserDetailPO) o;
    return Objects.equal(getUserName(), that.getUserName())
        && Objects.equal(getEnabled(), that.getEnabled())
        && Objects.equal(getOriginCode(), that.getOriginCode())
        && Objects.equal(getExternalId(), that.getExternalId());
  }

  @Override
  public int hashCode() {
    return Objects.hashCode(getUserName(), getEnabled(), getOriginCode(), getExternalId());
  }
}
