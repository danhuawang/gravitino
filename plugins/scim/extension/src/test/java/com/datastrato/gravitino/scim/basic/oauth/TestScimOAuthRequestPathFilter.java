/*
 * Copyright 2026 Datastrato Pvt Ltd.
 */

package com.datastrato.gravitino.scim.basic.oauth;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

class TestScimOAuthRequestPathFilter {

  @Test
  void testParseFromCatalog() {
    assertEquals(
        "ml1",
        ScimOAuthRequestPathFilter.parseMetalakeName("/api/metalakes/ml1/catalogs/hive/schemas/db")
            .orElseThrow());
  }

  @Test
  void testParseFromRoot() {
    assertEquals(
        "ml1", ScimOAuthRequestPathFilter.parseMetalakeName("/api/metalakes/ml1").orElseThrow());
  }

  @Test
  void testParseNonMetalake() {
    assertTrue(ScimOAuthRequestPathFilter.parseMetalakeName("/api/version").isEmpty());
    assertTrue(ScimOAuthRequestPathFilter.parseMetalakeName(null).isEmpty());
  }
}
