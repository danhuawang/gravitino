/*
 * Copyright 2026 Datastrato Inc.
 */
package com.datastrato.gravitino.dto.requests;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.util.List;
import org.junit.jupiter.api.Test;

/** Tests for {@link DirectoryUserAddRequest}. */
public class TestDirectoryUserAddRequest {

  private static final String VALID_PASSWORD = "ChangeMe-2026!";

  @Test
  public void testValidateSuccess() {
    assertDoesNotThrow(
        () ->
            new DirectoryUserAddRequest("jordan.m", VALID_PASSWORD, List.of("governance"))
                .validate());
    assertDoesNotThrow(
        () -> new DirectoryUserAddRequest("jordan.m", VALID_PASSWORD, null).validate());
  }

  @Test
  public void testValidateRejectsInvalidFields() {
    assertThrows(
        IllegalArgumentException.class,
        () -> new DirectoryUserAddRequest(" ", VALID_PASSWORD, null).validate());
    assertThrows(
        IllegalArgumentException.class,
        () -> new DirectoryUserAddRequest("bad:user", VALID_PASSWORD, null).validate());
    assertThrows(
        IllegalArgumentException.class,
        () -> new DirectoryUserAddRequest("jordan.m", "short", null).validate());
    assertThrows(
        IllegalArgumentException.class,
        () -> new DirectoryUserAddRequest("jordan.m", VALID_PASSWORD, List.of(" ")).validate());
  }
}
