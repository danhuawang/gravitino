/*
 * Copyright 2026 Datastrato Pvt Ltd.
 * This software is licensed under the Apache License version 2.
 */
package com.datastrato.gravitino.transit.common;

import java.util.Arrays;

/** Immutable internal response returned by the shared Transit connection. */
final class TransitHttpResponse {

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
  int statusCode() {
    return statusCode;
  }

  /**
   * Returns a copy of the bounded response body.
   *
   * @return response body bytes
   */
  byte[] body() {
    return Arrays.copyOf(body, body.length);
  }
}
