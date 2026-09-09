/*
 * Copyright 2026 Datastrato Inc.
 */
package com.datastrato.gravitino.authorization.po;

import com.datastrato.gravitino.dto.authorization.IdentitySource;
import com.google.common.base.Objects;
import javax.annotation.Nullable;
import org.apache.gravitino.storage.relational.po.ExtendedUserPO;

/**
 * Read model for list users with groups: {@code user_meta} with aggregated role and group names.
 *
 * <p>Populated by a single JOIN query; not persisted.
 */
public class UserWithGroupsPO extends ExtendedUserPO {

  private String groupNames;
  private Integer originCode;
  private Boolean enabled;
  private String externalId;

  /**
   * @return JSON array of metalake group names for the user.
   */
  public String getGroupNames() {
    return groupNames;
  }

  /**
   * @param groupNames JSON array of metalake group names for the user.
   */
  public void setGroupNames(String groupNames) {
    this.groupNames = groupNames;
  }

  /**
   * @return Origin code from SQL ({@link IdentitySource#ORIGIN_CODE_LOCAL}, {@link
   *     IdentitySource#ORIGIN_CODE_PROVISIONED}, or {@code null} when neither identity store
   *     matched).
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
   * @return Identity source for the security Users table, or {@code null} when unknown.
   */
  @Nullable
  public IdentitySource origin() {
    return IdentitySource.fromOriginCodeOrNull(originCode);
  }

  /**
   * @return Identity-store enabled flag, or {@code null} when unset.
   */
  public Boolean getEnabled() {
    return enabled;
  }

  /**
   * @param enabled Identity-store enabled flag.
   */
  public void setEnabled(Boolean enabled) {
    this.enabled = enabled;
  }

  /**
   * @return Enabled flag, defaulting to {@code true} when unset.
   */
  public boolean enabledOrDefault() {
    return enabled == null || enabled;
  }

  /**
   * @return SCIM external id when present.
   */
  public String getExternalId() {
    return externalId;
  }

  /**
   * @param externalId SCIM external id.
   */
  public void setExternalId(String externalId) {
    this.externalId = externalId;
  }

  @Override
  public boolean equals(Object o) {
    if (this == o) {
      return true;
    }
    if (!(o instanceof UserWithGroupsPO)) {
      return false;
    }
    UserWithGroupsPO that = (UserWithGroupsPO) o;
    return super.equals(o)
        && Objects.equal(getGroupNames(), that.getGroupNames())
        && Objects.equal(getOriginCode(), that.getOriginCode())
        && Objects.equal(getEnabled(), that.getEnabled())
        && Objects.equal(getExternalId(), that.getExternalId());
  }

  @Override
  public int hashCode() {
    return Objects.hashCode(
        super.hashCode(), getGroupNames(), getOriginCode(), getEnabled(), getExternalId());
  }
}
