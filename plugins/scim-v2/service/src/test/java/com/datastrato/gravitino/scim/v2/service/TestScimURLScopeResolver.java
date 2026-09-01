/*
 * Copyright 2026 Datastrato Inc.
 */

package com.datastrato.gravitino.scim.v2.service;

import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.datastrato.gravitino.scim.v2.service.web.ScimRequestContext;
import jakarta.servlet.FilterChain;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.util.Optional;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class TestScimURLScopeResolver {

  private ScimURLScopeResolver resolver;
  private HttpServletRequest request;
  private HttpServletResponse response;
  private FilterChain chain;

  @BeforeEach
  void setUp() {
    resolver = new ScimURLScopeResolver();
    request = mock(HttpServletRequest.class);
    response = mock(HttpServletResponse.class);
    chain = mock(FilterChain.class);
  }

  @AfterEach
  void tearDown() {
    ScimRequestContext.clear();
  }

  @Test
  void testBindsAndClearsRequestContext() throws Exception {
    when(request.getServletPath()).thenReturn("/scim");
    when(request.getPathInfo()).thenReturn("/v2/Users");
    when(request.getRequestURL())
        .thenReturn(new StringBuffer("http://localhost:9201/scim/v2/Users"));

    resolver.doFilter(request, response, chain);

    verify(chain).doFilter(request, response);
    assertTrue(ScimRequestContext.currentRequestBaseUri().isEmpty());
  }

  @Test
  void testContextAvailableDuringChain() throws Exception {
    when(request.getServletPath()).thenReturn("/scim");
    when(request.getPathInfo()).thenReturn("/v2/Groups");
    when(request.getRequestURL())
        .thenReturn(new StringBuffer("http://localhost:9201/scim/v2/Groups"));

    resolver.doFilter(
        request,
        response,
        (req, resp) ->
            org.junit.jupiter.api.Assertions.assertEquals(
                Optional.of("http://localhost:9201"), ScimRequestContext.currentRequestBaseUri()));
  }

  @Test
  void testNonScimPathPassesThroughWithoutContext() throws Exception {
    when(request.getServletPath()).thenReturn("/health");
    when(request.getPathInfo()).thenReturn(null);

    resolver.doFilter(request, response, chain);

    verify(chain).doFilter(request, response);
    assertTrue(ScimRequestContext.currentRequestBaseUri().isEmpty());
  }
}
