/*
 * Copyright 2026 Datastrato Inc.
 */
package org.apache.gravitino.iceberg.service;

import org.apache.gravitino.encryption.EncryptionAdmissionService;
import org.apache.gravitino.encryption.EncryptionAdmissionService.AdmissionFailure;
import org.apache.gravitino.encryption.EncryptionAdmissionService.FailureKind;
import org.apache.iceberg.exceptions.BadRequestException;
import org.apache.iceberg.exceptions.ServiceUnavailableException;

/**
 * The one place an admission refusal becomes an Iceberg REST exception, for the table dispatcher
 * and the registration service alike, so one refusal reaches the client with one status and one
 * error type whichever request carried it. Only the exception class is this API's own; the kind,
 * the decision id and the message come from the {@link EncryptionAdmissionService}.
 *
 * <p>The exception class is the wire contract, not an implementation detail: the REST layer reports
 * the thrown class's simple name as the response's error type, and an Iceberg REST client
 * reconstructs and rethrows that type. A format-version refusal is therefore raised as {@link
 * IllegalArgumentException}, which the client rethrows as itself and which the server's exception
 * mapper already answers with {@code 400}, rather than as {@link BadRequestException}, which the
 * client would turn back into a generic bad-request failure.
 */
public final class EncryptionAdmissionRejections {

  private EncryptionAdmissionRejections() {}

  /**
   * Translates an admission failure into the exception the IRC boundary throws for it: {@code 503}
   * for a KMS that could not be reached, a server-side outage, and {@code 400} for every other
   * refusal, which is the caller's request.
   *
   * @param failure the refusal the admission service decided
   * @return the exception to throw, carrying the service's message unchanged
   */
  public static RuntimeException toRestException(AdmissionFailure failure) {
    if (failure.kind() == FailureKind.KMS_UNAVAILABLE) {
      return new ServiceUnavailableException("%s", failure.message());
    }
    if (failure.kind() == FailureKind.UNSUPPORTED_FORMAT_VERSION) {
      return new IllegalArgumentException(failure.message());
    }
    return new BadRequestException("%s", failure.message());
  }
}
