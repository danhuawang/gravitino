/*
 * Copyright 2026 Datastrato Inc.
 */
package org.apache.gravitino.client;

import org.apache.gravitino.dto.responses.ErrorResponse;
import org.apache.gravitino.exceptions.ConnectionFailedException;
import org.apache.gravitino.exceptions.MetadataIntegrityException;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

public class TestErrorHandlers {
  private static final String METADATA_INTEGRITY_PUBLIC_MESSAGE =
      "Metadata integrity verification failed";

  @Test
  public void testTableConnectionFailureIsReconstructed() {
    ErrorResponse response = ErrorResponse.connectionFailed("KMS is unavailable");

    Assertions.assertThrows(
        ConnectionFailedException.class, () -> ErrorHandlers.tableErrorHandler().accept(response));
  }

  /** Java clients reconstruct the typed exception from the redacted table error body. */
  @Test
  public void testTableMetadataIntegrityFailureIsReconstructed() {
    ErrorResponse response =
        ErrorResponse.typedInternalError(
            MetadataIntegrityException.class.getSimpleName(), METADATA_INTEGRITY_PUBLIC_MESSAGE);

    MetadataIntegrityException exception =
        Assertions.assertThrows(
            MetadataIntegrityException.class,
            () -> ErrorHandlers.tableErrorHandler().accept(response));
    Assertions.assertEquals(METADATA_INTEGRITY_PUBLIC_MESSAGE, exception.getMessage());
  }
}
