/*
 * Copyright 2026 Datastrato Pvt Ltd.
 */

package com.datastrato.gravitino.scim.service;

import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.datastrato.gravitino.scim.service.web.ScimMetalakeContext;
import jakarta.servlet.FilterChain;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.PrintWriter;
import java.io.StringWriter;
import java.util.Optional;
import org.apache.gravitino.exceptions.NotFoundException;
import org.apache.gravitino.storage.relational.service.MetalakeMetaService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.MockedStatic;
import org.mockito.Mockito;

class TestScimURLScopeResolver {

  private ScimURLScopeResolver resolver;
  private HttpServletRequest request;
  private HttpServletResponse response;
  private FilterChain chain;
  private MockedStatic<MetalakeMetaService> metalakeMetaService;

  @BeforeEach
  void setUp() throws Exception {
    resolver = new ScimURLScopeResolver();
    request = mock(HttpServletRequest.class);
    response = mock(HttpServletResponse.class);
    chain = mock(FilterChain.class);
    when(response.getWriter()).thenReturn(new PrintWriter(new StringWriter()));

    MetalakeMetaService metaService = mock(MetalakeMetaService.class);
    metalakeMetaService = Mockito.mockStatic(MetalakeMetaService.class);
    metalakeMetaService.when(MetalakeMetaService::getInstance).thenReturn(metaService);
    when(metaService.getMetalakeIdByName("ml1")).thenReturn(42L);
  }

  @AfterEach
  void tearDown() {
    metalakeMetaService.close();
    ScimMetalakeContext.clear();
  }

  @Test
  void testSetsAndClearsMetalakeContext() throws Exception {
    when(request.getServletPath()).thenReturn("/scim/v2/metalakes/ml1/Users");
    when(request.getRequestURL())
        .thenReturn(new StringBuffer("http://localhost:9201/scim/v2/metalakes/ml1/Users"));

    resolver.doFilter(request, response, chain);

    verify(chain).doFilter(request, response);
    assertThrows(IllegalStateException.class, ScimMetalakeContext::getMetalake);
    org.junit.jupiter.api.Assertions.assertTrue(
        ScimMetalakeContext.currentRequestBaseUri().isEmpty());
  }

  @Test
  void testMissingMetalakeReturns404() throws Exception {
    when(request.getServletPath()).thenReturn("/scim/v2/metalakes/ml1/Users");
    when(request.getRequestURL())
        .thenReturn(new StringBuffer("http://localhost:9201/scim/v2/metalakes/ml1/Users"));
    MetalakeMetaService metaService = MetalakeMetaService.getInstance();
    doThrow(new NotFoundException("Metalake not found"))
        .when(metaService)
        .getMetalakeIdByName("ml1");

    resolver.doFilter(request, response, chain);

    verify(response).setStatus(HttpServletResponse.SC_NOT_FOUND);
    verify(chain, never()).doFilter(request, response);
  }

  @Test
  void testContextAvailableDuringChain() throws Exception {
    when(request.getServletPath()).thenReturn("/scim/v2/metalakes/ml1/Groups");
    when(request.getRequestURL())
        .thenReturn(new StringBuffer("http://localhost:9201/scim/v2/metalakes/ml1/Groups"));

    resolver.doFilter(
        request,
        response,
        (req, resp) -> {
          org.junit.jupiter.api.Assertions.assertEquals("ml1", ScimMetalakeContext.getMetalake());
          org.junit.jupiter.api.Assertions.assertEquals(
              Optional.of("http://localhost:9201"), ScimMetalakeContext.currentRequestBaseUri());
        });
  }
}
