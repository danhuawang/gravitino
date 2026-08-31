/*
 * Copyright 2026 Datastrato Inc.
 */

package com.datastrato.gravitino.scim.service;

import com.datastrato.gravitino.scim.service.web.ScimHttpResponses;
import com.datastrato.gravitino.scim.service.web.ScimMetalakeContext;
import com.datastrato.gravitino.scim.service.web.ScimRequestPaths;
import jakarta.servlet.Filter;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.ServletRequest;
import jakarta.servlet.ServletResponse;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import org.apache.gravitino.exceptions.NotFoundException;
import org.apache.gravitino.storage.relational.service.MetalakeMetaService;

/**
 * Resolves URL {@code {metalake}} to request-scoped context for SCIMple repository adapters on port
 * 9201.
 */
public class ScimURLScopeResolver implements Filter {

  @Override
  public void doFilter(ServletRequest request, ServletResponse response, FilterChain chain)
      throws IOException, ServletException {
    HttpServletRequest httpRequest = (HttpServletRequest) request;
    HttpServletResponse httpResponse = (HttpServletResponse) response;
    String path = ScimRequestPaths.resolveRequestPath(httpRequest);

    if (!ScimRequestPaths.isMetalakeScopedPath(path)) {
      chain.doFilter(request, response);
      return;
    }

    String metalakeName =
        ScimRequestPaths.metalakeFromPath(path)
            .orElseThrow(
                () ->
                    new ServletException(
                        "Invalid SCIM metalake path (expected /scim/v2/metalakes/{metalake}/...)"));

    try {
      MetalakeMetaService.getInstance().getMetalakeIdByName(metalakeName);
      ScimMetalakeContext.setMetalake(metalakeName);
      ScimMetalakeContext.setRequestBaseUri(ScimRequestPaths.requestBaseUri(httpRequest));
      chain.doFilter(request, response);
    } catch (NotFoundException e) {
      ScimHttpResponses.writeError(httpResponse, HttpServletResponse.SC_NOT_FOUND, e.getMessage());
    } finally {
      ScimMetalakeContext.clear();
    }
  }
}
