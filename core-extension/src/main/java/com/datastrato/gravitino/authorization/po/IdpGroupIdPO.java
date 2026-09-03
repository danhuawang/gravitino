/*
 * Copyright 2026 Datastrato Inc.
 */
package com.datastrato.gravitino.authorization.po;

/**
 * IdP group id lookup row for Directory User creation.
 *
 * <p>Populated by a SELECT against {@code idp_group_meta}; not persisted by itself.
 */
public class IdpGroupIdPO {

  private String groupName;
  private Long groupId;

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
   * @return Group id.
   */
  public Long getGroupId() {
    return groupId;
  }

  /**
   * @param groupId Group id.
   */
  public void setGroupId(Long groupId) {
    this.groupId = groupId;
  }
}
