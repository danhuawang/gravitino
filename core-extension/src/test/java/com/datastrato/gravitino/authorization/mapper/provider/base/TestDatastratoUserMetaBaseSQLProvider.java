/*
 * Copyright 2026 Datastrato Inc.
 */
package com.datastrato.gravitino.authorization.mapper.provider.base;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.datastrato.gravitino.dto.authorization.IdentitySource;
import com.google.common.collect.Lists;
import org.junit.jupiter.api.Test;

/** Tests for {@link DatastratoUserMetaBaseSQLProvider}. */
public class TestDatastratoUserMetaBaseSQLProvider {

  private final DatastratoUserMetaBaseSQLProvider provider =
      new DatastratoUserMetaBaseSQLProvider();

  /** Verifies security user list SQL pre-aggregates roles and groups separately. */
  @Test
  public void testListUserWithGroupsPOsByMetalakeName() {
    String sql = provider.listUserWithGroupsPOsByMetalakeName("metalake");

    assertTrue(sql.contains("JSON_ARRAYAGG(rot.role_name) as roleNames"));
    assertTrue(sql.contains("JSON_ARRAYAGG(membership.groupName) as groupNames"));
    assertTrue(sql.contains("GROUP BY rt.user_id"));
    assertTrue(sql.contains("GROUP BY membership.userId"));
    assertTrue(sql.contains("idp_user_group_rel"));
    assertTrue(sql.contains("scim_user_group_rel"));
    assertTrue(sql.contains("jit_user_group_rel"));
    assertTrue(sql.contains("scim_user_meta"));
    assertTrue(sql.contains("scim_group_meta"));
    assertTrue(sql.contains("jit_user_meta"));
    assertTrue(sql.contains("jit_group_meta"));
    assertTrue(sql.contains("LEFT JOIN scim_user_meta su"));
    assertTrue(sql.contains("su.user_name IS NOT NULL"));
    assertFalse(sql.contains("ut.external_id"));
    assertFalse(sql.contains("ut.enabled"));
    assertTrue(sql.contains("as enabled"));
    assertTrue(sql.contains("as externalId"));
    assertTrue(sql.contains("su.external_id"));
    assertTrue(sql.contains("as originCode"));
    assertTrue(sql.contains("idp_user_meta"));
    assertTrue(sql.contains("THEN " + IdentitySource.ORIGIN_CODE_PROVISIONED));
    assertTrue(sql.contains("THEN " + IdentitySource.ORIGIN_CODE_LOCAL));
    assertTrue(sql.contains("THEN " + IdentitySource.ORIGIN_CODE_JIT));
    assertTrue(sql.contains("ELSE NULL END as originCode"));
    assertTrue(sql.contains("UNION ALL"));
    assertTrue(sql.contains("metalake_name = #{metalakeName}"));
    assertFalse(sql.contains("GROUP BY ut.user_id"));
    assertFalse(sql.contains("as inBuiltInIdp"));
    assertFalse(sql.contains("UPDATE "));
  }

  @Test
  public void testListDirectoryUsers() {
    String sql = provider.listDirectoryUsers();

    assertTrue(sql.contains("idp_user_meta"));
    assertTrue(sql.contains("scim_user_meta"));
    assertTrue(sql.contains("jit_user_meta"));
    assertTrue(sql.contains("idp_user_group_rel"));
    assertTrue(sql.contains("scim_user_group_rel"));
    assertTrue(sql.contains("jit_user_group_rel"));
    assertTrue(sql.contains("originCode"));
    assertTrue(sql.contains("metalakeNames"));
    assertTrue(sql.contains("UNION ALL"));
    assertFalse(sql.contains("NOT EXISTS"));
    assertTrue(sql.contains("ORDER BY identity.userName, identity.originCode"));
    assertTrue(sql.contains("CAST(NULL AS BIGINT)"));
    // Metalake names are aggregated per username for the Directory Users table.
    assertTrue(sql.contains("GROUP BY ut.user_name"));
    assertFalse(sql.contains("#{metalakeName}"));
    assertTrue(sql.contains(" " + IdentitySource.ORIGIN_CODE_JIT + " as originCode"));
    assertTrue(sql.contains("true as enabled"));
    assertTrue(sql.contains("jitGroups.groupNames"));
  }

