/*
 * Copyright 2026 Datastrato Pvt Ltd.
 * This software is licensed under the Apache License version 2.
 */

package com.datastrato.gravitino.scim.service.web;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.datastrato.gravitino.scim.service.ScimHealthCheckPathMatcher;
import jakarta.servlet.DispatcherType;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletRequest;
import jakarta.servlet.ServletResponse;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.security.Principal;
import org.apache.gravitino.auth.AuthConstants;
import org.apache.gravitino.listener.EventBus;
import org.apache.gravitino.listener.api.event.EventSource;
import org.apache.gravitino.listener.api.event.server.HttpRequestFailureEvent;
import org.apache.gravitino.utils.RequestContext;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

class TestScimHttpAuditFilter {

  @AfterEach
  void cleanup() {
    RequestContext.resetOperationFailureFired();
    RequestContext.clear();
  }

  @Test
  void testNullBus() throws Exception {
    ScimHttpAuditFilter filter =
        new ScimHttpAuditFilter(
            null, EventSource.GRAVITINO_SERVER, new ScimHealthCheckPathMatcher());
    HttpServletRequest req = mockRequest("GET", "/scim/v2/metalakes/m1/Users", null, "1.2.3.4");
    HttpServletResponse resp = mock(HttpServletResponse.class);
    FilterChain chain = mock(FilterChain.class);

    filter.doFilter(req, resp, chain);

    verify(chain).doFilter(req, resp);
  }

  @Test
  void testErrorDispatch() throws Exception {
    EventBus eventBus = mock(EventBus.class);
    ScimHttpAuditFilter filter =
        new ScimHttpAuditFilter(
            eventBus, EventSource.GRAVITINO_SERVER, new ScimHealthCheckPathMatcher());
    HttpServletRequest req = mockRequest("GET", "/scim/v2/metalakes/m1/Users", null, "1.2.3.4");
    when(req.getDispatcherType()).thenReturn(DispatcherType.ERROR);
    HttpServletResponse resp = mock(HttpServletResponse.class);
    FilterChain chain = mock(FilterChain.class);

    filter.doFilter(req, resp, chain);

    verify(eventBus, never()).dispatchEvent(any());
    verify(chain).doFilter(req, resp);
  }

  @Test
  void testNonHttp() throws Exception {
    EventBus eventBus = mock(EventBus.class);
    ScimHttpAuditFilter filter =
        new ScimHttpAuditFilter(
            eventBus, EventSource.GRAVITINO_SERVER, new ScimHealthCheckPathMatcher());
    ServletRequest req = mock(ServletRequest.class);
    ServletResponse resp = mock(ServletResponse.class);
    FilterChain chain = mock(FilterChain.class);

    filter.doFilter(req, resp, chain);

    verify(eventBus, never()).dispatchEvent(any());
    verify(chain).doFilter(req, resp);
  }

  @Test
  void testSkipHealth() throws Exception {
    EventBus eventBus = mock(EventBus.class);
    ScimHttpAuditFilter filter =
        new ScimHttpAuditFilter(
            eventBus, EventSource.GRAVITINO_SERVER, new ScimHealthCheckPathMatcher());
    HttpServletRequest req = mockRequest("GET", "/scim/health", null, "1.2.3.4");
    HttpServletResponse resp = mock(HttpServletResponse.class);
    FilterChain chain =
        (request, response) ->
            ((HttpServletResponse) response).setStatus(HttpServletResponse.SC_OK);

    filter.doFilter(req, resp, chain);

    verify(eventBus, never()).dispatchEvent(any());
  }

  @Test
  void testSkip2xx() throws Exception {
    EventBus eventBus = mock(EventBus.class);
    ScimHttpAuditFilter filter =
        new ScimHttpAuditFilter(
            eventBus, EventSource.GRAVITINO_SERVER, new ScimHealthCheckPathMatcher());
    HttpServletRequest req = mockRequest("POST", "/scim/v2/metalakes/m1/Users", "entra", "9.9.9.9");
    HttpServletResponse resp = mock(HttpServletResponse.class);
    FilterChain chain =
        (request, response) ->
            ((HttpServletResponse) response).setStatus(HttpServletResponse.SC_CREATED);

    filter.doFilter(req, resp, chain);

    verify(eventBus, never()).dispatchEvent(any());
  }

