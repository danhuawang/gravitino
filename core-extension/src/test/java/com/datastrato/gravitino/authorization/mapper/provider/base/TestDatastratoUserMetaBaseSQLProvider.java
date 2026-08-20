/*
 * Copyright 2026 Datastrato Pvt Ltd.
 * This software is licensed under the Apache License version 2.
 */
package com.datastrato.gravitino.authorization.mapper.provider.base;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.google.common.collect.Lists;
import org.junit.jupiter.api.Test;

public class TestDatastratoUserMetaBaseSQLProvider {

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