  @Test
  public void testListDirectoryUsersMySQLCastsNullAsSigned() {
    DatastratoUserMetaBaseSQLProvider mysql =
        new DatastratoUserMetaBaseSQLProvider() {
          @Override
          protected String nullLongLiteral() {
            return "CAST(NULL AS SIGNED)";
          }
        };
    String sql = mysql.listDirectoryUsers();
    assertTrue(sql.contains("CAST(NULL AS SIGNED)"));
    assertFalse(sql.contains("CAST(NULL AS BIGINT)"));
  }

  @Test
  public void testPostgreSQLScimEnabledCastInDirectoryUsers() {
    DatastratoUserMetaBaseSQLProvider withPgCast =
        new DatastratoUserMetaBaseSQLProvider() {
          @Override
          protected String scimUserEnabledAsBoolean() {
            return "(su.enabled <> 0)";
          }
        };
    String sql = withPgCast.listDirectoryUsers();
    assertTrue(sql.contains("(su.enabled <> 0) as enabled"));
  }

  @Test
  public void testListUserMetasByMetalakeNameAndNames() {
    String sql =
        provider.listUserMetasByMetalakeNameAndNames(
            "metalake", Lists.newArrayList("alice", "bob"));

    assertTrue(sql.contains("SELECT ut.user_id as userId"));
    assertFalse(sql.contains("external_id"));
    assertFalse(sql.contains("as enabled"));
    assertTrue(sql.contains("metalake_name = #{metalakeName}"));
    assertTrue(sql.contains("user_name IN "));
    assertFalse(sql.contains("UPDATE "));
  }

  @Test
  public void testBatchUpdateEnabledUpdatesIdpUsers() {
    String sql =
        provider.batchUpdateEnabledByMetalakeNameAndNames(
            "metalake", Lists.newArrayList("alice", "bob"), false);

    assertTrue(sql.contains("UPDATE "));
    assertTrue(sql.contains("idp_user_meta"));
    assertTrue(sql.contains("SET enabled = #{enabled}"));
    assertTrue(sql.contains("metalake_name = #{metalakeName}"));
    assertTrue(sql.contains("scim_user_meta"));
    assertTrue(sql.contains("NOT EXISTS"));
    assertTrue(sql.contains("user_name IN "));
    assertFalse(sql.contains("UPDATE user_meta"));
    assertFalse(sql.contains("external_id IS NULL"));
    assertFalse(sql.contains("HAVING"));
    assertFalse(sql.contains("INNER JOIN"));
  }

  @Test
  public void testBatchUpdateScimUserEnabledByUserNames() {
    String sql =
        provider.batchUpdateScimUserEnabledByUserNames(Lists.newArrayList("alice", "bob"), true);

    assertTrue(sql.contains("UPDATE scim_user_meta"));
    assertTrue(sql.contains("SET enabled = #{enabled}"));
    assertTrue(sql.contains("user_name IN "));
  }

  @Test
  public void testSelectIdpUserNamesByNames() {
    String sql = provider.selectIdpUserNamesByNames(Lists.newArrayList("alice", "bob"));

    assertTrue(sql.contains("SELECT user_name FROM idp_user_meta"));
    assertTrue(sql.contains("user_name IN "));
  }

  @Test
  public void testBatchUpdateIdpUserEnabledByUserNames() {
    String sql =
        provider.batchUpdateIdpUserEnabledByUserNames(Lists.newArrayList("alice", "bob"), false);

    assertTrue(sql.contains("UPDATE idp_user_meta"));
    assertTrue(sql.contains("SET enabled = #{enabled}"));
    assertTrue(sql.contains("user_name IN "));
    assertTrue(sql.contains("current_version"));
  }

