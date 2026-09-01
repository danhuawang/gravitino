/*
 * Copyright 2026 Datastrato Pvt Ltd.
 */

package com.datastrato.gravitino.scim.v2.service;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

class TestScimHealthCheckPathMatcher {

  private final ScimHealthCheckPathMatcher matcher = new ScimHealthCheckPathMatcher();

  @Test
  void testHealthCheckPaths() {
    assertTrue(matcher.isHealthCheckPath("/health"));
    assertTrue(matcher.isHealthCheckPath("/health/live"));
    assertTrue(matcher.isHealthCheckPath("/health.html"));
    assertTrue(matcher.isHealthCheckPath("/scim/health"));
    assertTrue(matcher.isHealthCheckPath("/scim/health/ready"));
    assertFalse(matcher.isHealthCheckPath("/scim/v2/Users"));
    assertFalse(matcher.isHealthCheckPath(null));
  }
}
