/*
 * Copyright 2026 Datastrato Inc.
 */
package org.apache.gravitino.exceptions;

import com.google.errorprone.annotations.FormatMethod;
import com.google.errorprone.annotations.FormatString;

/**
 * Thrown when re-registering a metadata file would change a table's encryption key binding.
 *
 * <p>Enrolling an unencrypted table, rekeying an encrypted one and stripping its key are governed
 * changes that happen through admission, never through a repoint, so a backend refuses them before
 * it publishes the pointer.
 *
 * <p>It extends {@link IllegalArgumentException} so that a caller which does not know this type
 * still treats the refusal as a bad request. The type exists to be recognised, not to travel: the
 * Iceberg REST server resolves an HTTP status by exact class and writes the class's simple name
 * into the error body's {@code type} field, and the Iceberg REST client turns any 400 whose {@code
 * type} is not the literal {@code "IllegalArgumentException"} into a {@code BadRequestException}.
 * That server's exception mapper therefore writes this out as a plain {@link
 * IllegalArgumentException} carrying this one as its cause, on every route, leaving the wire
 * unchanged; handlers upstream of it recognise the type and rethrow it as it is.
 */
public class EncryptionKeyRepointException extends IllegalArgumentException {

  /**
   * Constructs an exception with a formatted detail message.
   *
   * @param message the detail message
   * @param args the arguments to the message
   */
  @FormatMethod
  public EncryptionKeyRepointException(@FormatString String message, Object... args) {
    super(String.format(message, args));
  }
}
