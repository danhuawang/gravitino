/*
 * Copyright 2026 Datastrato Inc.
 */
package com.datastrato.gravitino.authorization.po;

import com.datastrato.gravitino.dto.authorization.IdentitySource;
import com.google.common.base.Objects;
import javax.annotation.Nullable;

/**
 * Read model for Directory User Overview: identity-store user with optional SCIM {@code externalId}
 * and {@code audit_info} for Created ({@code createTime}).
 */
public class DirectoryUserDetailPO {

  private String userName;
  private Boolean enabled;
  private Integer originCode;
  private String externalId;
  private String auditInfo;

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
   * @return Serialized identity-store {@code audit_info}; may be null.
   */
  @Nullable
  public String getAuditInfo() {
    return auditInfo;
  }

  /**
   * @param auditInfo Serialized {@code audit_info}.
   */
  public void setAuditInfo(String auditInfo) {
    this.auditInfo = auditInfo;
  }

  /**
   * @return Resolved identity source, or {@code null} when metalake-only.
   */
  @Nullable
  public IdentitySource origin() {
    return IdentitySource.fromOriginCodeOrNull(originCode);
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
        && Objects.equal(getExternalId(), that.getExternalId())
        && Objects.equal(getAuditInfo(), that.getAuditInfo());
  }

  @Override
  public int hashCode() {
    return Objects.hashCode(
        getUserName(), getEnabled(), getOriginCode(), getExternalId(), getAuditInfo());
  }
}
