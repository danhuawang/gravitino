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
    assertTrue(sql.contains("UNION ALL"));
    assertTrue(sql.contains("metalake_name = #{metalakeName}"));
    assertFalse(sql.contains("GROUP BY ut.user_id"));
    assertFalse(sql.contains("UPDATE "));
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
  public void testListUsersByMetalakeWithOriginOmitsUserMetaIdentityColumns() {
    String sql = provider.listUsersByMetalakeWithOrigin("metalake");

    assertFalse(sql.contains("external_id"));
    assertFalse(sql.contains("as enabled"));
    assertTrue(sql.contains("idp_user_meta"));
  }
}
