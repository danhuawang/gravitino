/*
 * Copyright 2026 Datastrato Pvt Ltd.
 */
package com.datastrato.gravitino.authorization.mapper.provider.base;

import static org.apache.gravitino.storage.relational.mapper.GroupRoleRelMapper.GROUP_ROLE_RELATION_TABLE_NAME;
import static org.apache.gravitino.storage.relational.mapper.GroupRoleRelMapper.GROUP_TABLE_NAME;
import static org.apache.gravitino.storage.relational.mapper.RoleMetaMapper.ROLE_TABLE_NAME;
import static org.apache.gravitino.storage.relational.mapper.UserRoleRelMapper.USER_ROLE_RELATION_TABLE_NAME;
import static org.apache.gravitino.storage.relational.mapper.UserRoleRelMapper.USER_TABLE_NAME;

import com.datastrato.gravitino.authorization.mapper.DatastratoGroupMetaMapper;
import com.datastrato.gravitino.authorization.mapper.DatastratoUserMetaMapper;
import java.util.List;
import org.apache.gravitino.storage.relational.mapper.MetalakeMetaMapper;
import org.apache.gravitino.storage.relational.po.GroupRoleRelPO;
import org.apache.gravitino.storage.relational.po.UserRoleRelPO;
import org.apache.ibatis.annotations.Param;

/** Base SQL provider for enterprise principal-role assignment queries. */
public class DatastratoRoleAssignmentBaseSQLProvider {

  /**
   * Assigns multiple roles to multiple users without changing existing assignments.
   *
   * @param assignments The user-role assignments.
   * @return The batch user-role assignment SQL.
   */
  public String batchAssignRoleToUsers(@Param("assignments") List<UserRoleRelPO> assignments) {
    return batchAssignRole(USER_ROLE_RELATION_TABLE_NAME, "user_id", "userId", assignments.size());
  }

  /**
   * Assigns multiple roles to multiple groups without changing existing assignments.
   *
   * @param assignments The group-role assignments.
   * @return The batch group-role assignment SQL.
   */
  public String batchAssignRoleToGroups(@Param("assignments") List<GroupRoleRelPO> assignments) {
    return batchAssignRole(
        GROUP_ROLE_RELATION_TABLE_NAME, "group_id", "groupId", assignments.size());
  }

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

  /**
   * Lists users assigned to a role with assignment and identity-source information.
   *
   * @param metalake The metalake name.
   * @param role The role name.
   * @return The role user assignment SQL.
   */
  public String listUserAssignmentsByRole(
      @Param("metalake") String metalake, @Param("role") String role) {
    return "SELECT mt.metalake_id as requestedMetalakeId, rt.role_id as roleId,"
        + " principal.user_id as userId, principal.user_name as userName,"
        + " principal.metalake_id as metalakeId,"
        + " principal.audit_info as auditInfo,"
        + " principal.current_version as currentVersion,"
        + " principal.last_version as lastVersion, principal.deleted_at as deletedAt,"
        + " rel.audit_info as assignmentAuditInfo,"
        + " CASE WHEN EXISTS (SELECT 1 FROM "
        + DatastratoUserMetaMapper.IDP_USER_TABLE_NAME
        + " iu WHERE iu.user_name = principal.user_name AND iu.deleted_at = 0)"
        + " THEN 1 ELSE 0 END as inBuiltInIdp"
        + roleAssignmentsFrom(USER_ROLE_RELATION_TABLE_NAME)
        + " LEFT JOIN "
        + USER_TABLE_NAME
        + " principal ON principal.user_id = rel.user_id AND principal.deleted_at = 0"
        + " WHERE mt.metalake_name = #{metalake} AND mt.deleted_at = 0"
        + " ORDER BY principal.user_name";
  }

