/*
 * Copyright 2026 Datastrato Pvt Ltd.
 * This software is licensed under the Apache License version 2.
 */

package com.datastrato.gravitino.scim.service.web;

import com.datastrato.gravitino.scim.service.ScimHealthCheckPathMatcher;
import jakarta.servlet.DispatcherType;
import jakarta.servlet.Filter;
import jakarta.servlet.FilterChain;
import jakarta.servlet.FilterConfig;
import jakarta.servlet.ServletException;
import jakarta.servlet.ServletRequest;
import jakarta.servlet.ServletResponse;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.servlet.http.HttpServletResponseWrapper;
import java.io.IOException;
import java.security.Principal;
import org.apache.commons.lang3.StringUtils;
import org.apache.gravitino.auth.AuthConstants;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Jakarta Servlet filter that logs 4xx/5xx responses on the SCIM auxiliary listener.
 *
 * <p>Uses structured SLF4J logging because a dedicated {@code EventSource} for SCIM lives outside
 * this module.
 */
public final class ScimHttpAuditFilter implements Filter {

  private static final Logger LOG = LoggerFactory.getLogger(ScimHttpAuditFilter.class);
  private static final String X_FORWARDED_FOR = "X-Forwarded-For";

  private final ScimHealthCheckPathMatcher healthCheckMatcher;

  /**
   * Constructs a SCIM HTTP audit filter.
   *
   * @param healthCheckMatcher determines which paths are health probes and should be skipped
   */
  public ScimHttpAuditFilter(ScimHealthCheckPathMatcher healthCheckMatcher) {
    this.healthCheckMatcher = healthCheckMatcher;
  }

  @Override
  public void init(FilterConfig filterConfig) {}

  @Override
  public void doFilter(ServletRequest request, ServletResponse response, FilterChain chain)
      throws IOException, ServletException {
    if (!(request instanceof HttpServletRequest httpRequest)) {
      chain.doFilter(request, response);
      return;
    }

    if (httpRequest.getDispatcherType() == DispatcherType.ERROR) {
      chain.doFilter(request, response);
      return;
    }

    if (healthCheckMatcher.isHealthCheckPath(httpRequest.getRequestURI())) {
      chain.doFilter(request, response);
      return;
    }

    StatusCapturingResponseWrapper wrappedResponse =
        new StatusCapturingResponseWrapper((HttpServletResponse) response);
    Throwable chainException = null;
    try {
      chain.doFilter(httpRequest, wrappedResponse);
    } catch (Throwable t) {
      chainException = t;
      if (wrappedResponse.getCapturedStatus() < 400) {
        wrappedResponse.setStatus(HttpServletResponse.SC_INTERNAL_SERVER_ERROR);
      }
    } finally {
      int status = wrappedResponse.getCapturedStatus();
      if (status >= 400) {
        LOG.warn(
            "SCIM HTTP request failed: user={} client={} {} {} status={}",
            resolveUser(httpRequest),
            resolveClientAddress(httpRequest),
            httpRequest.getMethod(),
            httpRequest.getRequestURI(),
            status);
      }
    }

    if (chainException instanceof Error error) {
      throw error;
    } else if (chainException instanceof RuntimeException runtimeException) {
      throw runtimeException;
    } else if (chainException instanceof IOException ioException) {
      throw ioException;
    } else if (chainException instanceof ServletException servletException) {
      throw servletException;
    } else if (chainException != null) {
      throw new ServletException(chainException);
    }
  }

  @Override
  public void destroy() {}

  private static String resolveUser(HttpServletRequest request) {
    Object principalObj =
        request.getAttribute(AuthConstants.AUTHENTICATED_PRINCIPAL_ATTRIBUTE_NAME);
    if (principalObj instanceof Principal principal) {
      return principal.getName();
    }
    return "unknown";
  }

  private static String resolveClientAddress(HttpServletRequest request) {
    String xForwardedFor = request.getHeader(X_FORWARDED_FOR);
    if (StringUtils.isNotBlank(xForwardedFor)) {
      return xForwardedFor.split(",")[0].trim();
    }
    return request.getRemoteAddr();
  }

  static final class StatusCapturingResponseWrapper extends HttpServletResponseWrapper {

    private int capturedStatus = HttpServletResponse.SC_OK;

    StatusCapturingResponseWrapper(HttpServletResponse response) {
      super(response);
    }

    int getCapturedStatus() {
      return capturedStatus;
    }

    @Override
    public void setStatus(int sc) {
      capturedStatus = sc;
      super.setStatus(sc);
    }

    @Override
    public void sendError(int sc) throws IOException {
      capturedStatus = sc;
      super.sendError(sc);
    }

    @Override
    public void sendError(int sc, String msg) throws IOException {
      capturedStatus = sc;
      super.sendError(sc, msg);
    }

    @Override
    public void reset() {
      capturedStatus = HttpServletResponse.SC_OK;
      super.reset();
    }
  }
}
