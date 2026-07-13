/*
 * Copyright 2026 Datastrato Pvt Ltd.
 * This software is licensed under the Apache License version 2.
 */

package com.datastrato.gravitino.scim.service;

import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doNothing;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.datastrato.gravitino.scim.ScimTokenManager;
import jakarta.servlet.FilterChain;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.PrintWriter;
import java.io.StringWriter;
import org.apache.gravitino.auth.AuthConstants;
import org.apache.gravitino.exceptions.NotFoundException;
import org.apache.gravitino.exceptions.TokenExpiredException;
import org.apache.gravitino.exceptions.UnauthorizedException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class TestScimBearerAuthFilter {

  private ScimTokenManager tokenManager;
  private ScimBearerAuthFilter filter;
  private HttpServletRequest request;
  private HttpServletResponse response;
  private FilterChain chain;
  private StringWriter responseBody;

  @BeforeEach
  void setUp() throws Exception {
    tokenManager = mock(ScimTokenManager.class);
    filter = new ScimBearerAuthFilter(tokenManager);
    request = mock(HttpServletRequest.class);
    response = mock(HttpServletResponse.class);
    chain = mock(FilterChain.class);
    responseBody = new StringWriter();
    when(response.getWriter()).thenReturn(new PrintWriter(responseBody));
  }

  @Test
  void testNonMetalakePathPassesThrough() throws Exception {
    when(request.getServletPath()).thenReturn("/health");

    filter.doFilter(request, response, chain);

    verify(chain).doFilter(request, response);
    verify(tokenManager, never()).authenticateBearerToken(anyString(), anyString());
  }

  @Test
  void testMissingAuthorizationHeaderReturns401() throws Exception {
    when(request.getServletPath()).thenReturn("/scim/v2/metalakes/ml1/Users");
    when(request.getHeader(AuthConstants.HTTP_HEADER_AUTHORIZATION)).thenReturn(null);

    filter.doFilter(request, response, chain);

    verify(response).setStatus(HttpServletResponse.SC_UNAUTHORIZED);
    verify(chain, never()).doFilter(request, response);
  }

  @Test
  void testValidTokenContinuesChain() throws Exception {
    when(request.getServletPath()).thenReturn("/scim/v2/metalakes/ml1/Users");
    when(request.getHeader(AuthConstants.HTTP_HEADER_AUTHORIZATION))
        .thenReturn("Bearer gravitino_scim_test");
    doNothing().when(tokenManager).authenticateBearerToken("gravitino_scim_test", "ml1");

    filter.doFilter(request, response, chain);

    verify(chain).doFilter(request, response);
  }

  @Test
  void testExpiredTokenReturns419() throws Exception {
    when(request.getServletPath()).thenReturn("/scim/v2/metalakes/ml1/Users");
    when(request.getHeader(AuthConstants.HTTP_HEADER_AUTHORIZATION))
        .thenReturn("Bearer gravitino_scim_expired");
    doThrow(new TokenExpiredException("SCIM token has expired"))
        .when(tokenManager)
        .authenticateBearerToken("gravitino_scim_expired", "ml1");

    filter.doFilter(request, response, chain);

    verify(response).setStatus(419);
    verify(chain, never()).doFilter(request, response);
  }

  @Test
  void testMissingMetalakeReturns404() throws Exception {
    when(request.getServletPath()).thenReturn("/scim/v2/metalakes/ml1/Users");
    when(request.getHeader(AuthConstants.HTTP_HEADER_AUTHORIZATION))
        .thenReturn("Bearer gravitino_scim_test");
    doThrow(new NotFoundException("Metalake not found"))
        .when(tokenManager)
        .authenticateBearerToken("gravitino_scim_test", "ml1");

    filter.doFilter(request, response, chain);

    verify(response).setStatus(HttpServletResponse.SC_NOT_FOUND);
  }

  @Test
  void testInvalidTokenReturns401() throws Exception {
    when(request.getServletPath()).thenReturn("/scim/v2/metalakes/ml1/Users");
    when(request.getHeader(AuthConstants.HTTP_HEADER_AUTHORIZATION)).thenReturn("Bearer bad");
    doThrow(new UnauthorizedException("Invalid SCIM bearer token"))
        .when(tokenManager)
        .authenticateBearerToken("bad", "ml1");

    filter.doFilter(request, response, chain);

    verify(response).setStatus(HttpServletResponse.SC_UNAUTHORIZED);
  }
}
