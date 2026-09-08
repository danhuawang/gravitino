/*
 * Copyright 2026 Datastrato Inc.
 */
package com.datastrato.gravitino.authorization.po;

import com.datastrato.gravitino.dto.authorization.IdentitySource;
import com.google.common.base.Objects;
import com.google.common.base.Preconditions;

/** Read model for Directory Group Overview: identity-store group with membership count. */
public class DirectoryGroupDetailPO {

  private String groupName;
  private Integer originCode;
  private Integer memberCount;

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
   * @return Identity-store member count.
   */
  public Integer getMemberCount() {
    return memberCount;
  }

  /**
   * @param memberCount Identity-store member count.
   */
  public void setMemberCount(Integer memberCount) {
    this.memberCount = memberCount;
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
    if (!(o instanceof DirectoryGroupDetailPO)) {
      return false;
    }
    DirectoryGroupDetailPO that = (DirectoryGroupDetailPO) o;
    return Objects.equal(getGroupName(), that.getGroupName())
        && Objects.equal(getOriginCode(), that.getOriginCode())
        && Objects.equal(getMemberCount(), that.getMemberCount());
  }

  @Override
  public int hashCode() {
    return Objects.hashCode(getGroupName(), getOriginCode(), getMemberCount());
  }
}