  @Test
  void testDispatch4xx() throws Exception {
    EventBus eventBus = mock(EventBus.class);
    ScimHttpAuditFilter filter =
        new ScimHttpAuditFilter(
            eventBus, EventSource.GRAVITINO_SERVER, new ScimHealthCheckPathMatcher());
    HttpServletRequest req = mockRequest("GET", "/scim/v2/metalakes/m1/Users", "entra", "9.9.9.9");
    HttpServletResponse resp = mock(HttpServletResponse.class);
    FilterChain chain =
        (request, response) ->
            ((HttpServletResponse) response).setStatus(HttpServletResponse.SC_UNAUTHORIZED);

    filter.doFilter(req, resp, chain);

    ArgumentCaptor<HttpRequestFailureEvent> captor =
        ArgumentCaptor.forClass(HttpRequestFailureEvent.class);
    verify(eventBus).dispatchEvent(captor.capture());
    HttpRequestFailureEvent event = captor.getValue();
    Assertions.assertEquals("entra", event.user());
    Assertions.assertEquals("9.9.9.9", event.remoteAddress());
    Assertions.assertEquals("GET", event.httpMethod());
    Assertions.assertEquals("/scim/v2/metalakes/m1/Users", event.requestUri());
    Assertions.assertEquals(401, event.statusCode());
    Assertions.assertEquals(EventSource.GRAVITINO_SERVER, event.eventSource());
  }

  @Test
  void testSkipAlreadyFired() throws Exception {
    EventBus eventBus = mock(EventBus.class);
    ScimHttpAuditFilter filter =
        new ScimHttpAuditFilter(
            eventBus, EventSource.GRAVITINO_SERVER, new ScimHealthCheckPathMatcher());
    HttpServletRequest req = mockRequest("POST", "/scim/v2/metalakes/m1/Users", "entra", "1.1.1.1");
    HttpServletResponse resp = mock(HttpServletResponse.class);
    FilterChain chain =
        (request, response) -> {
          RequestContext.markOperationFailureFired();
          ((HttpServletResponse) response).setStatus(HttpServletResponse.SC_BAD_REQUEST);
        };

    filter.doFilter(req, resp, chain);

    verify(eventBus, never()).dispatchEvent(any());
  }

  @Test
  void testEscapeTo500() throws Exception {
    EventBus eventBus = mock(EventBus.class);
    ScimHttpAuditFilter filter =
        new ScimHttpAuditFilter(
            eventBus, EventSource.GRAVITINO_SERVER, new ScimHealthCheckPathMatcher());
    HttpServletRequest req = mockRequest("GET", "/scim/v2/metalakes/m1/Users", null, "1.2.3.4");
    HttpServletResponse resp = mock(HttpServletResponse.class);
    FilterChain chain =
        (request, response) -> {
          throw new RuntimeException("boom");
        };

    Assertions.assertThrows(RuntimeException.class, () -> filter.doFilter(req, resp, chain));

    ArgumentCaptor<HttpRequestFailureEvent> captor =
        ArgumentCaptor.forClass(HttpRequestFailureEvent.class);
    verify(eventBus).dispatchEvent(captor.capture());
    Assertions.assertEquals(500, captor.getValue().statusCode());
    Assertions.assertEquals("unknown", captor.getValue().user());
  }

  private static HttpServletRequest mockRequest(
      String method, String uri, String principalName, String remoteAddr) {
    HttpServletRequest req = mock(HttpServletRequest.class);
    when(req.getMethod()).thenReturn(method);
    when(req.getRequestURI()).thenReturn(uri);
    when(req.getDispatcherType()).thenReturn(DispatcherType.REQUEST);
    when(req.getRemoteAddr()).thenReturn(remoteAddr);
    if (principalName != null) {
      Principal principal = () -> principalName;
      when(req.getAttribute(AuthConstants.AUTHENTICATED_PRINCIPAL_ATTRIBUTE_NAME))
          .thenReturn(principal);
    } else {
      when(req.getAttribute(AuthConstants.AUTHENTICATED_PRINCIPAL_ATTRIBUTE_NAME)).thenReturn(null);
    }
    return req;
  }
}
