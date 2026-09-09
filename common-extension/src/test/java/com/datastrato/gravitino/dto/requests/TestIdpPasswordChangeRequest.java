/*
 * Copyright 2026 Datastrato Inc.
 */
package com.datastrato.gravitino.dto.requests;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

/** Tests for {@link IdpPasswordChangeRequest}. */
public class TestIdpPasswordChangeRequest {

  @Test
  public void testValidateSuccess() {
    assertDoesNotThrow(
        () ->
            new IdpPasswordChangeRequest("dana.k", "ChangeMe-2026!", "ChangeMe-2027!").validate());
  }

  @Test
  public void testValidateRejectsBlankUsername() {
    IllegalArgumentException ex =
        assertThrows(
            IllegalArgumentException.class,
            () -> new IdpPasswordChangeRequest(" ", "ChangeMe-2026!", "ChangeMe-2027!").validate());
    assertTrue(ex.getMessage().contains("username"));
  }

  @Test
  public void testValidateRejectsBlankOldPassword() {
    IllegalArgumentException ex =
        assertThrows(
            IllegalArgumentException.class,
            () -> new IdpPasswordChangeRequest("dana.k", " ", "ChangeMe-2027!").validate());
    assertTrue(ex.getMessage().contains("oldPassword"));
  }

  @Test
  public void testValidateRejectsShortNewPassword() {
    IllegalArgumentException ex =
        assertThrows(
            IllegalArgumentException.class,
            () -> new IdpPasswordChangeRequest("dana.k", "ChangeMe-2026!", "short").validate());
    assertTrue(ex.getMessage().contains("Password"));
  }

  @Test
  public void testValidateRejectsSamePasswords() {
    IllegalArgumentException ex =
        assertThrows(
            IllegalArgumentException.class,
            () ->
                new IdpPasswordChangeRequest("dana.k", "ChangeMe-2026!", "ChangeMe-2026!")
                    .validate());
    assertTrue(ex.getMessage().contains("differ"));
  }
}
