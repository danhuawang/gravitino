/*
 * Copyright 2026 Datastrato Inc.
 */

package com.datastrato.gravitino.scim.service;

import org.apache.gravitino.server.web.HealthCheckPathMatcher;

/**
 * A {@link HealthCheckPathMatcher} for the SCIM REST server that additionally recognises {@code
 * /scim/health} and {@code /scim/health/*} as health check endpoints.
 *
 * <p>Pass an instance of this class to {@link
 * com.datastrato.gravitino.scim.service.web.ScimHttpAuditFilter} (and any auth filter that skips
 * probes) when constructing the SCIM REST server so filters agree on which paths are probe traffic.
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
