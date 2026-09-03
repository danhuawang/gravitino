/*
 * Copyright 2026 Datastrato Inc.
 */
package com.datastrato.gravitino.authorization.po;

/**
 * IdP user id lookup row for Directory Group creation.
 *
 * <p>Populated by a SELECT against {@code idp_user_meta}; not persisted by itself.
 */
public class IdpUserIdPO {

  private String userName;
  private Long userId;

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
}
