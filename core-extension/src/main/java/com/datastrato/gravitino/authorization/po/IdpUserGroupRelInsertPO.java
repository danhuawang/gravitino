/*
 * Copyright 2026 Datastrato Inc.
 */
package com.datastrato.gravitino.authorization.po;

/**
 * Insert row for {@code idp_user_group_rel} when creating a Directory User.
 *
 * <p>Used only as a MyBatis parameter object.
 */
public class IdpUserGroupRelInsertPO {

  private Long id;
  private Long userId;
  private Long groupId;

  /**
   * Creates a relation insert row.
   *
   * @param id Relation id.
   * @param userId IdP user id.
   * @param groupId IdP group id.
   */
  public IdpUserGroupRelInsertPO(Long id, Long userId, Long groupId) {
    this.id = id;
    this.userId = userId;
    this.groupId = groupId;
  }

  /** Default constructor for MyBatis. */
  public IdpUserGroupRelInsertPO() {}

  /**
   * @return Relation id.
   */
  public Long getId() {
    return id;
  }

  /**
   * @param id Relation id.
   */
  public void setId(Long id) {
    this.id = id;
  }

  /**
   * @return User id.
   */
  public Long getUserId() {
    return userId;
  }

  /**
   * @param userId User id.
   */
  public void setUserId(Long userId) {
    this.userId = userId;
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
