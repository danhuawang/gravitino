/*
 * Copyright 2026 Datastrato Pvt Ltd.
 */
package org.apache.gravitino.client;

import org.apache.gravitino.dto.responses.ErrorResponse;
import org.apache.gravitino.exceptions.ConnectionFailedException;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

public class TestErrorHandlers {

  @Test
  public void testTableConnectionFailureIsReconstructed() {
    ErrorResponse response = ErrorResponse.connectionFailed("KMS is unavailable");

    Assertions.assertThrows(
        ConnectionFailedException.class, () -> ErrorHandlers.tableErrorHandler().accept(response));
  }
}
