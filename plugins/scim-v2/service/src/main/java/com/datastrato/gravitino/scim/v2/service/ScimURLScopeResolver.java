/*
 * Copyright 2026 Datastrato Inc.
 */

package com.datastrato.gravitino.scim.v2.service;

import com.datastrato.gravitino.scim.v2.service.web.ScimRequestContext;
import com.datastrato.gravitino.scim.v2.service.web.ScimRequestPaths;
import jakarta.servlet.Filter;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.ServletRequest;
import jakarta.servlet.ServletResponse;
import jakarta.servlet.http.HttpServletRequest;
import java.io.IOException;

/** Binds request-scoped SCIM v2 context for repository adapters on port 9201. */
public class ScimURLScopeResolver implements Filter {

  @Override
  public void doFilter(ServletRequest request, ServletResponse response, FilterChain chain)
      throws IOException, ServletException {
    HttpServletRequest httpRequest = (HttpServletRequest) request;
    String path = ScimRequestPaths.resolveRequestPath(httpRequest);

    if (!ScimRequestPaths.isScimV2Path(path)) {
      chain.doFilter(request, response);
      return;
    }

    try {
      ScimRequestContext.bindRequestBaseUri(ScimRequestPaths.requestBaseUri(httpRequest));
      chain.doFilter(request, response);
    } finally {
      ScimRequestContext.clear();
    }
  }
}
