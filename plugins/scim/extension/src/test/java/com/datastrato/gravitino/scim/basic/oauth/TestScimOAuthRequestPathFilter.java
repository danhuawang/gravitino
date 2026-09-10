/*
 * Copyright 2026 Datastrato Inc.
 */

package com.datastrato.gravitino.scim.basic.oauth;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

class TestScimOAuthRequestPathFilter {

  @AfterEach
  void tearDown() {
    ScimOAuthRequestPathFilter.clear();
  }

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

  @Test
  void testBearerTokenLifecycle() {
    ScimOAuthRequestPathFilter.bind("/api/version", "bearer token-value");
    assertEquals("token-value", ScimOAuthRequestPathFilter.currentBearerToken().orElseThrow());

    ScimOAuthRequestPathFilter.clear();
    assertTrue(ScimOAuthRequestPathFilter.currentBearerToken().isEmpty());
  }

  @Test
  void testRejectsNonBearerAuthorization() {
    ScimOAuthRequestPathFilter.bind("/api/version", "Basic credentials");
    assertTrue(ScimOAuthRequestPathFilter.currentBearerToken().isEmpty());
  }
}
