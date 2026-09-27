/*
 * Copyright 2026 Datastrato Inc.
 */
package org.apache.gravitino.iceberg.service;

import java.util.List;
import org.apache.gravitino.encryption.EncryptionAdmissionService.AdmissionFailure;
import org.apache.gravitino.encryption.EncryptionAdmissionService.FailureKind;
import org.apache.iceberg.exceptions.BadRequestException;
import org.apache.iceberg.exceptions.ServiceUnavailableException;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

/**
 * Tests the one mapping from an admission refusal to the Iceberg REST exception it is thrown as.
 */
public class TestEncryptionAdmissionRejections {

  /**
   * Every failure kind is thrown as its wire type: an unreachable KMS as the server's 503, an
   * unsupported format version as the {@code IllegalArgumentException} a client rethrows as itself,
   * and every other refusal as a 400. The service's message crosses unchanged, format directives
   * included, with the decision id it already names.
   */
  @ParameterizedTest
  @EnumSource(FailureKind.class)
  void testEachFailureKindIsThrownAsItsRestExceptionWithTheMessageUnchanged(FailureKind kind) {
    AdmissionFailure failure =
        new AdmissionFailure(kind, "d-1", "refused %s for table t: decisionId=d-1", List.of());

    RuntimeException exception = EncryptionAdmissionRejections.toRestException(failure);

    Assertions.assertEquals(restExceptionFor(kind), exception.getClass());
    Assertions.assertEquals(failure.message(), exception.getMessage());
  }

  private static Class<? extends RuntimeException> restExceptionFor(FailureKind kind) {
    switch (kind) {
      case KMS_UNAVAILABLE:
        return ServiceUnavailableException.class;
      case UNSUPPORTED_FORMAT_VERSION:
        return IllegalArgumentException.class;
      default:
        return BadRequestException.class;
    }
  }
}
