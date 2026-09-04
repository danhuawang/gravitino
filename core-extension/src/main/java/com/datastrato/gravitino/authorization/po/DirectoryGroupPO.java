/*
 * Copyright 2026 Datastrato Inc.
 */
package com.datastrato.gravitino.authorization.po;

import com.datastrato.gravitino.dto.authorization.IdentitySource;
import com.google.common.base.Objects;
import com.google.common.base.Preconditions;

/**
 * Read model for Directory Groups: identity / metalake group with member count and metalake names.
 *
 * <p>Populated by a single JOIN query; not persisted. {@code originCode} uses {@link
 * IdentitySource} constants.
 */
public class DirectoryGroupPO {

  private String groupName;
  private Integer memberCount;
  private Integer originCode;
  private String metalakeNames;

  /**
   * @return Group name.
   */
  public String getGroupName() {
    return groupName;
  }

  /**
   * @param groupName Group name.
   */
  public void setGroupName(String groupName) {
    this.groupName = groupName;
  }

  /**
   * @return Member count.
   */
  public Integer getMemberCount() {
    return memberCount;
  }

  /**
   * @param memberCount Member count.
   */
  public void setMemberCount(Integer memberCount) {
    this.memberCount = memberCount;
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
   * @return Member count; defaults to {@code 0} when null.
   */
  public int memberCountOrZero() {
    return memberCount == null ? 0 : memberCount;
  }

  @Override
  public boolean equals(Object o) {
    if (this == o) {
      return true;
    }
    if (!(o instanceof DirectoryGroupPO)) {
      return false;
    }
    DirectoryGroupPO that = (DirectoryGroupPO) o;
    return Objects.equal(getGroupName(), that.getGroupName())
        && Objects.equal(getMemberCount(), that.getMemberCount())
        && Objects.equal(getOriginCode(), that.getOriginCode())
        && Objects.equal(getMetalakeNames(), that.getMetalakeNames());
  }

  @Override
  public int hashCode() {
    return Objects.hashCode(getGroupName(), getMemberCount(), getOriginCode(), getMetalakeNames());
  }
}
