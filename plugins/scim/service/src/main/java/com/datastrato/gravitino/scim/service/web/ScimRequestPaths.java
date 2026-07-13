/*
 * Copyright 2026 Datastrato Pvt Ltd.
 * This software is licensed under the Apache License version 2.
 */

package com.datastrato.gravitino.scim.service.web;

import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Parses metalake scope from SCIM auxiliary HTTP paths on port 9201. */
public final class ScimRequestPaths {

  /** Servlet path prefix for IdP-facing SCIM endpoints. */
  public static final String SCIM_SPEC = "/scim/*";

  /** Path prefix for metalake-scoped SCIM resources. */
  public static final String METALAKE_SCIM_PREFIX = "/scim/v2/metalakes/";

  private static final Pattern METALAKE_PATH =
      Pattern.compile("^/scim/v2/metalakes/([^/]+)(?:/.*)?$");

  private ScimRequestPaths() {}

  /**
   * Returns the metalake name encoded in a SCIM request path.
   *
   * @param requestPath servlet path (for example {@code /scim/v2/metalakes/ml1/Users})
   * @return metalake name when the path is metalake-scoped
   */
  public static Optional<String> metalakeFromPath(String requestPath) {
    if (requestPath == null || requestPath.isBlank()) {
      return Optional.empty();
    }
    Matcher matcher = METALAKE_PATH.matcher(requestPath);
    if (!matcher.matches()) {
      return Optional.empty();
    }
    return Optional.of(matcher.group(1));
  }

  /**
   * Returns whether the request path is under the metalake-scoped SCIM namespace.
   *
   * @param requestPath servlet path
   * @return {@code true} when bearer auth and URL scope resolution apply
   */
  public static boolean isMetalakeScopedPath(String requestPath) {
    return requestPath != null && requestPath.startsWith(METALAKE_SCIM_PREFIX);
  }
}
