/*
 * Copyright 2026 Datastrato Inc.
 */

package com.datastrato.gravitino.scim.v2.storage.mapper.provider.base;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import org.junit.jupiter.api.Test;

class TestScimUserGroupRelSoftDeleteSQLProvider {

  private static final String MILLISECOND_TIMESTAMP_COMPONENT =
      "EXTRACT(MICROSECOND FROM CURRENT_TIMESTAMP(3)) / 1000";

  private static final long GROUP_ID = 200L;
  private static final long USER_ID = 100L;

  @Test
  void testUserIdMs() {
    String sql = new ScimUserGroupRelBaseSQLProvider().softDeleteMembersByUserId(USER_ID);

    assertUsesMillisecondTimestamp(sql);
    assertTrue(sql.contains("user_id = #{userId}"));
    assertFalse(sql.contains("metalake"));
  }

  @Test
  void testGroupIdMs() {
    String sql = new ScimUserGroupRelBaseSQLProvider().softDeleteMembersByGroupId(GROUP_ID);

    assertUsesMillisecondTimestamp(sql);
    assertTrue(sql.contains("group_id = #{groupId}"));
    assertFalse(sql.contains("metalake"));
  }

  @Test
  void testOrphanMembershipsNotExists() {
    String sql = new ScimUserGroupRelBaseSQLProvider().softDeleteOrphanMemberships();

    assertUsesMillisecondTimestamp(sql);
    assertTrue(sql.contains("NOT EXISTS"));
    assertTrue(sql.contains("v2_scim_user_meta"));
    assertTrue(sql.contains("v2_scim_group_meta"));
  }

  @Test
  void testGroupUsersMs() {
    String sql =
        new ScimUserGroupRelBaseSQLProvider()
            .softDeleteMembersByGroupAndUserIds(GROUP_ID, List.of(USER_ID));

    assertUsesMillisecondTimestamp(sql);
    assertTrue(sql.contains("group_id = #{groupId}"));
    assertTrue(sql.contains("user_id IN"));
    assertFalse(sql.contains("metalake"));
  }

  private static void assertUsesMillisecondTimestamp(String sql) {
    assertTrue(sql.contains("(UNIX_TIMESTAMP() * 1000.0)"), sql);
    assertTrue(sql.contains(MILLISECOND_TIMESTAMP_COMPONENT), sql);
  }
}