  @Test
  public void testInsertIdpUserAndBatchInsertRels() {
    String insert = provider.insertIdpUser(1L, "alice", "hash", true, "{\"creator\":\"admin\"}");
    assertTrue(insert.contains("INSERT INTO idp_user_meta"));
    assertTrue(insert.contains("password_hash"));
    assertTrue(insert.contains("audit_info"));
    assertTrue(insert.contains("#{auditInfo}"));

    String rels =
        provider.batchInsertIdpUserGroupRels(
            Lists.newArrayList(
                new com.datastrato.gravitino.authorization.po.IdpUserGroupRelInsertPO(
                    10L, 1L, 200L, "{\"creator\":\"admin\"}")));
    assertTrue(rels.contains("INSERT INTO idp_user_group_rel"));
    assertTrue(rels.contains("audit_info"));
    assertTrue(rels.contains("#{item.auditInfo}"));
    assertTrue(rels.contains("foreach"));
  }

  @Test
  public void testSelectIdpGroupIdsByNames() {
    String sql = provider.selectIdpGroupIdsByNames(Lists.newArrayList("governance", "ops"));
    assertTrue(sql.contains("SELECT group_name as groupName, group_id as groupId FROM"));
    assertTrue(sql.contains("idp_group_meta"));
    assertTrue(sql.contains("group_name IN "));
  }

  @Test
  public void testSelectScimUserNamesByNames() {
    String sql = provider.selectScimUserNamesByNames(Lists.newArrayList("alice", "bob"));

    assertTrue(sql.contains("SELECT user_name FROM scim_user_meta"));
    assertTrue(sql.contains("user_name IN "));
  }

  @Test
  public void testCountUsersByEnabledByMetalakeUsesIdentityTables() {
    String sql = provider.countUsersByEnabledByMetalake("metalake");

    assertTrue(sql.contains("scim_user_meta"));
    assertTrue(sql.contains("idp_user_meta"));
    assertTrue(sql.contains("COALESCE(COALESCE(su.enabled, iu.enabled), true)"));
    assertFalse(sql.contains("ut.enabled"));
  }

  @Test
  public void testGetUserByMetalakeWithOriginMatchesListOriginRules() {
    String sql = provider.getUserByMetalakeWithOrigin("metalake", "dana.k");

    assertTrue(sql.contains("scim_user_meta"));
    assertTrue(sql.contains("idp_user_meta"));
    assertTrue(sql.contains("as enabled"));
    assertTrue(sql.contains("as externalId"));
    assertTrue(sql.contains("external_id"));
    assertTrue(sql.contains("as originCode"));
    // SCIM → IdP → JIT → NULL
    assertTrue(sql.contains("jit_user_meta"));
    assertTrue(sql.contains("THEN " + IdentitySource.ORIGIN_CODE_LOCAL));
    assertTrue(sql.contains("THEN " + IdentitySource.ORIGIN_CODE_PROVISIONED));
    assertTrue(sql.contains("THEN " + IdentitySource.ORIGIN_CODE_JIT));
    assertTrue(sql.contains("ELSE NULL END as originCode"));
    assertFalse(sql.contains("ELSE " + IdentitySource.ORIGIN_CODE_JIT));
    assertTrue(
        sql.contains(
            "COALESCE(MAX(CASE WHEN COALESCE(su.enabled, iu.enabled) IS NULL THEN NULL WHEN"
                + " COALESCE(su.enabled, iu.enabled) THEN 1 ELSE 0 END), 1) <> 0 as enabled"));
    // Must not map NULL identity enabled to 0 before COALESCE(..., 1), or metalake-only users
    // become Suspended.
    assertFalse(
        sql.contains(
            "COALESCE(MAX(CASE WHEN COALESCE(su.enabled, iu.enabled) THEN 1 ELSE 0 END), 1)"));
    // Must not apply MAX() directly to a boolean/TINYINT expression (breaks on PostgreSQL
    // BOOLEAN).
    assertFalse(sql.contains("MAX(COALESCE(su.enabled, iu.enabled))"));
    assertTrue(sql.contains("ut.user_name = #{userName}"));
    assertTrue(sql.contains("GROUP BY ut.user_id"));
    assertFalse(sql.contains("as inBuiltInIdp"));
  }

