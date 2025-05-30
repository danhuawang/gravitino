/*
 * Copyright 2024 Datastrato Pvt Ltd.
 * This software is licensed under the Apache License version 2.
 */
package com.datastrato.gravitino.preview;

/** The exception will be thrown when users preview a data sensitive relational metadata object. */
public class DataPreviewSensitiveTableException extends RuntimeException {
  public DataPreviewSensitiveTableException(String message) {
    super(message);
  }
}
