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
import java.util.Optional;
import org.apache.commons.lang3.StringUtils;
import org.apache.gravitino.auth.AuthConstants;
import org.apache.gravitino.listener.EventBus;
import org.apache.gravitino.listener.api.event.EventSource;
import org.apache.gravitino.listener.api.event.server.HttpRequestFailureEvent;
import org.apache.gravitino.utils.RequestContext;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Jakarta Servlet filter that dispatches {@link HttpRequestFailureEvent} for SCIM HTTP 4xx/5xx
 * responses through the shared {@link EventBus}, matching the main server / Iceberg REST audit path
 * ({@code EventBus} → audit listener → {@code gravitino.audit} / {@code gravitino_audit.log}).
 *
 * <p>Successful SCIM User operations are audited by operation-layer events emitted from {@code
 * ScimUserEventDispatcher} when SCIMple invokes repository methods. This filter only covers
 * HTTP-layer failures (auth rejection, malformed requests, unexpected 5xx) that never reach an
 * operation dispatcher.
 *
 * <p>Cannot reuse {@code org.apache.gravitino.server.web.HttpAuditFilter} directly because the SCIM
 * auxiliary listener runs on Jetty 11 / Jakarta Servlet, while that filter is compiled against
 * {@code javax.servlet}.
 */
public final class ScimHttpAuditFilter implements Filter {

  private static final Logger LOG = LoggerFactory.getLogger(ScimHttpAuditFilter.class);
  private static final String X_FORWARDED_FOR = "X-Forwarded-For";

  private final Optional<EventBus> eventBus;
  private final EventSource eventSource;
  private final ScimHealthCheckPathMatcher healthCheckMatcher;

  /**
   * Constructs a SCIM HTTP audit filter.
   *
   * @param eventBus event bus used to dispatch {@link HttpRequestFailureEvent}s; may be {@code
   *     null}, in which case the filter is a pass-through
   * @param eventSource identifies which server produced the event
   * @param healthCheckMatcher determines which paths are health probes and should be skipped
   */
  public ScimHttpAuditFilter(
      EventBus eventBus, EventSource eventSource, ScimHealthCheckPathMatcher healthCheckMatcher) {
    this.eventBus = Optional.ofNullable(eventBus);
    this.eventSource = eventSource;
    this.healthCheckMatcher = healthCheckMatcher;
  }

  @Override
  public void init(FilterConfig filterConfig) {}

  @Override
  public void doFilter(ServletRequest request, ServletResponse response, FilterChain chain)
      throws IOException, ServletException {
    if (eventBus.isEmpty() || !(request instanceof HttpServletRequest httpRequest)) {
      chain.doFilter(request, response);
      return;
    }

    if (httpRequest.getDispatcherType() == DispatcherType.ERROR) {
      chain.doFilter(request, response);
      return;
    }

    RequestContext.resetOperationFailureFired();
    if (healthCheckMatcher.isHealthCheckPath(httpRequest.getRequestURI())) {
      try {
        chain.doFilter(request, response);
      } finally {
        RequestContext.resetOperationFailureFired();
      }
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
      try {
        if (!RequestContext.isOperationFailureFired()) {
          int status = wrappedResponse.getCapturedStatus();
          if (status >= 400) {
            HttpRequestFailureEvent event =
                new HttpRequestFailureEvent(
                    resolveUser(httpRequest),
                    resolveClientAddress(httpRequest),
                    httpRequest.getMethod(),
                    httpRequest.getRequestURI(),
                    status,
                    eventSource);
            eventBus.get().dispatchEvent(event);
          }
        }
      } catch (Exception e) {
        LOG.error(
            "Failed to dispatch SCIM HTTP audit event for {} {}",
            httpRequest.getMethod(),
            httpRequest.getRequestURI(),
            e);
      } finally {
        RequestContext.resetOperationFailureFired();
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
    String contextAddress = RequestContext.getRemoteAddress();
    if (contextAddress != null) {
      return contextAddress;
    }
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