  @Test
  public void testListUsersByMetalakeWithOriginMatchesListOriginRules() {
    String sql = provider.listUsersByMetalakeWithOrigin("metalake");

    assertTrue(sql.contains("scim_user_meta"));
    assertTrue(sql.contains("idp_user_meta"));
    assertTrue(sql.contains("jit_user_meta"));
    assertTrue(sql.contains("as enabled"));
    assertTrue(sql.contains("as externalId"));
    assertTrue(sql.contains("external_id"));
    assertTrue(sql.contains("THEN " + IdentitySource.ORIGIN_CODE_PROVISIONED));
    assertTrue(sql.contains("THEN " + IdentitySource.ORIGIN_CODE_LOCAL));
    assertTrue(sql.contains("THEN " + IdentitySource.ORIGIN_CODE_JIT));
    assertTrue(sql.contains("ELSE NULL END as originCode"));
    assertFalse(sql.contains("ELSE " + IdentitySource.ORIGIN_CODE_JIT));
    assertTrue(
        sql.contains(
            "COALESCE(MAX(CASE WHEN COALESCE(su.enabled, iu.enabled) IS NULL THEN NULL WHEN"
                + " COALESCE(su.enabled, iu.enabled) THEN 1 ELSE 0 END), 1) <> 0 as enabled"));
    assertFalse(sql.contains("as inBuiltInIdp"));
  }

  @Test
  public void testListDirectoryUsersForGroupLocal() {
    String sql =
        provider.listDirectoryUsersForGroup("governance", IdentitySource.ORIGIN_CODE_LOCAL);
    assertTrue(sql.contains("idp_group_meta"));
    assertTrue(sql.contains("idp_user_group_rel"));
    assertTrue(sql.contains("idp_user_meta"));
    assertTrue(sql.contains("iu.enabled"));
    assertTrue(sql.contains("ig.group_name = #{groupName}"));
    assertTrue(sql.contains(IdentitySource.ORIGIN_CODE_LOCAL + " as originCode"));
    assertFalse(sql.contains("scim_user_meta"));
    assertFalse(sql.contains(" " + IdentitySource.ORIGIN_CODE_JIT + " as originCode"));
  }

  @Test
  public void testListDirectoryUsersForGroupProvisioned() {
    String sql =
        provider.listDirectoryUsersForGroup("governance", IdentitySource.ORIGIN_CODE_PROVISIONED);
    assertTrue(sql.contains("scim_group_meta"));
    assertTrue(sql.contains("scim_user_group_rel"));
    assertTrue(sql.contains("scim_user_meta"));
    assertTrue(sql.contains("su.enabled"));
    assertTrue(sql.contains("sg.group_name = #{groupName}"));
    assertFalse(sql.contains("NOT EXISTS"));
    assertTrue(sql.contains(IdentitySource.ORIGIN_CODE_PROVISIONED + " as originCode"));
    assertFalse(sql.contains("idp_user_group_rel"));
    assertFalse(sql.contains(" " + IdentitySource.ORIGIN_CODE_JIT + " as originCode"));
  }

  @Test
  public void testListDirectoryUsersForGroupJit() {
    String sql = provider.listDirectoryUsersForGroup("engineers", IdentitySource.ORIGIN_CODE_JIT);
    assertTrue(sql.contains("jit_group_meta"));
    assertTrue(sql.contains("jit_user_group_rel"));
    assertTrue(sql.contains("jit_user_meta"));
    assertTrue(sql.contains("true as enabled"));
    assertTrue(sql.contains("jg.group_name = #{groupName}"));
    assertTrue(sql.contains(IdentitySource.ORIGIN_CODE_JIT + " as originCode"));
    assertFalse(sql.contains("NOT EXISTS"));
  }

  @Test
  public void testGetDirectoryUserLocalSelectsAuditInfo() {
    String sql = provider.getDirectoryUser("sam.o", IdentitySource.ORIGIN_CODE_LOCAL);
    assertTrue(sql.contains("idp_user_meta"));
    assertTrue(sql.contains("iu.audit_info as auditInfo"));
    assertTrue(sql.contains("iu.user_name = #{userName}"));
    assertTrue(sql.contains(IdentitySource.ORIGIN_CODE_LOCAL + " as originCode"));
    assertFalse(sql.contains("scim_user_meta"));
  }

