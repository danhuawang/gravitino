/*
 * Copyright 2026 Datastrato Pvt Ltd.
 */

package com.datastrato.gravitino.scim.v2;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

import org.junit.jupiter.api.Test;

class TestScimUtils {

  @Test
  void testBlankToNull() {
    assertNull(ScimUtils.blankToNull(null));
    assertNull(ScimUtils.blankToNull(""));
    assertNull(ScimUtils.blankToNull("   "));
    assertEquals("id", ScimUtils.blankToNull("id"));
  }

  @Test
  void testBlankToUnknown() {
    assertEquals("unknown", ScimUtils.blankToUnknown(null));
    assertEquals("unknown", ScimUtils.blankToUnknown(""));
    assertEquals("unknown", ScimUtils.blankToUnknown("   "));
    assertEquals("alice", ScimUtils.blankToUnknown("alice"));
  }
}
