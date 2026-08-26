/*
 * Copyright 2026 Datastrato Pvt Ltd.
 */

package com.datastrato.gravitino.scim.service.web;

import com.datastrato.gravitino.scim.ScimUtils;
import jakarta.servlet.http.HttpServletRequest;
import java.net.URI;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.apache.commons.lang3.StringUtils;

/** Parses metalake scope from SCIM auxiliary HTTP paths on port 9201. */
public final class ScimRequestPaths {

  /** Servlet path prefix for IdP-facing SCIM endpoints. */
  public static final String SCIM_SPEC = "/scim/*";

  /** Path prefix for metalake-scoped SCIM resources. */
  public static final String METALAKE_SCIM_PREFIX = ScimUtils.METALAKE_SCIM_PREFIX;

  private static final Pattern METALAKE_PATH =
      Pattern.compile("^" + Pattern.quote(ScimUtils.METALAKE_SCIM_PREFIX) + "([^/]+)(?:/.*)?$");

  private ScimRequestPaths() {}

  /**
   * Returns the full request path for SCIM routing under Jetty.
   *
   * <p>The Jersey servlet and filters are mapped to {@link #SCIM_SPEC}. For {@code GET
   * /scim/v2/metalakes/ml1/Users}, Jetty exposes {@code servletPath=/scim} and {@code
   * pathInfo=/v2/metalakes/ml1/Users}; this method joins them for filter routing.
   *
   * @param request incoming HTTP request
   * @return normalized path such as {@code /scim/v2/metalakes/ml1/Users}
   */
  public static String resolveRequestPath(HttpServletRequest request) {
    return StringUtils.removeEnd(StringUtils.defaultString(request.getServletPath()), "/")
        + StringUtils.defaultString(request.getPathInfo());
  }

  /**
   * Returns the request origin ({@code scheme://host[:port]}) for absolute SCIM locations.
   *
   * <p>Matches the host used by JAX-RS {@code UriInfo} when building the HTTP {@code Location}
   * header, so {@code meta.location} can use the same absolute URI shape.
   *
   * @param request incoming HTTP request
   * @return origin without a path, for example {@code http://localhost:9201}
   */
  public static String requestBaseUri(HttpServletRequest request) {
    URI requestUri = URI.create(request.getRequestURL().toString());
    return requestUri.getScheme() + "://" + requestUri.getAuthority();
  }

  /**
   * Returns the metalake name encoded in a SCIM request path.
   *
   * @param requestPath servlet path (for example {@code /scim/v2/metalakes/ml1/Users})
   * @return metalake name when the path is metalake-scoped
   */
  public static Optional<String> metalakeFromPath(String requestPath) {
    if (StringUtils.isBlank(requestPath)) {
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
    return StringUtils.startsWith(requestPath, METALAKE_SCIM_PREFIX);
  }
}
