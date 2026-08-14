/*
 * Copyright 2026 Datastrato Pvt Ltd.
 * This software is licensed under the Apache License version 2.
 */

package com.datastrato.gravitino.scim.service.web;

import com.google.common.base.Preconditions;
import java.util.Optional;
import org.apache.commons.lang3.StringUtils;

/**
 * Request-scoped context for SCIM repository adapters.
 *
 * <p>Set by {@code ScimURLScopeResolver} on port 9201 before SCIMple invokes repository adapters.
 * Holds the metalake name and the request origin ({@code scheme://host[:port]}) used to build
 * absolute {@code meta.location} URIs.
 */
public final class ScimMetalakeContext {

  private static final ThreadLocal<String> METALAKE = new ThreadLocal<>();
  private static final ThreadLocal<String> REQUEST_BASE_URI = new ThreadLocal<>();

  private ScimMetalakeContext() {}

  /**
   * Sets the active metalake name for the current request thread.
   *
   * @param metalakeName metalake name parsed from the SCIM URL
   */
  public static void setMetalake(String metalakeName) {
    METALAKE.set(metalakeName);
  }

  /**
   * Sets the request origin used for absolute SCIM resource locations.
   *
   * @param requestBaseUri origin such as {@code http://localhost:9201} (no path)
   */
  public static void setRequestBaseUri(String requestBaseUri) {
    REQUEST_BASE_URI.set(requestBaseUri);
  }

  /**
   * Returns the active metalake name for the current request thread.
   *
   * @return metalake name
   */
  public static String getMetalake() {
    String metalake = METALAKE.get();
    Preconditions.checkState(
        metalake != null && !metalake.isBlank(), "SCIM metalake context is not set");
    return metalake;
  }

  /**
   * Returns the active metalake when the request context has been set.
   *
   * @return metalake name, or empty when unset (for example in unit tests)
   */
  public static Optional<String> currentMetalake() {
    String metalake = METALAKE.get();
    return StringUtils.isBlank(metalake) ? Optional.empty() : Optional.of(metalake);
  }

  /**
   * Returns the request origin when set for the current thread.
   *
   * @return origin such as {@code http://localhost:9201}, or empty when unset
   */
  public static Optional<String> currentRequestBaseUri() {
    String baseUri = REQUEST_BASE_URI.get();
    return StringUtils.isBlank(baseUri) ? Optional.empty() : Optional.of(baseUri);
  }

  /** Clears request-scoped metalake and base-URI context. */
  public static void clear() {
    METALAKE.remove();
    REQUEST_BASE_URI.remove();
  }
}
