/*
 * Copyright 2026 Datastrato Pvt Ltd.
 */
package com.datastrato.gravitino.authorization.mapper.provider.base;

import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

/** Tests principal-role assignment SQL generation. */
public class TestDatastratoRoleAssignmentBaseSQLProvider {

  private final DatastratoRoleAssignmentBaseSQLProvider provider =
      new DatastratoRoleAssignmentBaseSQLProvider();

  /** Tests the user assignment query and its principal-preserving joins. */
  @Test
  public void testListRoleAssignmentsByUser() {
    String sql = provider.listRoleAssignmentsByUser("metalake1", "user1");

    assertTrue(sql.contains("FROM metalake_meta mt LEFT JOIN user_meta principal"));
    assertTrue(sql.contains("principal.user_name = #{principal}"));
    assertTrue(
        sql.contains(
            "LEFT JOIN user_role_rel rel ON principal.user_id = rel.user_id"
                + " AND rel.deleted_at = 0"));
    assertTrue(
        sql.contains("LEFT JOIN role_meta rt ON rt.role_id = rel.role_id AND rt.deleted_at = 0"));
    assertTrue(sql.contains("WHERE mt.metalake_name = #{metalake} AND mt.deleted_at = 0"));
  }

  /** Tests the group assignment query and its principal-preserving joins. */
  @Test
  public void testListRoleAssignmentsByGroup() {
    String sql = provider.listRoleAssignmentsByGroup("metalake1", "group1");

    assertTrue(sql.contains("FROM metalake_meta mt LEFT JOIN group_meta principal"));
    assertTrue(sql.contains("principal.group_name = #{principal}"));
    assertTrue(
        sql.contains(
            "LEFT JOIN group_role_rel rel ON principal.group_id = rel.group_id"
                + " AND rel.deleted_at = 0"));
    assertTrue(sql.contains("mt.metalake_id as requestedMetalakeId"));
    assertTrue(sql.contains("principal.group_id as principalId"));
    assertTrue(sql.contains("ORDER BY rt.role_name"));
  }

  /** Tests role-side user assignments include relation audit and identity origin. */
  @Test
  public void testListUserAssignmentsByRole() {
    String sql = provider.listUserAssignmentsByRole("metalake1", "role1");

    assertTrue(sql.contains("mt.metalake_id as requestedMetalakeId"));
    assertTrue(sql.contains("rt.role_id as roleId"));
    assertTrue(sql.contains("rel.audit_info as assignmentAuditInfo"));
    assertTrue(sql.contains("FROM idp_user_meta iu"));
    assertTrue(sql.contains("as inBuiltInIdp"));
    assertTrue(sql.contains("rt.role_name = #{role}"));
    assertTrue(sql.contains("LEFT JOIN user_role_rel rel"));
    assertTrue(sql.contains("LEFT JOIN user_meta principal"));
    assertTrue(sql.contains("WHERE mt.metalake_name = #{metalake}"));
    assertTrue(sql.contains("ORDER BY principal.user_name"));
  }

  /** Tests role-side group assignments include relation audit and membership counts. */
  @Test
  public void testListGroupAssignmentsByRole() {
    String sql = provider.listGroupAssignmentsByRole("metalake1", "role1");

    assertTrue(sql.contains("rel.audit_info as assignmentAuditInfo"));
    assertTrue(sql.contains("as userCount"));
    assertTrue(sql.contains("EXISTS (SELECT 1 FROM idp_group_meta source_group"));
    assertTrue(sql.contains("SELECT COUNT(DISTINCT ut.user_id) FROM idp_group_meta"));
    assertTrue(sql.contains("INNER JOIN idp_user_group_rel"));
    assertTrue(sql.contains("FROM scim_user_group_rel"));
    assertTrue(sql.contains("LEFT JOIN group_role_rel rel"));
    assertTrue(sql.contains("LEFT JOIN group_meta principal"));
    assertTrue(sql.contains("WHERE mt.metalake_name = #{metalake}"));
    assertTrue(sql.contains("ORDER BY principal.group_name"));
  }
}
