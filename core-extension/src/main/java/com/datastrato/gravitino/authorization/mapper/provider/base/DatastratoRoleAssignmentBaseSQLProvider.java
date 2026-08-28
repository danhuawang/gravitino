/*
 * Copyright 2026 Datastrato Pvt Ltd.
 */
package com.datastrato.gravitino.authorization.mapper.provider.base;

import static org.apache.gravitino.storage.relational.mapper.GroupRoleRelMapper.GROUP_ROLE_RELATION_TABLE_NAME;
import static org.apache.gravitino.storage.relational.mapper.GroupRoleRelMapper.GROUP_TABLE_NAME;
import static org.apache.gravitino.storage.relational.mapper.RoleMetaMapper.ROLE_TABLE_NAME;
import static org.apache.gravitino.storage.relational.mapper.UserRoleRelMapper.USER_ROLE_RELATION_TABLE_NAME;
import static org.apache.gravitino.storage.relational.mapper.UserRoleRelMapper.USER_TABLE_NAME;

import org.apache.gravitino.storage.relational.mapper.MetalakeMetaMapper;
import org.apache.ibatis.annotations.Param;

/** Base SQL provider for enterprise principal-role assignment queries. */
public class DatastratoRoleAssignmentBaseSQLProvider {

  /**
   * Lists role assignments for a user.
   *
   * @param metalake The metalake name.
   * @param user The user name.
   * @return The list user role assignments SQL.
   */
  public String listRoleAssignmentsByUser(
      @Param("metalake") String metalake, @Param("principal") String user) {
    return listRoleAssignments(
        USER_TABLE_NAME, USER_ROLE_RELATION_TABLE_NAME, "user_id", "user_name");
  }

  /**
   * Lists role assignments for a group.
   *
   * @param metalake The metalake name.
   * @param group The group name.
   * @return The list group role assignments SQL.
   */
  public String listRoleAssignmentsByGroup(
      @Param("metalake") String metalake, @Param("principal") String group) {
    return listRoleAssignments(
        GROUP_TABLE_NAME, GROUP_ROLE_RELATION_TABLE_NAME, "group_id", "group_name");
  }

  private String listRoleAssignments(
      String principalTableName,
      String relationTableName,
      String principalIdColumn,
      String principalNameColumn) {
    return "SELECT mt.metalake_id as requestedMetalakeId,"
        + " principal."
        + principalIdColumn
        + " as principalId,"
        + " rt.role_id as roleId, rt.role_name as roleName,"
        + " rt.metalake_id as metalakeId, rt.properties as properties,"
        + " rt.audit_info as roleAuditInfo, rel.audit_info as assignmentAuditInfo,"
        + " rt.current_version as currentVersion, rt.last_version as lastVersion,"
        + " rt.deleted_at as deletedAt"
        + " FROM "
        + MetalakeMetaMapper.TABLE_NAME
        + " mt LEFT JOIN "
        + principalTableName
        + " principal ON principal.metalake_id = mt.metalake_id"
        + " AND principal."
        + principalNameColumn
        + " = #{principal} AND principal.deleted_at = 0"
        + " LEFT JOIN "
        + relationTableName
        + " rel ON principal."
        + principalIdColumn
        + " = rel."
        + principalIdColumn
        + " AND rel.deleted_at = 0"
        + " LEFT JOIN "
        + ROLE_TABLE_NAME
        + " rt ON rt.role_id = rel.role_id AND rt.deleted_at = 0"
        + " WHERE mt.metalake_name = #{metalake} AND mt.deleted_at = 0"
        + " ORDER BY rt.role_name";
  }
}
