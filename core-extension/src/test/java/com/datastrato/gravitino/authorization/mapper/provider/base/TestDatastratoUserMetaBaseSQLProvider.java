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

  /** Verifies security user list SQL pre-aggregates roles and groups separately. */
  @Test
  public void testListUserWithGroupsPOsByMetalakeName() {
    String sql =
        new DatastratoUserMetaBaseSQLProvider().listUserWithGroupsPOsByMetalakeName("metalake");

    assertTrue(sql.contains("JSON_ARRAYAGG(rot.role_name) as roleNames"));
    assertTrue(sql.contains("JSON_ARRAYAGG(membership.groupName) as groupNames"));
    assertTrue(sql.contains("GROUP BY rt.user_id"));
    assertTrue(sql.contains("GROUP BY membership.userId"));
    assertTrue(sql.contains("idp_user_group_rel"));
    assertTrue(sql.contains("scim_user_group_rel"));
    assertTrue(sql.contains("UNION ALL"));
    assertTrue(sql.contains("metalake_name = #{metalakeName}"));
    assertFalse(sql.contains("GROUP BY ut.user_id"));
    assertFalse(sql.contains("UPDATE "));
  }

  @Test
  public void testListUserMetasByMetalakeNameAndNames() {
    String sql =
        new DatastratoUserMetaBaseSQLProvider()
            .listUserMetasByMetalakeNameAndNames("metalake", Lists.newArrayList("alice", "bob"));

    assertTrue(sql.contains("SELECT user_id as userId"));
    assertTrue(sql.contains("metalake_name = #{metalakeName}"));
    assertTrue(sql.contains("user_name IN "));
    assertFalse(sql.contains("UPDATE "));
  }

  @Test
  public void testBatchUpdateEnabledRequiresNullExternalId() {
    String sql =
        new DatastratoUserMetaBaseSQLProvider()
            .batchUpdateEnabledByMetalakeNameAndNames(
                "metalake", Lists.newArrayList("alice", "bob"), false);

    assertTrue(sql.contains("UPDATE "));
    assertTrue(sql.contains("SET enabled = #{enabled}"));
    assertTrue(sql.contains("metalake_name = #{metalakeName}"));
    assertTrue(sql.contains("external_id IS NULL"));
    assertTrue(sql.contains("user_name IN "));
    assertFalse(sql.contains("HAVING"));
    assertFalse(sql.contains("INNER JOIN"));
  }
}
