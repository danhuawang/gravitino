/*
 * Copyright 2026 Datastrato Inc.
 */
package com.datastrato.gravitino.authorization.mapper.provider.base;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

public class TestDatastratoGroupMetaBaseSQLProvider {

  private final DatastratoGroupMetaBaseSQLProvider provider =
      new DatastratoGroupMetaBaseSQLProvider();

  @Test
  public void testListGroupsByMetalakeWithOriginIncludesUserCount() {
    String sql = provider.listGroupsByMetalakeWithOrigin("metalake");
    assertTrue(sql.contains("as userCount"));
    assertTrue(sql.contains("as originCode"));
    assertTrue(sql.contains("idp_user_group_rel"));
    assertTrue(sql.contains("scim_user_group_rel"));
    assertTrue(sql.contains("scim_group_meta"));
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
    assertTrue(sql.contains("as originCode"));
    assertTrue(sql.contains("gt.group_name = #{groupName}"));
    assertTrue(sql.contains("GROUP BY gt.group_id"));
    assertTrue(sql.contains("scim_group_meta"));
    assertTrue(sql.contains("idp_group_meta"));
    assertTrue(sql.contains("LEFT JOIN scim_group_meta sg"));
    assertTrue(!sql.contains("as inBuiltInIdp"));
  }

  @Test
  public void testListGroupsForMetalakeUserUsesScimMembershipAlias() {
    String sql = provider.listGroupsForMetalakeUserWithOrigin("metalake", "alice");
    assertTrue(sql.contains("sgm.group_name IS NOT NULL"));
    assertFalse(sql.contains("WHEN MAX(CASE WHEN sg.group_name IS NOT NULL"));
    assertTrue(sql.contains("scim_group_meta sgm"));
  }

  @Test
  public void testListDirectoryGroups() {
    String sql = provider.listDirectoryGroups();
    assertTrue(sql.contains("idp_group_meta"));
    assertTrue(sql.contains("scim_group_meta"));
    assertTrue(sql.contains("idp_user_group_rel"));
    assertTrue(sql.contains("scim_user_group_rel"));
    assertTrue(sql.contains("memberCount"));
    assertTrue(sql.contains("metalakeNames"));
    assertTrue(sql.contains("originCode"));
    assertTrue(sql.contains("UNION ALL"));
    assertTrue(sql.contains("NOT EXISTS"));
    assertTrue(sql.contains("GROUP BY gt.group_name"));
    assertTrue(sql.contains("ORDER BY identity.groupName"));
  }
}
