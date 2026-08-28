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
}
