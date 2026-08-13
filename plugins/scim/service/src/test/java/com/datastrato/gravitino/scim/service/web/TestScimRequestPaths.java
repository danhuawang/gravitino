/*
 * Copyright 2026 Datastrato Pvt Ltd.
 * This software is licensed under the Apache License version 2.
 */

package com.datastrato.gravitino.scim.service.web;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import jakarta.servlet.http.HttpServletRequest;
import java.util.Optional;
import org.junit.jupiter.api.Test;

class TestScimRequestPaths {

  @Test
  void testMetalakeFromPath() {
    assertEquals(
        Optional.of("metalake_a"),
        ScimRequestPaths.metalakeFromPath("/scim/v2/metalakes/metalake_a/Users"));
    assertEquals(
        Optional.of("metalake_a"),
        ScimRequestPaths.metalakeFromPath("/scim/v2/metalakes/metalake_a/ServiceProviderConfig"));
    assertEquals(Optional.empty(), ScimRequestPaths.metalakeFromPath("/scim/v2/Users"));
    assertEquals(Optional.empty(), ScimRequestPaths.metalakeFromPath("/health"));
  }

  @Test
  void testIsMetalakeScopedPath() {
    assertTrue(ScimRequestPaths.isMetalakeScopedPath("/scim/v2/metalakes/ml1/Users"));
    assertFalse(ScimRequestPaths.isMetalakeScopedPath("/scim/ServiceProviderConfig"));
  }

  @Test
  void testResolveRequestPathCombinesServletPathAndPathInfo() {
    HttpServletRequest request = mock(HttpServletRequest.class);
    when(request.getServletPath()).thenReturn("/scim");
    when(request.getPathInfo()).thenReturn("/v2/metalakes/ml1/Users");
    assertEquals("/scim/v2/metalakes/ml1/Users", ScimRequestPaths.resolveRequestPath(request));
  }

  @Test
  void testResolveRequestPathWithEmptyPathInfo() {
    HttpServletRequest request = mock(HttpServletRequest.class);
    when(request.getServletPath()).thenReturn("/scim");
    when(request.getPathInfo()).thenReturn(null);
    assertEquals("/scim", ScimRequestPaths.resolveRequestPath(request));
  }

  @Test
  void testResolveRequestPathStripsTrailingSlashFromServletPath() {
    HttpServletRequest request = mock(HttpServletRequest.class);
    when(request.getServletPath()).thenReturn("/scim/");
    when(request.getPathInfo()).thenReturn("/v2/metalakes/ml1/Users");
    assertEquals("/scim/v2/metalakes/ml1/Users", ScimRequestPaths.resolveRequestPath(request));
  }

  @Test
  void testRequestBaseUriUsesSchemeAndAuthority() {
    HttpServletRequest request = mock(HttpServletRequest.class);
    when(request.getRequestURL())
        .thenReturn(new StringBuffer("http://localhost:9201/scim/v2/metalakes/ml1/Users/1"));
    assertEquals("http://localhost:9201", ScimRequestPaths.requestBaseUri(request));
  }
}
