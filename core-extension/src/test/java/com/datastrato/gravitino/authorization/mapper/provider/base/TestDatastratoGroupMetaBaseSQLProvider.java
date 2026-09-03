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
    assertTrue(sql.contains("idp_user_group_rel"));
    assertTrue(sql.contains("scim_user_group_rel"));
    assertTrue(sql.contains("scim_group_meta"));
    assertTrue(sql.contains("scim_user_meta"));
    assertFalse(sql.contains("gt.external_id"));
    assertTrue(sql.contains("COUNT(DISTINCT ut.user_id)"));
  }
}
