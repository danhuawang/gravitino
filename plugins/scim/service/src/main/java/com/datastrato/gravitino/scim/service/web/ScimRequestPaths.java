/*
 * Copyright 2026 Datastrato Inc.
 */

package com.datastrato.gravitino.scim.service.web;

import com.datastrato.gravitino.scim.ScimUtils;
import jakarta.servlet.http.HttpServletRequest;
import java.net.URI;
import java.util.Optional;
import java.util.regex.Pattern;
import org.apache.commons.lang3.StringUtils;

/** Parses SCIM auxiliary HTTP paths on port 9201. */
public final class ScimRequestPaths {

  /** Servlet path prefix for IdP-facing SCIM endpoints. */
  public static final String SCIM_SPEC = "/scim/*";

  /** Path prefix for instance-scoped SCIM resources. */
  public static final String SCIM_PREFIX = ScimUtils.SCIM_PREFIX;

  private static final Pattern SCIM_RESOURCE_PATH =
      Pattern.compile("^" + Pattern.quote(ScimUtils.SCIM_PREFIX) + "(Users|Groups)(?:/.*)?$");

  private ScimRequestPaths() {}

  /** Returns the full request path for SCIM routing under Jetty. */
  public static String resolveRequestPath(HttpServletRequest request) {
    return StringUtils.removeEnd(StringUtils.defaultString(request.getServletPath()), "/")
        + StringUtils.defaultString(request.getPathInfo());
  }

  /** Returns the request origin for absolute SCIM locations. */
  public static String requestBaseUri(HttpServletRequest request) {
    URI requestUri = URI.create(request.getRequestURL().toString());
    return requestUri.getScheme() + "://" + requestUri.getAuthority();
  }

  /** Returns whether the request path is under the SCIM namespace. */
  public static boolean isScimPath(String requestPath) {
    return StringUtils.startsWith(requestPath, SCIM_PREFIX);
  }

  /** Returns whether the request path targets Users or Groups resources. */
  public static boolean isScimRootResourcePath(String requestPath) {
    return isScimPath(requestPath) && SCIM_RESOURCE_PATH.matcher(requestPath).matches();
  }

  /** Returns the request base URI when set by filters. */
  public static Optional<String> currentRequestBaseUri() {
    return ScimRequestContext.currentRequestBaseUri();
  }
}
