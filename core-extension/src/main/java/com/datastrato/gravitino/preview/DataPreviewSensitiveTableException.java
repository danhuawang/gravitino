/*
 * Copyright 2024 Datastrato Inc.
 */
package com.datastrato.gravitino.preview;

/** The exception will be thrown when users preview a data sensitive relational metadata object. */
public class DataPreviewSensitiveTableException extends RuntimeException {
  public DataPreviewSensitiveTableException(String message) {
    super(message);
  }
}
