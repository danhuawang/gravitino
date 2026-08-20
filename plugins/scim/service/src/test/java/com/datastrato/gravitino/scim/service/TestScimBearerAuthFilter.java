/*
 * Copyright 2026 Datastrato Pvt Ltd.
 * This software is licensed under the Apache License version 2.
 */

package com.datastrato.gravitino.scim.service;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.anyLong;
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

  private static final String SCIM_PATH = "/scim/v2/metalakes/ml1/Users";

  private ScimTokenManager tokenManager;
  private ScimBearerAuthFilter filter;
  private HttpServletRequest request;
  private HttpServletResponse response;
  private FilterChain chain;

  @BeforeEach
  void setUp() throws Exception {
    tokenManager = mock(ScimTokenManager.class);
    filter = new ScimBearerAuthFilter(tokenManager);
    request = mock(HttpServletRequest.class);
    response = mock(HttpServletResponse.class);
    chain = mock(FilterChain.class);
    when(response.getWriter()).thenReturn(new PrintWriter(new StringWriter()));
  }

  @Test
  void testBypassHealth() throws Exception {
    when(request.getServletPath()).thenReturn("/health");
    filter.doFilter(request, response, chain);
    verify(chain).doFilter(request, response);
    verify(tokenManager, never()).authenticateBearerToken(anyString(), anyString());
    verify(tokenManager, never()).updateScimTokenLastUsedAt(anyLong());
  }

  @Test
  void testNoAuth401() throws Exception {
    when(request.getServletPath()).thenReturn(SCIM_PATH);
    filter.doFilter(request, response, chain);
    verify(response).setStatus(HttpServletResponse.SC_UNAUTHORIZED);
    verify(chain, never()).doFilter(request, response);
  }

  @Test
  void testAuthOk() throws Exception {
    stubAuth("gravitino_scim_test", token("entra-prod"));
    filter.doFilter(request, response, chain);
    verify(chain).doFilter(request, response);
    ArgumentCaptor<Object> principal = ArgumentCaptor.forClass(Object.class);
    verify(request)
        .setAttribute(
            eq(AuthConstants.AUTHENTICATED_PRINCIPAL_ATTRIBUTE_NAME), principal.capture());
    assertEquals("entra-prod", ((UserPrincipal) principal.getValue()).getName());
    verify(tokenManager).updateScimTokenLastUsedAt(1L);
  }

  @Test
  void testLastUsedOn4xx() throws Exception {
    stubAuth("gravitino_scim_test", token("entra-prod"));
    when(response.getStatus()).thenReturn(HttpServletResponse.SC_NOT_FOUND);
    filter.doFilter(request, response, chain);
    verify(tokenManager).updateScimTokenLastUsedAt(1L);
  }

  @Test
  void testLastUsedFailSafe() throws Exception {
    stubAuth("gravitino_scim_test", token("entra-prod"));
    doThrow(new RuntimeException("db")).when(tokenManager).updateScimTokenLastUsedAt(1L);
    assertDoesNotThrow(() -> filter.doFilter(request, response, chain));
    verify(chain).doFilter(request, response);
    verify(response, never()).setStatus(HttpServletResponse.SC_UNAUTHORIZED);
  }

  @Test
  void testDoAs() throws Exception {
    stubAuth("gravitino_scim_test", token("entra-prod"));
    AtomicReference<String> actor = new AtomicReference<>();
    filter.doFilter(
        request, response, (req, resp) -> actor.set(PrincipalUtils.getCurrentUserName()));
    assertEquals("entra-prod", actor.get());
  }

  @Test
  void testExpired419() throws Exception {
    stubAuth("gravitino_scim_expired", null);
    doThrow(new TokenExpiredException("expired"))
        .when(tokenManager)
        .authenticateBearerToken("gravitino_scim_expired", "ml1");
    filter.doFilter(request, response, chain);
    verify(response).setStatus(419);
    verify(tokenManager, never()).updateScimTokenLastUsedAt(anyLong());
  }

  @Test
  void testNoMetalake404() throws Exception {
    stubAuth("gravitino_scim_test", null);
    doThrow(new NotFoundException("missing"))
        .when(tokenManager)
        .authenticateBearerToken("gravitino_scim_test", "ml1");
    filter.doFilter(request, response, chain);
    verify(response).setStatus(HttpServletResponse.SC_NOT_FOUND);
    verify(tokenManager, never()).updateScimTokenLastUsedAt(anyLong());
  }

  @Test
  void testBadToken401() throws Exception {
    when(request.getServletPath()).thenReturn(SCIM_PATH);
    when(request.getHeader(AuthConstants.HTTP_HEADER_AUTHORIZATION)).thenReturn("Bearer bad");
    doThrow(new UnauthorizedException("bad"))
        .when(tokenManager)
        .authenticateBearerToken("bad", "ml1");
    filter.doFilter(request, response, chain);
    verify(response).setStatus(HttpServletResponse.SC_UNAUTHORIZED);
  }

  private void stubAuth(String rawToken, ScimToken token) throws Exception {
    when(request.getServletPath()).thenReturn(SCIM_PATH);
    when(request.getHeader(AuthConstants.HTTP_HEADER_AUTHORIZATION))
        .thenReturn("Bearer " + rawToken);
    if (token != null) {
      when(tokenManager.authenticateBearerToken(rawToken, "ml1")).thenReturn(token);
    }
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
