/*
 * Copyright 2026 Datastrato Inc.
 */

package com.datastrato.gravitino.scim;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import org.junit.jupiter.api.Test;

class TestScimFieldLimits {

  @Test
  void testNullAndExactLimitPass() {
    assertDoesNotThrow(() -> ScimFieldLimits.checkMaxLength(null, 1, "userName", "user"));
    assertDoesNotThrow(
        () ->
            ScimFieldLimits.checkMaxLength(
                "a".repeat(ScimFieldLimits.MAX_NAME_LENGTH),
                ScimFieldLimits.MAX_NAME_LENGTH,
                "userName",
                "user"));
    assertDoesNotThrow(
        () ->
            ScimFieldLimits.checkGroup(
                "g".repeat(ScimFieldLimits.MAX_NAME_LENGTH),
                "e".repeat(ScimFieldLimits.MAX_EXTERNAL_ID_LENGTH),
                "c".repeat(ScimFieldLimits.MAX_GROUP_COMMENT_LENGTH)));
  }

  @Test
  void testCodePointsNotUtf16Chars() {
    String emoji = "\uD83D\uDE00";
    assertEquals(2, emoji.length());
    assertEquals(1, emoji.codePointCount(0, emoji.length()));
    String atLimit = "a".repeat(ScimFieldLimits.MAX_NAME_LENGTH - 1) + emoji;
    assertDoesNotThrow(
        () ->
            ScimFieldLimits.checkMaxLength(
                atLimit, ScimFieldLimits.MAX_NAME_LENGTH, "userName", "user"));

    String overLimit = "a".repeat(ScimFieldLimits.MAX_NAME_LENGTH) + emoji;
    IllegalArgumentException exception =
        assertThrows(
            IllegalArgumentException.class,
            () ->
                ScimFieldLimits.checkMaxLength(
                    overLimit, ScimFieldLimits.MAX_NAME_LENGTH, "userName", "user"));
    assertEquals(
        "The userName of the SCIM user must not exceed 128 characters", exception.getMessage());
  }

  @Test
  void testExternalIdAndGroupCommentLimits() {
    IllegalArgumentException externalId =
        assertThrows(
            IllegalArgumentException.class,
            () ->
                ScimFieldLimits.checkExternalId(
                    "x".repeat(ScimFieldLimits.MAX_EXTERNAL_ID_LENGTH + 1), "user"));
    assertEquals(
        "The externalId of the SCIM user must not exceed 256 characters", externalId.getMessage());

    IllegalArgumentException comment =
        assertThrows(
            IllegalArgumentException.class,
            () ->
                ScimFieldLimits.checkGroup(
                    "engineers", null, "c".repeat(ScimFieldLimits.MAX_GROUP_COMMENT_LENGTH + 1)));
    assertEquals(
        "The group comment of the SCIM group must not exceed 1024 characters",
        comment.getMessage());

    IllegalArgumentException tokenName =
        assertThrows(
            IllegalArgumentException.class,
            () ->
                ScimFieldLimits.checkToken("t".repeat(ScimFieldLimits.MAX_TOKEN_NAME_LENGTH + 1)));
    assertEquals(
        "The tokenName of the SCIM token must not exceed 256 characters", tokenName.getMessage());
    assertDoesNotThrow(
        () -> ScimFieldLimits.checkToken("t".repeat(ScimFieldLimits.MAX_TOKEN_NAME_LENGTH)));
  }
}
