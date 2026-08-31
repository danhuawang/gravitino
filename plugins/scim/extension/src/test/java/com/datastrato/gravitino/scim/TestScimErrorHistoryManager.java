/*
 * Copyright 2026 Datastrato Inc.
 */

package com.datastrato.gravitino.scim;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

class TestScimErrorHistoryManager {

  private static final String USERS = "/scim/v2/metalakes/m1/Users/42";

  @Test
  void testShouldRecord() {
    assertFalse(ScimErrorHistoryManager.shouldRecord(200, USERS));
    assertFalse(ScimErrorHistoryManager.shouldRecord(404, USERS));
    assertTrue(ScimErrorHistoryManager.shouldRecord(400, USERS));
    assertTrue(ScimErrorHistoryManager.shouldRecord(409, "/scim/v2/metalakes/m1/Users"));
    assertTrue(ScimErrorHistoryManager.shouldRecord(409, "/scim/v2/metalakes/m1/Users/.search"));
    assertTrue(ScimErrorHistoryManager.shouldRecord(500, "/scim/v2/metalakes/m1/Groups"));
    assertFalse(ScimErrorHistoryManager.shouldRecord(409, "/api/metalakes/m1/scim/tokens"));
    assertFalse(
        ScimErrorHistoryManager.shouldRecord(500, "/scim/v2/metalakes/m1/ServiceProviderConfig"));
    assertFalse(ScimErrorHistoryManager.shouldRecord(400, "/scim/v2/metalakes/m1/Schemas"));
  }
}
