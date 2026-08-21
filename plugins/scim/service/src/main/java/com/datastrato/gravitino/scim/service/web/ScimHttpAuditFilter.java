/*
 * Copyright 2026 Datastrato Pvt Ltd.
 * This software is licensed under the Apache License version 2.
 */

package com.datastrato.gravitino.scim.service.web;

import com.datastrato.gravitino.scim.ScimErrorHistoryManager;
import com.datastrato.gravitino.scim.service.ScimHealthCheckPathMatcher;
import jakarta.servlet.DispatcherType;
import jakarta.servlet.Filter;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.ServletRequest;
import jakarta.servlet.ServletResponse;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.security.Principal;
import java.util.Optional;
import javax.annotation.Nullable;
import org.apache.commons.lang3.StringUtils;
import org.apache.gravitino.auth.AuthConstants;
import org.apache.gravitino.listener.EventBus;
import org.apache.gravitino.listener.api.event.EventSource;
import org.apache.gravitino.listener.api.event.server.HttpRequestFailureEvent;
import org.apache.gravitino.utils.RequestContext;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Jakarta Servlet filter that records SCIM HTTP 4xx/5xx failures for audit and error history.
 *
 * <p>Dispatches {@link HttpRequestFailureEvent} through {@link EventBus} when present, matching the
 * main server audit path. Non-404 Users/Groups failures are also persisted to {@code
 * scim_error_history} when a manager is provided; that path does not depend on {@link EventBus}.
 *
 * <p>Cannot reuse {@code org.apache.gravitino.server.web.HttpAuditFilter} because the SCIM listener
 * runs on Jetty 11 / Jakarta Servlet.
 */
public final class ScimHttpAuditFilter implements Filter {

  private static final Logger LOG = LoggerFactory.getLogger(ScimHttpAuditFilter.class);
  private static final String X_FORWARDED_FOR = "X-Forwarded-For";

  private final Optional<EventBus> eventBus;
  private final EventSource eventSource;
  private final ScimHealthCheckPathMatcher healthCheckMatcher;
  @Nullable private final ScimErrorHistoryManager errorHistoryManager;

  /**
   * Constructs a SCIM HTTP audit filter without error-history persistence.
   *
   * @param eventBus event bus used to dispatch {@link HttpRequestFailureEvent}s; may be {@code
   *     null}, in which case the filter is a pass-through
   * @param eventSource identifies which server produced the event
   * @param healthCheckMatcher determines which paths are health probes and should be skipped
   */
  public ScimHttpAuditFilter(
      EventBus eventBus, EventSource eventSource, ScimHealthCheckPathMatcher healthCheckMatcher) {
    this(eventBus, eventSource, healthCheckMatcher, null);
  }

  /**
   * Constructs a SCIM HTTP audit filter that also records protocol failures.
   *
   * @param eventBus event bus used to dispatch {@link HttpRequestFailureEvent}s; may be {@code
   *     null}, in which case HTTP audit events are skipped
   * @param eventSource identifies which server produced the event
   * @param healthCheckMatcher determines which paths are health probes and should be skipped
   * @param errorHistoryManager records SCIM protocol failures; {@code null} disables persistence
   */
  public ScimHttpAuditFilter(
      EventBus eventBus,
      EventSource eventSource,
      ScimHealthCheckPathMatcher healthCheckMatcher,
      @Nullable ScimErrorHistoryManager errorHistoryManager) {
    this.eventBus = Optional.ofNullable(eventBus);
    this.eventSource = eventSource;
    this.healthCheckMatcher = healthCheckMatcher;
    this.errorHistoryManager = errorHistoryManager;
  }

  @Override
  public void doFilter(ServletRequest request, ServletResponse response, FilterChain chain)
      throws IOException, ServletException {
    if (!(request instanceof HttpServletRequest httpRequest)
        || httpRequest.getDispatcherType() == DispatcherType.ERROR
        || (eventBus.isEmpty() && errorHistoryManager == null)) {
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

    ScimCapturingResponse captured =
        new ScimCapturingResponse((HttpServletResponse) response, errorHistoryManager != null);
    Throwable chainException = null;
    try {
      RequestContext.setRemoteAddress(resolveClientAddress(httpRequest));
      chain.doFilter(httpRequest, captured);
    } catch (Throwable t) {
      chainException = t;
      if (captured.status() < 400) {
        captured.setStatus(HttpServletResponse.SC_INTERNAL_SERVER_ERROR);
      }
    } finally {
      try {
        emitFailures(httpRequest, captured, chainException);
      } catch (Exception e) {
        LOG.error(
            "Failed to dispatch SCIM HTTP audit event for {} {}",
            httpRequest.getMethod(),
            httpRequest.getRequestURI(),
            e);
      } finally {
        RequestContext.clear();
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

  private void emitFailures(
      HttpServletRequest request, ScimCapturingResponse captured, @Nullable Throwable failure) {
    ScimCapturingResponse.Failure snapshot = captured.failure(failure);
    String path = request.getRequestURI();
    if (!ScimRequestPaths.isMetalakeScopedPath(path)) {
      path = ScimRequestPaths.resolveRequestPath(request);
    }
    if (errorHistoryManager != null
        && ScimErrorHistoryManager.shouldRecord(snapshot.status(), path)) {
      errorHistoryManager.recordHttpFailure(
          ScimRequestPaths.metalakeFromPath(path).orElse(""),
          request.getMethod(),
          path,
          snapshot.status(),
          snapshot.scimType(),
          snapshot.detail(),
          resolveUser(request));
    }
    if (eventBus.isPresent()
        && !RequestContext.isOperationFailureFired()
        && snapshot.status() >= 400) {
      eventBus
          .get()
          .dispatchEvent(
              new HttpRequestFailureEvent(
                  resolveUser(request),
                  resolveClientAddress(request),
                  request.getMethod(),
                  request.getRequestURI(),
                  snapshot.status(),
                  eventSource));
    }
  }

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
}
