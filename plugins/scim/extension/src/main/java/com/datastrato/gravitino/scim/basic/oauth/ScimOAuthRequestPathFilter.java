/*
 * Copyright 2026 Datastrato Inc.
 */

package com.datastrato.gravitino.scim.basic.oauth;

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
 * Captures OAuth request context before servlet authentication runs.
 *
 * <p>The bearer token is exposed only on the request thread. {@link ScimOAuthPrincipalMapper} reads
 * its claims after the configured OAuth validator has validated that same token.
 */
public class ScimOAuthRequestPathFilter implements Filter {

  /** Fully qualified class name for {@code gravitino.server.webserver.customFilters}. */
  public static final String FILTER_CLASS_NAME = ScimOAuthRequestPathFilter.class.getName();

  private static final String METALAKES_PREFIX = "/api/metalakes/";
  private static final String BEARER_PREFIX = "Bearer ";

  private static final ThreadLocal<String> CURRENT_REQUEST_PATH = new ThreadLocal<>();
  private static final ThreadLocal<String> CURRENT_BEARER_TOKEN = new ThreadLocal<>();

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
      bind(httpRequest.getRequestURI(), httpRequest.getHeader("Authorization"));
      chain.doFilter(request, response);
    } finally {
      clear();
    }
  }

  @Override
  public void destroy() {}

  static void bind(String requestPath) {
    bind(requestPath, null);
  }

  static void bind(String requestPath, @Nullable String authorizationHeader) {
    CURRENT_REQUEST_PATH.set(requestPath);
    String token = bearerToken(authorizationHeader);
    if (token != null) {
      CURRENT_BEARER_TOKEN.set(token);
    } else {
      CURRENT_BEARER_TOKEN.remove();
    }
  }

  static Optional<String> currentRequestPath() {
    return Optional.ofNullable(CURRENT_REQUEST_PATH.get());
  }

  static Optional<String> currentBearerToken() {
    return Optional.ofNullable(CURRENT_BEARER_TOKEN.get());
  }

  static Optional<String> currentMetalakeName() {
    return currentRequestPath().flatMap(ScimOAuthRequestPathFilter::parseMetalakeName);
  }

  static void clear() {
    CURRENT_REQUEST_PATH.remove();
    CURRENT_BEARER_TOKEN.remove();
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

  @Nullable
  private static String bearerToken(@Nullable String authorizationHeader) {
    if (StringUtils.isBlank(authorizationHeader)
        || !authorizationHeader.regionMatches(true, 0, BEARER_PREFIX, 0, BEARER_PREFIX.length())) {
      return null;
    }
    return StringUtils.trimToNull(authorizationHeader.substring(BEARER_PREFIX.length()));
  }
}
