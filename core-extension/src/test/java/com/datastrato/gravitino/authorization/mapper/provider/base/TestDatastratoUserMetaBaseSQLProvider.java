/*
 * Copyright 2026 Datastrato Inc.
 */
package com.datastrato.gravitino.authorization.mapper.provider.base;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

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
    assertTrue(sql.contains("scim_user_meta"));
    assertTrue(sql.contains("scim_group_meta"));
    assertFalse(sql.contains("ut.external_id"));
    assertFalse(sql.contains("ut.enabled"));
    assertTrue(sql.contains("as originCode"));
    assertTrue(sql.contains("idp_user_meta"));
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
    assertTrue(sql.contains("user_meta"));
    assertTrue(sql.contains("idp_user_group_rel"));
    assertTrue(sql.contains("scim_user_group_rel"));
    assertTrue(sql.contains("originCode"));
    assertTrue(sql.contains("metalakeNames"));
    assertTrue(sql.contains("UNION ALL"));
    assertTrue(sql.contains("NOT EXISTS"));
    assertTrue(sql.contains("GROUP BY ut.user_name"));
    assertTrue(sql.contains("ORDER BY identity.userName"));
    assertTrue(sql.contains("CAST(NULL AS BIGINT)"));
    assertFalse(sql.contains("#{metalakeName}"));
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
    String insert = provider.insertIdpUser(1L, "alice", "hash", true);
    assertTrue(insert.contains("INSERT INTO idp_user_meta"));
    assertTrue(insert.contains("password_hash"));

    String rels =
        provider.batchInsertIdpUserGroupRels(
            Lists.newArrayList(
                new com.datastrato.gravitino.authorization.po.IdpUserGroupRelInsertPO(
                    10L, 1L, 200L)));
    assertTrue(rels.contains("INSERT INTO idp_user_group_rel"));
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
    assertFalse(sql.contains("external_id"));
    assertFalse(sql.contains("as enabled"));
    assertTrue(sql.contains("as originCode"));
    assertTrue(sql.contains("ut.user_name = #{userName}"));
    assertTrue(sql.contains("GROUP BY ut.user_id"));
    assertFalse(sql.contains("as inBuiltInIdp"));
  }

  @Test
  public void testListUsersByMetalakeWithOriginMatchesListOriginRules() {
    String sql = provider.listUsersByMetalakeWithOrigin("metalake");

    assertTrue(sql.contains("scim_user_meta"));
    assertTrue(sql.contains("idp_user_meta"));
    assertFalse(sql.contains("external_id"));
    assertFalse(sql.contains("as enabled"));
    assertTrue(sql.contains("as originCode"));
    assertFalse(sql.contains("as inBuiltInIdp"));
  }

  @Test
  public void testListIdpUserNames() {
    String sql = provider.listIdpUserNames();

    assertTrue(sql.contains("SELECT user_name FROM idp_user_meta"));
    assertTrue(sql.contains("deleted_at = 0"));
    assertTrue(sql.contains("ORDER BY user_name"));
  }
}
