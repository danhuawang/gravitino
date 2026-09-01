/*
 * Copyright 2026 Datastrato Pvt Ltd.
 */

package com.datastrato.gravitino.scim.v2.service.web;

import jakarta.servlet.RequestDispatcher;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServlet;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;

/**
 * Forwards root-level health probe paths to canonical SCIM health endpoints.
 *
 * <p>Pass {@code "/scim"} as the target prefix so {@code /health/live} forwards to {@code
 * /scim/health/live}.
 */
public final class ScimHealthAliasServlet extends HttpServlet {

  private final String targetPrefix;

  /**
   * Forwards to {@code <targetPrefix>/health*}.
   *
   * @param targetPrefix the path prefix of the canonical health endpoint, e.g. {@code "/scim"}
   */
  public ScimHealthAliasServlet(String targetPrefix) {
    this.targetPrefix = targetPrefix;
  }

  @Override
  protected void doGet(HttpServletRequest req, HttpServletResponse resp)
      throws ServletException, IOException {
    String uri = req.getRequestURI();
    String targetPath = "/health.html".equals(uri) ? targetPrefix + "/health" : targetPrefix + uri;
    RequestDispatcher dispatcher = req.getRequestDispatcher(targetPath);
    if (dispatcher == null) {
      resp.sendError(HttpServletResponse.SC_SERVICE_UNAVAILABLE, "health dispatcher unavailable");
      return;
    }
    dispatcher.forward(req, resp);
  }
}
