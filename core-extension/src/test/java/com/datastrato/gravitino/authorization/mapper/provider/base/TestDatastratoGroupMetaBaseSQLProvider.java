/*
 * Copyright 2026 Datastrato Inc.
 */
package com.datastrato.gravitino.authorization.mapper.provider.base;

import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

public class TestDatastratoGroupMetaBaseSQLProvider {

  @Test
  public void testListGroupsByMetalakeWithOriginIncludesUserCount() {
    String sql =
        new DatastratoGroupMetaBaseSQLProvider().listGroupsByMetalakeWithOrigin("metalake");
    assertTrue(sql.contains("as userCount"));
    assertTrue(sql.contains("idp_user_group_rel"));
    assertTrue(sql.contains("scim_user_group_rel"));
    assertTrue(sql.contains("COUNT(DISTINCT ut.user_id)"));
  }
}
