/*
 * Copyright 2026 Datastrato Pvt Ltd.
 */
package com.datastrato.gravitino.authorization.mapper;

import com.datastrato.gravitino.authorization.po.RoleAssignmentPO;
import com.datastrato.gravitino.authorization.po.RoleGroupAssignmentPO;
import com.datastrato.gravitino.authorization.po.RoleUserAssignmentPO;
import java.util.List;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.SelectProvider;

/** Enterprise MyBatis mapper for principal-role assignment queries. */
public interface DatastratoRoleAssignmentMapper {

  /**
   * Lists role assignments for a user.
   *
   * @param metalake The metalake name.
   * @param user The user name.
   * @return The assigned roles and assignment audit information.
   */
  @SelectProvider(
      type = DatastratoRoleAssignmentSQLProviderFactory.class,
      method = "listRoleAssignmentsByUser")
  List<RoleAssignmentPO> listRoleAssignmentsByUser(
      @Param("metalake") String metalake, @Param("principal") String user);

  /**
   * Lists role assignments for a group.
   *
   * @param metalake The metalake name.
   * @param group The group name.
   * @return The assigned roles and assignment audit information.
   */
  @SelectProvider(
      type = DatastratoRoleAssignmentSQLProviderFactory.class,
      method = "listRoleAssignmentsByGroup")
  List<RoleAssignmentPO> listRoleAssignmentsByGroup(
      @Param("metalake") String metalake, @Param("principal") String group);

  /**
   * Lists users assigned to a role with assignment and identity-source information.
   *
   * @param metalake The metalake name.
   * @param role The role name.
   * @return The assigned users.
   */
  @SelectProvider(
      type = DatastratoRoleAssignmentSQLProviderFactory.class,
      method = "listUserAssignmentsByRole")
  List<RoleUserAssignmentPO> listUserAssignmentsByRole(
      @Param("metalake") String metalake, @Param("role") String role);

  /**
   * Lists groups assigned to a role with assignment and user-count information.
   *
   * @param metalake The metalake name.
   * @param role The role name.
   * @return The assigned groups.
   */
  @SelectProvider(
      type = DatastratoRoleAssignmentSQLProviderFactory.class,
      method = "listGroupAssignmentsByRole")
  List<RoleGroupAssignmentPO> listGroupAssignmentsByRole(
      @Param("metalake") String metalake, @Param("role") String role);
}