  /**
   * Lists groups assigned to a role with assignment and user-count information.
   *
   * @param metalake The metalake name.
   * @param role The role name.
   * @return The role group assignment SQL.
   */
  public String listGroupAssignmentsByRole(
      @Param("metalake") String metalake, @Param("role") String role) {
    return "SELECT mt.metalake_id as requestedMetalakeId, rt.role_id as roleId,"
        + " principal.group_id as groupId, principal.group_name as groupName,"
        + " principal.metalake_id as metalakeId,"
        + " principal.audit_info as auditInfo, principal.current_version as currentVersion,"
        + " principal.last_version as lastVersion, principal.deleted_at as deletedAt,"
        + " rel.audit_info as assignmentAuditInfo,"
        + groupUserCountSelect()
        + roleAssignmentsFrom(GROUP_ROLE_RELATION_TABLE_NAME)
        + " LEFT JOIN "
        + GROUP_TABLE_NAME
        + " principal ON principal.group_id = rel.group_id AND principal.deleted_at = 0"
        + " WHERE mt.metalake_name = #{metalake} AND mt.deleted_at = 0"
        + " ORDER BY principal.group_name";
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

  private String batchAssignRole(
      String relationTableName,
      String principalIdColumn,
      String principalIdProperty,
      int assignmentCount) {
    if (assignmentCount == 0) {
      return "SELECT 0";
    }

    return "<script>INSERT INTO "
        + relationTableName
        + " ("
        + principalIdColumn
        + ", role_id, audit_info, current_version, last_version, deleted_at)"
        + " SELECT batch_assignment.principal_id, batch_assignment.role_id,"
        + " batch_assignment.audit_info, batch_assignment.current_version,"
        + " batch_assignment.last_version, batch_assignment.deleted_at"
        + " FROM ("
        + "<foreach collection='assignments' item='item' separator=' UNION ALL '>"
        + "SELECT #{item."
        + principalIdProperty
        + "} AS principal_id, #{item.roleId} AS role_id,"
        + " #{item.auditInfo} AS audit_info, #{item.currentVersion} AS current_version,"
        + " #{item.lastVersion} AS last_version, #{item.deletedAt} AS deleted_at"
        + "</foreach>"
        + ") batch_assignment"
        + " WHERE NOT EXISTS (SELECT 1 FROM "
        + relationTableName
        + " existing_assignment WHERE existing_assignment."
        + principalIdColumn
        + " = batch_assignment.principal_id"
        + " AND existing_assignment.role_id = batch_assignment.role_id"
        + " AND existing_assignment.deleted_at = 0)"
        + "</script>";
  }

  private String roleAssignmentsFrom(String relationTableName) {
    return " FROM "
        + MetalakeMetaMapper.TABLE_NAME
        + " mt LEFT JOIN "
        + ROLE_TABLE_NAME
        + " rt ON rt.metalake_id = mt.metalake_id AND rt.role_name = #{role}"
        + " AND rt.deleted_at = 0 LEFT JOIN "
        + relationTableName
        + " rel ON rel.role_id = rt.role_id AND rel.deleted_at = 0";
  }

  private String groupUserCountSelect() {
    return " COALESCE(CASE WHEN EXISTS (SELECT 1 FROM "
        + DatastratoGroupMetaMapper.SCIM_GROUP_TABLE_NAME
        + " source_group WHERE source_group.group_name = principal.group_name"
        + " AND source_group.deleted_at = 0) THEN ("
        + scimGroupUserCountSubquery()
        + ") ELSE ("
        + localGroupUserCountSubquery()
        + ") END, 0) as userCount";
  }

  private String localGroupUserCountSubquery() {
    return "SELECT COUNT(DISTINCT ut.user_id) FROM "
        + DatastratoGroupMetaMapper.IDP_GROUP_TABLE_NAME
        + " ig INNER JOIN "
        + DatastratoGroupMetaMapper.IDP_USER_GROUP_REL_TABLE_NAME
        + " iugr ON iugr.group_id = ig.group_id AND iugr.deleted_at = 0"
        + " INNER JOIN "
        + DatastratoUserMetaMapper.IDP_USER_TABLE_NAME
        + " ium ON ium.user_id = iugr.user_id AND ium.deleted_at = 0"
        + " INNER JOIN "
        + USER_TABLE_NAME
        + " ut ON ut.metalake_id = principal.metalake_id"
        + " AND ut.user_name = ium.user_name AND ut.deleted_at = 0"
        + " WHERE ig.group_name = principal.group_name AND ig.deleted_at = 0";
  }

  private String scimGroupUserCountSubquery() {
    return "SELECT COUNT(DISTINCT ut.user_id) FROM "
        + DatastratoGroupMetaMapper.SCIM_GROUP_TABLE_NAME
        + " sg INNER JOIN "
        + DatastratoGroupMetaMapper.SCIM_USER_GROUP_REL_TABLE_NAME
        + " sur ON sur.group_id = sg.group_id AND sur.deleted_at = 0"
        + " INNER JOIN "
        + DatastratoUserMetaMapper.SCIM_USER_TABLE_NAME
        + " su ON su.user_id = sur.user_id AND su.deleted_at = 0"
        + " INNER JOIN "
        + USER_TABLE_NAME
        + " ut ON ut.metalake_id = principal.metalake_id AND ut.user_name = su.user_name"
        + " AND ut.deleted_at = 0"
        + " WHERE sg.group_name = principal.group_name AND sg.deleted_at = 0";
  }
}
