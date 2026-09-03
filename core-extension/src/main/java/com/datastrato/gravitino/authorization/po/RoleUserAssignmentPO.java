/*
 * Copyright 2026 Datastrato Inc.
 */
package com.datastrato.gravitino.authorization.po;

import org.apache.gravitino.storage.relational.po.UserPO;

/** Persistent user data together with role-assignment and identity-source information. */
public class RoleUserAssignmentPO {
  private Long requestedMetalakeId;
  private Long roleId;
  private Long userId;
  private String userName;
  private Long metalakeId;
  private String auditInfo;
  private Long currentVersion;
  private Long lastVersion;
  private Long deletedAt;
  private String assignmentAuditInfo;
  private Boolean inBuiltInIdp;

  /**
   * Returns the requested metalake id used to distinguish a missing metalake.
   *
   * @return The requested metalake id.
   */
  public Long getRequestedMetalakeId() {
    return requestedMetalakeId;
  }

  /**
   * Returns the requested role id used to distinguish a missing role.
   *
   * @return The role id.
   */
  public Long getRoleId() {
    return roleId;
  }

  /**
   * Returns the assigned user id.
   *
   * @return The user id.
   */
  public Long getUserId() {
    return userId;
  }

  /**
   * Returns the assigned username.
   *
   * @return The username.
   */
  public String getUserName() {
    return userName;
  }

  /**
   * Returns the user's metalake id.
   *
   * @return The metalake id.
   */
  public Long getMetalakeId() {
    return metalakeId;
  }

  /**
   * Returns serialized user audit information.
   *
   * @return The user audit information.
   */
  public String getAuditInfo() {
    return auditInfo;
  }

  /**
   * Returns the user's current version.
   *
   * @return The current version.
   */
  public Long getCurrentVersion() {
    return currentVersion;
  }

  /**
   * Returns the user's last version.
   *
   * @return The last version.
   */
  public Long getLastVersion() {
    return lastVersion;
  }

  /**
   * Returns the user's deletion timestamp.
   *
   * @return The deletion timestamp.
   */
  public Long getDeletedAt() {
    return deletedAt;
  }

  /**
   * Returns serialized role-assignment audit information.
   *
   * @return The assignment audit information.
   */
  public String getAssignmentAuditInfo() {
    return assignmentAuditInfo;
  }

  /**
   * Returns whether the user exists in the built-in IdP.
   *
   * @return Whether the user is local.
   */
  public Boolean getInBuiltInIdp() {
    return inBuiltInIdp;
  }

  /**
   * Converts the principal columns to an OSS user persistent object.
   *
   * @return The user persistent object.
   */
  public UserPO toUserPO() {
    return UserPO.builder()
        .withUserId(userId)
        .withUserName(userName)
        .withMetalakeId(metalakeId)
        .withAuditInfo(auditInfo)
        .withCurrentVersion(currentVersion)
        .withLastVersion(lastVersion)
        .withDeletedAt(deletedAt)
        .build();
  }
}