  @Test
  public void testGetDirectoryUserProvisionedSelectsAuditInfo() {
    String sql = provider.getDirectoryUser("dana.k", IdentitySource.ORIGIN_CODE_PROVISIONED);
    assertTrue(sql.contains("scim_user_meta"));
    assertTrue(sql.contains("su.audit_info as auditInfo"));
    assertTrue(sql.contains("su.external_id as externalId"));
    assertFalse(sql.contains("NOT EXISTS"));
    assertTrue(sql.contains(IdentitySource.ORIGIN_CODE_PROVISIONED + " as originCode"));
  }

  @Test
  public void testGetDirectoryUserJit() {
    String sql = provider.getDirectoryUser("jordan.m", IdentitySource.ORIGIN_CODE_JIT);
    assertTrue(sql.contains("jit_user_meta"));
    assertTrue(sql.contains("ju.audit_info as auditInfo"));
    assertTrue(sql.contains("true as enabled"));
    assertTrue(sql.contains("NULL as externalId"));
    assertTrue(sql.contains("ju.user_name = #{userName}"));
    assertTrue(sql.contains(IdentitySource.ORIGIN_CODE_JIT + " as originCode"));
    assertFalse(sql.contains("NOT EXISTS"));
  }

  @Test
  public void testSelectJitUserNamesByNames() {
    String sql = provider.selectJitUserNamesByNames(Lists.newArrayList("alice", "bob"));
    assertTrue(sql.contains("SELECT user_name FROM jit_user_meta"));
    assertTrue(sql.contains("deleted_at = 0"));
    assertTrue(sql.contains("user_name IN "));
  }

  @Test
  public void testSelectUserOriginCodes() {
    String sql = provider.selectUserOriginCodes("alice");
    assertTrue(sql.contains("idp_user_meta"));
    assertTrue(sql.contains("scim_user_meta"));
    assertTrue(sql.contains("jit_user_meta"));
    assertTrue(sql.contains("UNION ALL"));
    assertTrue(sql.contains(IdentitySource.ORIGIN_CODE_LOCAL + " AS originCode"));
    assertTrue(sql.contains(IdentitySource.ORIGIN_CODE_PROVISIONED + " AS originCode"));
    assertTrue(sql.contains(IdentitySource.ORIGIN_CODE_JIT + " AS originCode"));
    assertTrue(sql.contains("user_name = #{userName}"));
  }

  @Test
  public void testListUserRolesByMetalake() {
    String sql = provider.listUserRolesByMetalake("alice");
    assertTrue(sql.contains("metalake_name AS metalakeName"));
    assertTrue(sql.contains("role_id AS roleId"));
    assertTrue(sql.contains("role_name AS roleName"));
    assertFalse(sql.contains("properties AS properties"));
    assertTrue(sql.contains("user_meta"));
    assertTrue(sql.contains("user_role_rel"));
    assertTrue(sql.contains("role_meta"));
    assertTrue(sql.contains("user_name = #{userName}"));
    assertTrue(sql.contains("ORDER BY mt.metalake_name, rot.role_name"));
  }

  @Test
  public void testListOwnedMetalakeNames() {
    String sql = provider.listOwnedMetalakeNames("alice");
    assertTrue(sql.contains("owner_meta"));
    assertTrue(sql.contains("user_meta"));
    assertTrue(sql.contains("metalake_meta"));
    assertTrue(sql.contains("owner_type = 'USER'"));
    assertTrue(sql.contains("metadata_object_type = 'METALAKE'"));
    assertTrue(sql.contains("user_name = #{userName}"));
    assertTrue(sql.contains("ORDER BY mt.metalake_name"));
  }

  @Test
  public void testListIdpUserNames() {
    String sql = provider.listIdpUserNames();

    assertTrue(sql.contains("scim_user_meta"));
    assertTrue(sql.contains("idp_user_meta"));
    assertTrue(sql.contains("jit_user_meta"));
    assertTrue(sql.contains("UNION"));
    assertTrue(sql.contains("deleted_at = 0"));
    assertTrue(sql.contains("ORDER BY user_name"));
  }
}
