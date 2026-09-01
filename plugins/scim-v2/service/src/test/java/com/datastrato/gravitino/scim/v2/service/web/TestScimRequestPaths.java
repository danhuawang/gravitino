/*
 * Copyright 2026 Datastrato Inc.
 */

package com.datastrato.gravitino.scim.v2.service.web;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import jakarta.servlet.http.HttpServletRequest;
import org.junit.jupiter.api.Test;

class TestScimRequestPaths {

  @Test
  void testIsScimV2Path() {
    assertTrue(ScimRequestPaths.isScimV2Path("/scim/v2/Users"));
    assertTrue(ScimRequestPaths.isScimV2Path("/scim/v2/ServiceProviderConfig"));
    assertFalse(ScimRequestPaths.isScimV2Path("/health"));
  }

  @Test
  void testIsScimV2ResourcePath() {
    assertTrue(ScimRequestPaths.isScimV2ResourcePath("/scim/v2/Users"));
    assertTrue(ScimRequestPaths.isScimV2ResourcePath("/scim/v2/Groups/1"));
    assertFalse(ScimRequestPaths.isScimV2ResourcePath("/scim/v2/ServiceProviderConfig"));
    assertFalse(ScimRequestPaths.isScimV2ResourcePath("/scim/ServiceProviderConfig"));
  }

  @Test
  void testResolveRequestPathCombinesServletPathAndPathInfo() {
    HttpServletRequest request = mock(HttpServletRequest.class);
    when(request.getServletPath()).thenReturn("/scim");
    when(request.getPathInfo()).thenReturn("/v2/Users");
    assertEquals("/scim/v2/Users", ScimRequestPaths.resolveRequestPath(request));
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
    when(request.getPathInfo()).thenReturn("/v2/Users");
    assertEquals("/scim/v2/Users", ScimRequestPaths.resolveRequestPath(request));
  }

  @Test
  void testRequestBaseUriUsesSchemeAndAuthority() {
    HttpServletRequest request = mock(HttpServletRequest.class);
    when(request.getRequestURL())
        .thenReturn(new StringBuffer("http://localhost:9201/scim/v2/Users/1"));
    assertEquals("http://localhost:9201", ScimRequestPaths.requestBaseUri(request));
  }
}
