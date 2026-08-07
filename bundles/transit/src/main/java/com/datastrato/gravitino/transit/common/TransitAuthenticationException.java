/*
 * Copyright 2026 Datastrato Pvt Ltd.
 * This software is licensed under the Apache License version 2.
 */
package com.datastrato.gravitino.transit.common;

import org.apache.gravitino.exceptions.ConnectionFailedException;

/** Indicates that a Transit connection could not resolve usable configured credentials. */
public final class TransitAuthenticationException extends ConnectionFailedException {

  /**
   * Creates an authentication exception with the specified cause and detail message.
   *
   * @param cause cause of the failure
   * @param message detail message
   */
  public TransitAuthenticationException(Throwable cause, String message) {
    super(cause, "%s", message);
  }

  /**
   * Creates an authentication exception with the specified detail message.
   *
   * @param message detail message
   */
  public TransitAuthenticationException(String message) {
    super("%s", message);
  }
}
