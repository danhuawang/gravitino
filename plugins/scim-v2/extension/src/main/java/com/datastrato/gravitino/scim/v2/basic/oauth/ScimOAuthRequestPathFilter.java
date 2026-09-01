/*
 * Copyright 2026 Datastrato Pvt Ltd.
 */

package com.datastrato.gravitino.scim.v2.basic.oauth;

import java.io.IOException;
import java.util.Optional;
import javax.annotation.Nullable;
import javax.servlet.Filter;
import javax.servlet.FilterChain;
import javax.servlet.FilterConfig;
import javax.servlet.ServletException;
import javax.servlet.ServletRequest;
import javax.servlet.ServletResponse;
import javax.servlet.http.HttpServletRequest;
import org.apache.commons.lang3.StringUtils;

/**
 * Captures the request path before OAuth servlet authentication runs and parses metalake scope from
 * it.
 *
 * <p>Registered via {@code gravitino.server.webserver.customFilters}. The captured path is read by
 * {@link ScimOAuthPrincipalMapper#map(String)} on the same request thread.
 */
public class ScimOAuthRequestPathFilter implements Filter {

  /** Fully qualified class name for {@code gravitino.server.webserver.customFilters}. */
  public static final String FILTER_CLASS_NAME = ScimOAuthRequestPathFilter.class.getName();

  private static final String METALAKES_PREFIX = "/api/metalakes/";

  private static final ThreadLocal<String> CURRENT_REQUEST_PATH = new ThreadLocal<>();

  @Override
  public void init(FilterConfig filterConfig) {}

  @Override
  public void doFilter(ServletRequest request, ServletResponse response, FilterChain chain)
      throws IOException, ServletException {
    if (!(request instanceof HttpServletRequest httpRequest)) {
      chain.doFilter(request, response);
      return;
    }
    try {
      bind(httpRequest.getRequestURI());
      chain.doFilter(request, response);
    } finally {
      clear();
    }
  }

  @Override
  public void destroy() {}

  static void bind(String requestPath) {
    CURRENT_REQUEST_PATH.set(requestPath);
  }

  static Optional<String> currentRequestPath() {
    return Optional.ofNullable(CURRENT_REQUEST_PATH.get());
  }

  static Optional<String> currentMetalakeName() {
    return currentRequestPath().flatMap(ScimOAuthRequestPathFilter::parseMetalakeName);
  }

  static void clear() {
    CURRENT_REQUEST_PATH.remove();
  }

  /**
   * Extracts the metalake name from a request path such as {@code /api/metalakes/ml1/catalogs}.
   *
   * @param requestPath servlet or JAX-RS request path
   * @return metalake name when the path is metalake-scoped, otherwise {@link Optional#empty()}
   */
  static Optional<String> parseMetalakeName(@Nullable String requestPath) {
    if (StringUtils.isBlank(requestPath)) {
      return Optional.empty();
    }
    int prefixIndex = requestPath.indexOf(METALAKES_PREFIX);
    if (prefixIndex < 0) {
      return Optional.empty();
    }
    String metalakeSegment = requestPath.substring(prefixIndex + METALAKES_PREFIX.length());
    if (StringUtils.isEmpty(metalakeSegment)) {
      return Optional.empty();
    }
    int slashIndex = metalakeSegment.indexOf('/');
    return Optional.of(slashIndex < 0 ? metalakeSegment : metalakeSegment.substring(0, slashIndex));
  }
}
