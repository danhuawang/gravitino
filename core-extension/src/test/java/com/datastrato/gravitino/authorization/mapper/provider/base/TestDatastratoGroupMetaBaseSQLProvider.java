/*
 * Copyright 2026 Datastrato Inc.
 */
package com.datastrato.gravitino.authorization.mapper.provider.base;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.datastrato.gravitino.dto.authorization.IdentitySource;
import java.util.List;
import org.junit.jupiter.api.Test;

public class TestDatastratoGroupMetaBaseSQLProvider {

  private final DatastratoGroupMetaBaseSQLProvider provider =
      new DatastratoGroupMetaBaseSQLProvider();

  @Test
  public void testListGroupsByMetalakeWithOriginIncludesUserCount() {
    String sql = provider.listGroupsByMetalakeWithOrigin("metalake");
    assertTrue(sql.contains("as userCount"));
    assertTrue(sql.contains("THEN " + IdentitySource.ORIGIN_CODE_PROVISIONED));
    assertTrue(sql.contains("THEN " + IdentitySource.ORIGIN_CODE_LOCAL));
    assertTrue(sql.contains("THEN " + IdentitySource.ORIGIN_CODE_JIT));
    assertTrue(sql.contains("ELSE NULL END as originCode"));
    assertFalse(sql.contains("ELSE " + IdentitySource.ORIGIN_CODE_JIT));
    assertTrue(sql.contains("idp_user_group_rel"));
    assertTrue(sql.contains("scim_user_group_rel"));
    assertTrue(sql.contains("scim_group_meta"));
    assertTrue(sql.contains("idp_group_meta"));
    assertTrue(sql.contains("jit_group_meta"));
    assertTrue(sql.contains("scim_user_meta"));
    assertFalse(sql.contains("gt.external_id"));
    assertTrue(sql.contains("COUNT(DISTINCT ut.user_id)"));
    assertTrue(sql.contains("LEFT JOIN scim_group_meta sg"));
    assertTrue(sql.contains("sg.group_name IS NOT NULL"));
    assertTrue(!sql.contains("as inBuiltInIdp"));
  }

  @Test
  public void testGetGroupByMetalakeWithOriginMatchesListOriginRules() {
    String sql = provider.getGroupByMetalakeWithOrigin("metalake", "governance");
    assertTrue(sql.contains("as userCount"));
    assertTrue(sql.contains("THEN " + IdentitySource.ORIGIN_CODE_PROVISIONED));
    assertTrue(sql.contains("THEN " + IdentitySource.ORIGIN_CODE_LOCAL));
    assertTrue(sql.contains("THEN " + IdentitySource.ORIGIN_CODE_JIT));
    assertTrue(sql.contains("ELSE NULL END as originCode"));
    assertFalse(sql.contains("ELSE " + IdentitySource.ORIGIN_CODE_JIT));
    assertTrue(sql.contains("gt.group_name = #{groupName}"));
    assertTrue(sql.contains("GROUP BY gt.group_id"));
    assertTrue(sql.contains("scim_group_meta"));
    assertTrue(sql.contains("idp_group_meta"));
    assertTrue(sql.contains("jit_group_meta"));
    assertTrue(sql.contains("jit_user_group_rel"));
    assertTrue(sql.contains("jit_user_meta"));
    assertTrue(sql.contains("LEFT JOIN scim_group_meta sg"));
    assertTrue(!sql.contains("as inBuiltInIdp"));
  }

  @Test
  public void testListGroupsForMetalakeUserUsesScimMembershipAlias() {
    String sql = provider.listGroupsForMetalakeUserWithOrigin("metalake", "alice");
    assertTrue(sql.contains("scim_group_meta sgm"));
    assertTrue(sql.contains("LEFT JOIN scim_group_meta sg"));
    assertTrue(sql.contains("jit_group_meta"));
    assertTrue(sql.contains("jit_user_group_rel"));
    assertTrue(sql.contains("jit_user_meta"));
    assertTrue(sql.contains("THEN " + IdentitySource.ORIGIN_CODE_JIT));
    assertTrue(sql.contains("sg.group_name IS NOT NULL"));
  }

