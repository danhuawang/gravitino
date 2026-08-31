/*
 * Copyright 2026 Datastrato Inc.
 */
package com.datastrato.gravitino.transit.common;

/** Indicates invalid configuration shared by Transit-compatible capability clients. */
public final class TransitConfigurationException extends IllegalArgumentException {

  /**
   * Creates a configuration exception with the specified detail message.
   *
   * @param message detail message
   */
  public TransitConfigurationException(String message) {
    super(message);
  }

  /**
   * Creates a configuration exception with the specified cause and detail message.
   *
   * @param cause cause of the failure
   * @param message detail message
   */
  public TransitConfigurationException(Throwable cause, String message) {
    super(message, cause);
  }
}
