/*
 * Copyright 2026 Datastrato Pvt Ltd.
 */

package com.datastrato.gravitino.scim.v2.basic.token;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

class TestScimTokenGenerator {
  @Test
  void testGenerate() {
    ScimTokenGenerator.GeneratedToken first = ScimTokenGenerator.generate();
    ScimTokenGenerator.GeneratedToken second = ScimTokenGenerator.generate();

    assertTrue(first.getTokenValue().startsWith(ScimTokenGenerator.TOKEN_PREFIX));
    assertTrue(second.getTokenValue().startsWith(ScimTokenGenerator.TOKEN_PREFIX));
    assertNotEquals(first.getTokenValue(), second.getTokenValue());
    assertEquals(first.getTokenHash(), ScimTokenGenerator.hashToken(first.getTokenValue()));
  }

  @Test
  void testValidPrefix() {
    ScimTokenGenerator.GeneratedToken generated = ScimTokenGenerator.generate();
    assertTrue(ScimTokenGenerator.hasValidPrefix(generated.getTokenValue()));
    assertFalse(ScimTokenGenerator.hasValidPrefix("gravitino_other_token"));
    assertFalse(ScimTokenGenerator.hasValidPrefix(null));
    assertFalse(ScimTokenGenerator.hasValidPrefix(""));
    assertFalse(ScimTokenGenerator.hasValidPrefix("   "));
  }
}
