/*
 * Copyright 2026 Datastrato Pvt Ltd.
 */
package com.datastrato.gravitino.authorization.mapper;

import com.datastrato.gravitino.authorization.po.RoleAssignmentPO;
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
}
