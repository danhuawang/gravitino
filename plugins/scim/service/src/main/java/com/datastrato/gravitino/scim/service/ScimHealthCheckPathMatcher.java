/*
 * Copyright 2026 Datastrato Pvt Ltd.
 * This software is licensed under the Apache License version 2.
 */

package com.datastrato.gravitino.scim.service;

import org.apache.gravitino.server.web.HealthCheckPathMatcher;

/**
 * A {@link HealthCheckPathMatcher} for the SCIM REST server that additionally recognises {@code
 * /scim/health} and {@code /scim/health/*} as health check endpoints.
 *
 * <p>Pass an instance of this class to both {@link ScimBearerAuthFilter} and {@link
 * com.datastrato.gravitino.scim.service.web.ScimHttpAuditFilter} when constructing the SCIM REST
 * server so that both filters agree on which paths are probe traffic.
 */
public class ScimHealthCheckPathMatcher extends HealthCheckPathMatcher {

  @Override
  public boolean isHealthCheckPath(String path) {
    if (super.isHealthCheckPath(path)) {
      return true;
    }
    if (path == null) {
      return false;
    }
    return path.equals("/scim/health") || path.startsWith("/scim/health/");
  }
}