  @Test
  public void testListDirectoryGroups() {
    String sql = provider.listDirectoryGroups();
    assertTrue(sql.contains("idp_group_meta"));
    assertTrue(sql.contains("scim_group_meta"));
    assertTrue(sql.contains("jit_group_meta"));
    assertTrue(sql.contains("idp_user_group_rel"));
    assertTrue(sql.contains("scim_user_group_rel"));
    assertTrue(sql.contains("jit_user_group_rel"));
    assertTrue(sql.contains("memberCount"));
    assertTrue(sql.contains("metalakeNames"));
    assertTrue(sql.contains("originCode"));
    assertTrue(sql.contains("UNION ALL"));
    assertFalse(sql.contains("NOT EXISTS"));
    assertTrue(sql.contains("ORDER BY identity.groupName, identity.originCode"));
    assertTrue(sql.contains("CAST(NULL AS BIGINT)"));
    assertTrue(sql.contains("GROUP BY gt.group_name"));
    assertTrue(sql.contains(" " + IdentitySource.ORIGIN_CODE_JIT + " as originCode"));
    assertTrue(sql.contains("jitMembers.memberCount"));
  }

  @Test
  public void testListDirectoryGroupsMySQLCastsNullAsSigned() {
    DatastratoGroupMetaBaseSQLProvider mysql =
        new DatastratoGroupMetaBaseSQLProvider() {
          @Override
          protected String nullLongLiteral() {
            return "CAST(NULL AS SIGNED)";
          }
        };
    String sql = mysql.listDirectoryGroups();
    assertTrue(sql.contains("CAST(NULL AS SIGNED)"));
    assertFalse(sql.contains("CAST(NULL AS BIGINT)"));
  }

  @Test
  public void testListDirectoryGroupsForUserLocal() {
    String sql = provider.listDirectoryGroupsForUser("sam.o", IdentitySource.ORIGIN_CODE_LOCAL);
    assertTrue(sql.contains("idp_user_meta"));
    assertTrue(sql.contains("idp_user_group_rel"));
    assertTrue(sql.contains("idp_group_meta"));
    assertTrue(sql.contains("memberCount"));
    assertTrue(sql.contains("iu.user_name = #{userName}"));
    assertTrue(sql.contains(IdentitySource.ORIGIN_CODE_LOCAL + " as originCode"));
    assertFalse(sql.contains("scim_user_meta"));
    assertFalse(sql.contains(" " + IdentitySource.ORIGIN_CODE_JIT + " as originCode"));
  }

  @Test
  public void testListDirectoryGroupsForUserProvisioned() {
    String sql =
        provider.listDirectoryGroupsForUser("dana.k", IdentitySource.ORIGIN_CODE_PROVISIONED);
    assertTrue(sql.contains("scim_user_meta"));
    assertTrue(sql.contains("scim_user_group_rel"));
    assertTrue(sql.contains("scim_group_meta"));
    assertTrue(sql.contains("memberCount"));
    assertTrue(sql.contains("su.user_name = #{userName}"));
    assertFalse(sql.contains("NOT EXISTS"));
    assertTrue(sql.contains(IdentitySource.ORIGIN_CODE_PROVISIONED + " as originCode"));
    assertFalse(sql.contains("idp_user_group_rel"));
    assertFalse(sql.contains(" " + IdentitySource.ORIGIN_CODE_JIT + " as originCode"));
  }

  @Test
  public void testListDirectoryGroupsForUserJit() {
    String sql = provider.listDirectoryGroupsForUser("jordan.m", IdentitySource.ORIGIN_CODE_JIT);
    assertTrue(sql.contains("jit_user_meta"));
    assertTrue(sql.contains("jit_user_group_rel"));
    assertTrue(sql.contains("jit_group_meta"));
    assertTrue(sql.contains("memberCount"));
    assertTrue(sql.contains("ju.user_name = #{userName}"));
    assertTrue(sql.contains(IdentitySource.ORIGIN_CODE_JIT + " as originCode"));
    assertFalse(sql.contains("NOT EXISTS"));
  }

