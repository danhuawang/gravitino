/*
 * Copyright 2026 Datastrato Inc.
 */
package org.apache.gravitino.exceptions;

import com.google.errorprone.annotations.FormatMethod;
import com.google.errorprone.annotations.FormatString;

/** Thrown when metadata does not match its trusted catalog state. */
public class MetadataIntegrityException extends GravitinoRuntimeException {

  /**
   * Constructs an exception with a formatted detail message.
   *
   * @param message the detail message
   * @param args the arguments to the message
   */
  @FormatMethod
  public MetadataIntegrityException(@FormatString String message, Object... args) {
    super(message, args);
  }

  /**
   * Constructs an exception with a cause and formatted detail message.
   *
   * @param cause the cause
   * @param message the detail message
   * @param args the arguments to the message
   */
  @FormatMethod
  public MetadataIntegrityException(Throwable cause, @FormatString String message, Object... args) {
    super(cause, message, args);
  }
}
