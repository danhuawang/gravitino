/*
 * Copyright 2026 Datastrato Inc.
 */
package com.datastrato.gravitino.authorization.mapper;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

/** Tests for {@link DatastratoUserMetaSQLProviderFactory}. */
public class TestDatastratoUserMetaSQLProviderFactory {

  /** Verifies MySQL user list SQL keeps using the supported JSON aggregate function. */
  @Test
  public void testMySQLListUserWithGroupsUsesJsonArrayAgg() {
    DatastratoUserMetaSQLProviderFactory.DatastratoUserMetaMySQLProvider provider =
        new DatastratoUserMetaSQLProviderFactory.DatastratoUserMetaMySQLProvider();

    assertUsesJsonArrayAgg(provider.listUserWithGroupsPOsByMetalakeName("metalake"));
  }

  /** Verifies H2 user list SQL keeps using the supported JSON aggregate function. */
  @Test
  public void testH2ListUserWithGroupsUsesJsonArrayAgg() {
    DatastratoUserMetaSQLProviderFactory.DatastratoUserMetaH2Provider provider =
        new DatastratoUserMetaSQLProviderFactory.DatastratoUserMetaH2Provider();

    assertUsesJsonArrayAgg(provider.listUserWithGroupsPOsByMetalakeName("metalake"));
  }

  /** Verifies PostgreSQL user list SQL uses the supported JSON aggregate function. */
  @Test
  public void testPostgreSQLListUserWithGroupsUsesJsonAgg() {
    DatastratoUserMetaSQLProviderFactory.DatastratoUserMetaPostgreSQLProvider provider =
        new DatastratoUserMetaSQLProviderFactory.DatastratoUserMetaPostgreSQLProvider();

    String sql = provider.listUserWithGroupsPOsByMetalakeName("metalake");

    assertTrue(sql.contains("JSON_AGG(rot.role_name) as roleNames"));
    assertTrue(sql.contains("JSON_AGG(rot.role_id) as roleIds"));
    assertTrue(sql.contains("JSON_AGG(membership.groupName) as groupNames"));
    assertFalse(sql.contains("JSON_ARRAYAGG"));
  }

  private static void assertUsesJsonArrayAgg(String sql) {
    assertTrue(sql.contains("JSON_ARRAYAGG(rot.role_name) as roleNames"));
    assertTrue(sql.contains("JSON_ARRAYAGG(rot.role_id) as roleIds"));
    assertTrue(sql.contains("JSON_ARRAYAGG(membership.groupName) as groupNames"));
    assertFalse(sql.contains("JSON_AGG"));
  }
}