  @Test
  public void testGetDirectoryGroupLocalSelectsAuditInfo() {
    String sql = provider.getDirectoryGroup("governance", IdentitySource.ORIGIN_CODE_LOCAL);
    assertTrue(sql.contains("idp_group_meta"));
    assertTrue(sql.contains("ig.audit_info as auditInfo"));
    assertTrue(sql.contains("identity.auditInfo as auditInfo"));
    assertTrue(sql.contains("memberCount"));
    assertTrue(sql.contains(IdentitySource.ORIGIN_CODE_LOCAL + " as originCode"));
    assertFalse(sql.contains("scim_group_meta"));
  }

  @Test
  public void testGetDirectoryGroupProvisionedSelectsAuditInfo() {
    String sql = provider.getDirectoryGroup("platform", IdentitySource.ORIGIN_CODE_PROVISIONED);
    assertTrue(sql.contains("scim_group_meta"));
    assertTrue(sql.contains("sg.audit_info as auditInfo"));
    assertTrue(sql.contains("identity.auditInfo as auditInfo"));
    assertFalse(sql.contains("NOT EXISTS"));
    assertTrue(sql.contains(IdentitySource.ORIGIN_CODE_PROVISIONED + " as originCode"));
  }

  @Test
  public void testGetDirectoryGroupJit() {
    String sql = provider.getDirectoryGroup("engineers", IdentitySource.ORIGIN_CODE_JIT);
    assertTrue(sql.contains("jit_group_meta"));
    assertTrue(sql.contains("jg.audit_info as auditInfo"));
    assertTrue(sql.contains("identity.auditInfo as auditInfo"));
    assertTrue(sql.contains("jitMembers.memberCount"));
    assertTrue(sql.contains(IdentitySource.ORIGIN_CODE_JIT + " as originCode"));
    assertFalse(sql.contains("NOT EXISTS"));
  }

  @Test
  public void testInsertIdpGroupAndBatchInsertRels() {
    String insert = provider.insertIdpGroup(1L, "ops", "Operations", "{\"creator\":\"admin\"}");
    assertTrue(insert.contains("INSERT INTO idp_group_meta"));
    assertTrue(insert.contains("group_comment"));
    assertTrue(insert.contains("audit_info"));
    assertTrue(insert.contains("#{auditInfo}"));

    String rels =
        provider.batchInsertIdpUserGroupRels(
            java.util.List.of(
                new com.datastrato.gravitino.authorization.po.IdpUserGroupRelInsertPO(
                    10L, 100L, 1L, "{\"creator\":\"admin\"}")));
    assertTrue(rels.contains("INSERT INTO idp_user_group_rel"));
    assertTrue(rels.contains("audit_info"));
    assertTrue(rels.contains("#{item.auditInfo}"));
    assertTrue(rels.contains("foreach"));
  }

  @Test
  public void testListIdpGroupNames() {
    String sql = provider.listIdpGroupNames();
    assertTrue(sql.contains("scim_group_meta"));
    assertTrue(sql.contains("idp_group_meta"));
    assertTrue(sql.contains("jit_group_meta"));
    assertTrue(sql.contains("UNION"));
    assertTrue(sql.contains("deleted_at = 0"));
    assertTrue(sql.contains("ORDER BY group_name"));
  }

  @Test
  public void testSelectIdentityGroupNamesByNames() {
    String sql = provider.selectIdentityGroupNamesByNames(List.of("governance", "ops"));

    assertTrue(sql.contains("SELECT group_name FROM scim_group_meta"));
    assertTrue(sql.contains("SELECT group_name FROM idp_group_meta"));
    assertTrue(sql.contains("SELECT group_name FROM jit_group_meta"));
    assertTrue(sql.contains("UNION"));
    assertTrue(sql.contains("group_name IN "));
  }
}
