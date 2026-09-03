/*
 * Copyright 2026 Datastrato Inc.
 */
package com.datastrato.gravitino.dto.requests;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertThrows;

import com.datastrato.gravitino.dto.authorization.IdentitySource;
import org.junit.jupiter.api.Test;

/** Tests for {@link DirectoryUserEnabledBatchUpdateRequest}. */
public class TestDirectoryUserEnabledBatchUpdateRequest {

  @Test
  public void testValidateSuccess() {
    assertDoesNotThrow(
        () ->
            new DirectoryUserEnabledBatchUpdateRequest(
                    new DirectoryUserEnabledBatchUpdateRequest.DirectoryUserEnabledUpdate[] {
                      new DirectoryUserEnabledBatchUpdateRequest.DirectoryUserEnabledUpdate(
                          "sam.o", IdentitySource.LOCAL)
                    },
                    false)
                .validate());
  }

  @Test
  public void testValidateRejectsBlankOrMissingFields() {
    assertThrows(
        IllegalArgumentException.class,
        () -> new DirectoryUserEnabledBatchUpdateRequest(null, false).validate());
    assertThrows(
        IllegalArgumentException.class,
        () ->
            new DirectoryUserEnabledBatchUpdateRequest(
                    new DirectoryUserEnabledBatchUpdateRequest.DirectoryUserEnabledUpdate[0], false)
                .validate());
    assertThrows(
        IllegalArgumentException.class,
        () ->
            new DirectoryUserEnabledBatchUpdateRequest(
                    new DirectoryUserEnabledBatchUpdateRequest.DirectoryUserEnabledUpdate[] {
                      new DirectoryUserEnabledBatchUpdateRequest.DirectoryUserEnabledUpdate(
                          " ", IdentitySource.LOCAL)
                    },
                    false)
                .validate());
    assertThrows(
        IllegalArgumentException.class,
        () ->
            new DirectoryUserEnabledBatchUpdateRequest(
                    new DirectoryUserEnabledBatchUpdateRequest.DirectoryUserEnabledUpdate[] {
                      new DirectoryUserEnabledBatchUpdateRequest.DirectoryUserEnabledUpdate(
                          "sam.o", null)
                    },
                    false)
                .validate());
    assertThrows(
        IllegalArgumentException.class,
        () ->
            new DirectoryUserEnabledBatchUpdateRequest(
                    new DirectoryUserEnabledBatchUpdateRequest.DirectoryUserEnabledUpdate[] {
                      new DirectoryUserEnabledBatchUpdateRequest.DirectoryUserEnabledUpdate(
                          "sam.o", IdentitySource.LOCAL)
                    },
                    null)
                .validate());
  }
}
