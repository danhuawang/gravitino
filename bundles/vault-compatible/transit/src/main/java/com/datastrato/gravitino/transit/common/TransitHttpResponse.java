/*
 * Copyright 2026 Datastrato Inc.
 */
package com.datastrato.gravitino.transit.common;

import java.util.Arrays;

/** Immutable bounded response returned to typed Transit capability layers. */
public final class TransitHttpResponse {

  private final int statusCode;
  private final byte[] body;

  TransitHttpResponse(int statusCode, byte[] body) {
    this.statusCode = statusCode;
    this.body = Arrays.copyOf(body, body.length);
  }

  /**
   * Returns the HTTP status code.
   *
   * @return HTTP status code
   */
  public int statusCode() {
    return statusCode;
  }

  /**
   * Returns a copy of the bounded response body.
   *
   * @return response body bytes
   */
  public byte[] body() {
    return Arrays.copyOf(body, body.length);
  }
}
