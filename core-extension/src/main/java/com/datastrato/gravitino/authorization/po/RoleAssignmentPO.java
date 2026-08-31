/*
 * Copyright 2026 Datastrato Inc.
 */
package com.datastrato.gravitino.authorization.po;

import org.apache.gravitino.storage.relational.po.RolePO;

/** Persistent role data together with the audit information of a principal-role assignment. */
public class RoleAssignmentPO {

  private Long roleId;
  private Long requestedMetalakeId;
  private Long principalId;
  private String roleName;
  private Long metalakeId;
  private String properties;
  private String roleAuditInfo;
  private String assignmentAuditInfo;
  private Long currentVersion;
  private Long lastVersion;
  private Long deletedAt;

  /**
   * Returns the requested metalake id.
   *
   * @return The requested metalake id.
   */
  public Long getRequestedMetalakeId() {
    return requestedMetalakeId;
  }

  /**
   * Returns the principal id, or {@code null} when the requested principal does not exist.
   *
   * @return The principal id.
   */
  public Long getPrincipalId() {
    return principalId;
  }

  /**
   * Returns the role id.
   *
   * @return The role id.
   */
  public Long getRoleId() {
    return roleId;
  }

  /**
   * Returns the role name.
   *
   * @return The role name.
   */
  public String getRoleName() {
    return roleName;
  }

  /**
   * Returns the metalake id.
   *
   * @return The metalake id.
   */
  public Long getMetalakeId() {
    return metalakeId;
  }

  /**
   * Returns the role properties.
   *
   * @return The serialized role properties.
   */
  public String getProperties() {
    return properties;
  }

  /**
   * Returns the role audit information.
   *
   * @return The serialized role audit information.
   */
  public String getRoleAuditInfo() {
    return roleAuditInfo;
  }

  /**
   * Returns the assignment audit information.
   *
   * @return The serialized assignment audit information.
   */
  public String getAssignmentAuditInfo() {
    return assignmentAuditInfo;
  }

  /**
   * Returns the current version.
   *
   * @return The current version.
   */
  public Long getCurrentVersion() {
    return currentVersion;
  }

  /**
   * Returns the last version.
   *
   * @return The last version.
   */
  public Long getLastVersion() {
    return lastVersion;
  }

  /**
   * Returns the deletion timestamp.
   *
   * @return The deletion timestamp.
   */
  public Long getDeletedAt() {
    return deletedAt;
  }

  /**
   * Converts this assignment projection to a role persistent object.
   *
   * @return The role persistent object.
   */
  public RolePO toRolePO() {
    return RolePO.builder()
        .withRoleId(roleId)
        .withRoleName(roleName)
        .withMetalakeId(metalakeId)
        .withProperties(properties)
        .withAuditInfo(roleAuditInfo)
        .withCurrentVersion(currentVersion)
        .withLastVersion(lastVersion)
        .withDeletedAt(deletedAt)
        .build();
  }
}
