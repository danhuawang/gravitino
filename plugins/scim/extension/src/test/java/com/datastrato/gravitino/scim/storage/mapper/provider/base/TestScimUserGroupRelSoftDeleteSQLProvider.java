/*
 * Copyright 2026 Datastrato Pvt Ltd.
 * This software is licensed under the Apache License version 2.
 */

package com.datastrato.gravitino.scim.storage.mapper.provider.base;

import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import org.junit.jupiter.api.Test;

class TestScimUserGroupRelSoftDeleteSQLProvider {

  private static final String MILLISECOND_TIMESTAMP_COMPONENT =
      "EXTRACT(MICROSECOND FROM CURRENT_TIMESTAMP(3)) / 1000";

  @Test
  void testUserExtIdMs() {
    String sql =
        new ScimUserGroupRelBaseSQLProvider()
            .softDeleteMembersByUserExternalId("test_metalake", "user-ext-1");

    assertUsesMillisecondTimestamp(sql);
    assertTrue(sql.contains("mm.metalake_name = #{metalakeName}"));
    assertTrue(sql.contains("u.external_id = #{userExternalId}"));
  }

  @Test
  void testGroupExtIdMs() {
    String sql =
        new ScimUserGroupRelBaseSQLProvider()
            .softDeleteMembersByGroupExternalId("test_metalake", "group-ext-1");

    assertUsesMillisecondTimestamp(sql);
    assertTrue(sql.contains("mm.metalake_name = #{metalakeName}"));
    assertTrue(sql.contains("g.external_id = #{groupExternalId}"));
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
            .softDeleteMembersByGroupAndUserExternalIds(
                "test_metalake", "group-ext-1", List.of("user-ext-1"));

    assertUsesMillisecondTimestamp(sql);
    assertTrue(sql.contains("g.external_id = #{groupExternalId}"));
    assertTrue(sql.contains("u.external_id IN"));
  }

  private static void assertUsesMillisecondTimestamp(String sql) {
    assertTrue(sql.contains("(UNIX_TIMESTAMP() * 1000.0)"), sql);
    assertTrue(sql.contains(MILLISECOND_TIMESTAMP_COMPONENT), sql);
  }
}
