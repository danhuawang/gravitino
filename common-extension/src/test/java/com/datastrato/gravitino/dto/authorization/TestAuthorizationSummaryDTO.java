/*
 * Copyright 2026 Datastrato Pvt Ltd.
 */
package com.datastrato.gravitino.dto.authorization;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import org.junit.jupiter.api.Test;

public class TestAuthorizationSummaryDTO {

  @Test
  public void testCreateSummary() {
    AuthorizationSummaryDTO summary = new AuthorizationSummaryDTO(5, 4, 1, 4, 1, 6, 2);
    assertEquals(5, summary.getUserCount());
    assertEquals(4, summary.getActiveUserCount());
    assertEquals(1, summary.getSuspendedUserCount());
    assertEquals(4, summary.getGroupCount());
    assertEquals(1, summary.getEmptyGroupCount());
    assertEquals(6, summary.getRoleCount());
    assertEquals(2, summary.getUnassignedRoleCount());
  }

  @Test
  public void testRejectsMismatchedUserCounts() {
    assertThrows(
        IllegalArgumentException.class, () -> new AuthorizationSummaryDTO(5, 3, 1, 0, 0, 0, 0));
  }
}
