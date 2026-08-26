/*
 * Copyright 2026 Datastrato Pvt Ltd.
 */

package com.datastrato.gravitino.scim.storage.mapper.provider.base;

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
    String sql =
        new ScimUserGroupRelBaseSQLProvider().softDeleteMembersByUserId("test_metalake", USER_ID);

    assertUsesMillisecondTimestamp(sql);
    assertTrue(sql.contains("mm.metalake_name = #{metalakeName}"));
    assertTrue(sql.contains("r.user_id = #{userId}"));
    assertFalse(sql.contains("external_id"));
  }

  @Test
  void testGroupIdMs() {
    String sql =
        new ScimUserGroupRelBaseSQLProvider().softDeleteMembersByGroupId("test_metalake", GROUP_ID);

    assertUsesMillisecondTimestamp(sql);
    assertTrue(sql.contains("mm.metalake_name = #{metalakeName}"));
    assertTrue(sql.contains("r.group_id = #{groupId}"));
    assertFalse(sql.contains("external_id"));
  }

  @Test
  void testUnavailableNotExists() {
    String sql = new ScimUserGroupRelBaseSQLProvider().softDeleteMembersByUnavailableMetalake();

    assertUsesMillisecondTimestamp(sql);
    assertTrue(sql.contains("NOT EXISTS"));
    assertTrue(sql.contains("metalake_meta"));
    assertTrue(sql.contains("m.deleted_at = 0"));
  }

  @Test
  void testGroupUsersMs() {
    String sql =
        new ScimUserGroupRelBaseSQLProvider()
            .softDeleteMembersByGroupAndUserIds("test_metalake", GROUP_ID, List.of(USER_ID));

    assertUsesMillisecondTimestamp(sql);
    assertTrue(sql.contains("r.group_id = #{groupId}"));
    assertTrue(sql.contains("r.user_id IN"));
    assertFalse(sql.contains("external_id"));
  }

  private static void assertUsesMillisecondTimestamp(String sql) {
    assertTrue(sql.contains("(UNIX_TIMESTAMP() * 1000.0)"), sql);
    assertTrue(sql.contains(MILLISECOND_TIMESTAMP_COMPONENT), sql);
  }
}
