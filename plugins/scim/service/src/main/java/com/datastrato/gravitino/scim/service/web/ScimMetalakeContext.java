/*
 * Copyright 2026 Datastrato Pvt Ltd.
 * This software is licensed under the Apache License version 2.
 */

package com.datastrato.gravitino.scim.service.web;

import com.google.common.base.Preconditions;

/**
 * Request-scoped metalake context for SCIM repository adapters.
 *
 * <p>Set by {@code ScimURLScopeResolver} on port 9201 before SCIMple invokes repository adapters.
 */
public final class ScimMetalakeContext {

  private static final ThreadLocal<String> METALAKE = new ThreadLocal<>();

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

  /** Clears request-scoped metalake context. */
  public static void clear() {
    METALAKE.remove();
  }
}
