/*
 * Copyright 2026 Datastrato Inc.
 */
package com.datastrato.gravitino.authorization.po;

import com.datastrato.gravitino.authorization.mapper.IdpNameStatusPO;
import com.google.common.base.Objects;
import org.apache.gravitino.storage.relational.po.ExtendedUserPO;

/**
 * Read model for list users with groups: {@code user_meta} with aggregated role and group names.
 *
 * <p>Populated by a single JOIN query; not persisted.
 */
public class UserWithGroupsPO extends ExtendedUserPO {

  private String groupNames;
  private Integer inBuiltInIdp;

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
   * @return {@code 1} when the name exists in {@code idp_user_meta}, otherwise {@code 0}.
   */
  public Integer getInBuiltInIdp() {
    return inBuiltInIdp;
  }

  /**
   * @param inBuiltInIdp {@code 1} when the name exists in {@code idp_user_meta}.
   */
  public void setInBuiltInIdp(Integer inBuiltInIdp) {
    this.inBuiltInIdp = inBuiltInIdp;
  }

  /**
   * @return {@code true} when the name exists in {@code idp_user_meta}.
   */
  public boolean inBuiltInIdp() {
    return IdpNameStatusPO.isFlagTrue(inBuiltInIdp);
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
        && Objects.equal(getInBuiltInIdp(), that.getInBuiltInIdp());
  }

  @Override
  public int hashCode() {
    return Objects.hashCode(super.hashCode(), getGroupNames(), getInBuiltInIdp());
  }
}
