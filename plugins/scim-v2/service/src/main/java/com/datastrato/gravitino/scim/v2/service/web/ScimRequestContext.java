/*
 * Copyright 2026 Datastrato Inc.
 */

package com.datastrato.gravitino.scim.v2.service.web;

import java.util.Optional;

/** Request-scoped helpers for SCIM v2 auxiliary HTTP handling. */
public final class ScimRequestContext {

  private static final ThreadLocal<String> CURRENT_REQUEST_BASE_URI = new ThreadLocal<>();

  private ScimRequestContext() {}

  /** Binds the request base URI for the current thread. */
  public static void bindRequestBaseUri(String baseUri) {
    CURRENT_REQUEST_BASE_URI.set(baseUri);
  }

  /** Returns the bound request base URI. */
  public static Optional<String> currentRequestBaseUri() {
    return Optional.ofNullable(CURRENT_REQUEST_BASE_URI.get());
  }

  /** Clears request-scoped state. */
  public static void clear() {
    CURRENT_REQUEST_BASE_URI.remove();
  }
}
