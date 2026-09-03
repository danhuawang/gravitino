/*
 * Copyright 2026 Datastrato Inc.
 */
package com.datastrato.gravitino.authorization.po;

import org.apache.gravitino.storage.relational.po.GroupPO;

/** Persistent group data together with role-assignment and user-count information. */
public class RoleGroupAssignmentPO {
  private Long requestedMetalakeId;
  private Long roleId;
  private Long groupId;
  private String groupName;
  private Long metalakeId;
  private String auditInfo;
  private Long currentVersion;
  private Long lastVersion;
  private Long deletedAt;
  private String assignmentAuditInfo;
  private Integer userCount;

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
   * Returns the assigned group id.
   *
   * @return The group id.
   */
  public Long getGroupId() {
    return groupId;
  }

  /**
   * Returns the assigned group name.
   *
   * @return The group name.
   */
  public String getGroupName() {
    return groupName;
  }

  /**
   * Returns the group's metalake id.
   *
   * @return The metalake id.
   */
  public Long getMetalakeId() {
    return metalakeId;
  }

  /**
   * Returns serialized group audit information.
   *
   * @return The group audit information.
   */
  public String getAuditInfo() {
    return auditInfo;
  }

  /**
   * Returns the group's current version.
   *
   * @return The current version.
   */
  public Long getCurrentVersion() {
    return currentVersion;
  }

  /**
   * Returns the group's last version.
   *
   * @return The last version.
   */
  public Long getLastVersion() {
    return lastVersion;
  }

  /**
   * Returns the group's deletion timestamp.
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
   * Returns the number of metalake users in the group.
   *
   * @return The group user count.
   */
  public Integer getUserCount() {
    return userCount;
  }

  /**
   * Converts the principal columns to an OSS group persistent object.
   *
   * @return The group persistent object.
   */
  public GroupPO toGroupPO() {
    return GroupPO.builder()
        .withGroupId(groupId)
        .withGroupName(groupName)
        .withMetalakeId(metalakeId)
        .withAuditInfo(auditInfo)
        .withCurrentVersion(currentVersion)
        .withLastVersion(lastVersion)
        .withDeletedAt(deletedAt)
        .build();
  }
}
