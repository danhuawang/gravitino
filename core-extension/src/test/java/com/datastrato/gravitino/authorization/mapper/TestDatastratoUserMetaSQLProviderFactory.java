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

  /**
   * PostgreSQL {@code idp_user_meta.enabled} is {@code BOOLEAN}; {@code MAX(boolean)} is illegal.
   * Aggregated enabled must go through integer {@code CASE WHEN} and cast SCIM {@code SMALLINT}.
   */
  @Test
  public void testPostgreSQLGetUserEnabledAggregationAvoidsMaxBoolean() {
    DatastratoUserMetaSQLProviderFactory.DatastratoUserMetaPostgreSQLProvider provider =
        new DatastratoUserMetaSQLProviderFactory.DatastratoUserMetaPostgreSQLProvider();

    String sql = provider.getUserByMetalakeWithOrigin("metalake", "admin");
    String groupUsersSql = provider.listUsersForMetalakeGroupWithOrigin("metalake", "g1");

    for (String s : new String[] {sql, groupUsersSql}) {
      assertTrue(
          s.contains(
              "COALESCE(MAX(CASE WHEN COALESCE((su.enabled <> 0), iu.enabled) IS NULL THEN NULL"
                  + " WHEN COALESCE((su.enabled <> 0), iu.enabled) THEN 1 ELSE 0 END), 1) <> 0"
                  + " as enabled"));
      assertFalse(
          s.contains(
              "COALESCE(MAX(CASE WHEN COALESCE((su.enabled <> 0), iu.enabled) THEN 1 ELSE 0"
                  + " END), 1)"));
      assertFalse(s.contains("MAX(COALESCE((su.enabled <> 0), iu.enabled))"));
      assertFalse(s.contains("MAX(COALESCE(su.enabled, iu.enabled))"));
    }
  }

  private static void assertUsesJsonArrayAgg(String sql) {
    assertTrue(sql.contains("JSON_ARRAYAGG(rot.role_name) as roleNames"));
    assertTrue(sql.contains("JSON_ARRAYAGG(rot.role_id) as roleIds"));
    assertTrue(sql.contains("JSON_ARRAYAGG(membership.groupName) as groupNames"));
    assertFalse(sql.contains("JSON_AGG"));
  }
}
