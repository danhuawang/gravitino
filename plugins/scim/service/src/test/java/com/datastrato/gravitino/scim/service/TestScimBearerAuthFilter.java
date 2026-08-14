/*
 * Copyright 2026 Datastrato Pvt Ltd.
 * This software is licensed under the Apache License version 2.
 */

package com.datastrato.gravitino.scim.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.datastrato.gravitino.scim.ScimTokenManager;
import com.datastrato.gravitino.scim.model.ScimToken;
import jakarta.servlet.FilterChain;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.PrintWriter;
import java.io.StringWriter;
import java.util.concurrent.atomic.AtomicReference;
import org.apache.gravitino.UserPrincipal;
import org.apache.gravitino.auth.AuthConstants;
import org.apache.gravitino.exceptions.NotFoundException;
import org.apache.gravitino.exceptions.TokenExpiredException;
import org.apache.gravitino.exceptions.UnauthorizedException;
import org.apache.gravitino.utils.PrincipalUtils;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

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
  void testBypassNonMetalake() throws Exception {
    when(request.getServletPath()).thenReturn("/health");

    filter.doFilter(request, response, chain);

    verify(chain).doFilter(request, response);
    verify(tokenManager, never()).authenticateBearerToken(anyString(), anyString());
  }

  @Test
  void testMissingAuth401() throws Exception {
    when(request.getServletPath()).thenReturn("/scim/v2/metalakes/ml1/Users");
    when(request.getHeader(AuthConstants.HTTP_HEADER_AUTHORIZATION)).thenReturn(null);

    filter.doFilter(request, response, chain);

    verify(response).setStatus(HttpServletResponse.SC_UNAUTHORIZED);
    verify(chain, never()).doFilter(request, response);
  }

  @Test
  void testValidToken() throws Exception {
    when(request.getServletPath()).thenReturn("/scim/v2/metalakes/ml1/Users");
    when(request.getHeader(AuthConstants.HTTP_HEADER_AUTHORIZATION))
        .thenReturn("Bearer gravitino_scim_test");
    when(tokenManager.authenticateBearerToken("gravitino_scim_test", "ml1"))
        .thenReturn(token("entra-prod"));

    filter.doFilter(request, response, chain);

    verify(chain).doFilter(request, response);
    ArgumentCaptor<Object> principalCaptor = ArgumentCaptor.forClass(Object.class);
    verify(request)
        .setAttribute(
            eq(AuthConstants.AUTHENTICATED_PRINCIPAL_ATTRIBUTE_NAME), principalCaptor.capture());
    assertEquals("entra-prod", ((UserPrincipal) principalCaptor.getValue()).getName());
  }

  @Test
  void testDoAsActor() throws Exception {
    when(request.getServletPath()).thenReturn("/scim/v2/metalakes/ml1/Users");
    when(request.getHeader(AuthConstants.HTTP_HEADER_AUTHORIZATION))
        .thenReturn("Bearer gravitino_scim_test");
    when(tokenManager.authenticateBearerToken("gravitino_scim_test", "ml1"))
        .thenReturn(token("entra-prod"));

    AtomicReference<String> actorInChain = new AtomicReference<>();
    FilterChain observingChain =
        (req, resp) -> actorInChain.set(PrincipalUtils.getCurrentUserName());

    filter.doFilter(request, response, observingChain);

    assertEquals("entra-prod", actorInChain.get());
  }

  @Test
  void testExpired419() throws Exception {
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
  void testMissingMetalake404() throws Exception {
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
  void testInvalidToken401() throws Exception {
    when(request.getServletPath()).thenReturn("/scim/v2/metalakes/ml1/Users");
    when(request.getHeader(AuthConstants.HTTP_HEADER_AUTHORIZATION)).thenReturn("Bearer bad");
    doThrow(new UnauthorizedException("Invalid SCIM bearer token"))
        .when(tokenManager)
        .authenticateBearerToken("bad", "ml1");

    filter.doFilter(request, response, chain);

    verify(response).setStatus(HttpServletResponse.SC_UNAUTHORIZED);
  }

  private static ScimToken token(String name) {
    return ScimToken.builder()
        .withTokenId(1L)
        .withMetalakeId(10L)
        .withTokenName(name)
        .withExpiresAt(0L)
        .withAuditInfo(null)
        .build();
  }
}
