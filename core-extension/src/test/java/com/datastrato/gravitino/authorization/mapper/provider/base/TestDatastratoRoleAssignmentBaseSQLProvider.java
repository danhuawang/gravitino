/*
 * Copyright 2026 Datastrato Inc.
 */
package com.datastrato.gravitino.authorization.mapper.provider.base;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Collections;
import java.util.List;
import org.apache.gravitino.storage.relational.po.GroupRoleRelPO;
import org.apache.gravitino.storage.relational.po.UserRoleRelPO;
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

  @Test
  public void testBatchAssignRoleToUsers() {
    String sql =
        provider.batchAssignRoleToUsers(
            List.of(
                UserRoleRelPO.builder()
                    .withUserId(1L)
                    .withRoleId(10L)
                    .withAuditInfo("{}")
                    .withCurrentVersion(1L)
                    .withLastVersion(1L)
                    .withDeletedAt(0L)
                    .build()));

    assertTrue(sql.startsWith("<script>INSERT INTO user_role_rel"));
    assertTrue(sql.contains("<foreach collection='assignments'"));
    assertTrue(sql.contains("#{item.userId} AS principal_id"));
    assertTrue(sql.contains("WHERE NOT EXISTS"));
    assertTrue(sql.contains("existing_assignment.deleted_at = 0"));
  }

  @Test
  public void testBatchAssignRoleToGroups() {
    String sql =
        provider.batchAssignRoleToGroups(
            List.of(
                GroupRoleRelPO.builder()
                    .withGroupId(2L)
                    .withRoleId(10L)
                    .withAuditInfo("{}")
                    .withCurrentVersion(1L)
                    .withLastVersion(1L)
                    .withDeletedAt(0L)
                    .build()));

    assertTrue(sql.startsWith("<script>INSERT INTO group_role_rel"));
    assertTrue(sql.contains("#{item.groupId} AS principal_id"));
    assertTrue(sql.contains("UNION ALL"));
    assertTrue(sql.contains("existing_assignment.role_id = batch_assignment.role_id"));
  }

  @Test
  public void testBatchAssignRoleWithEmptyAssignments() {
    assertEquals("SELECT 0", provider.batchAssignRoleToUsers(Collections.emptyList()));
    assertEquals("SELECT 0", provider.batchAssignRoleToGroups(Collections.emptyList()));
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
