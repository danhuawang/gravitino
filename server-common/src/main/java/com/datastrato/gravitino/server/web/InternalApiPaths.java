/*
 * Copyright 2026 Datastrato Inc.
 */
package com.datastrato.gravitino.server.web;

/**
 * Path and header constants for Enterprise internal REST APIs.
 *
 * <p>Internal APIs serve the Enterprise Web UI and bundled tooling. They are served under {@code
 * /api/internal}, are not part of the public REST API, and carry no backward-compatibility
 * commitment.
 */
public final class InternalApiPaths {

  /** Path prefix of internal REST resources, relative to the {@code /api} servlet mount. */
  public static final String PREFIX = "/internal";

  /**
   * Header that callers of internal APIs must send.
   *
   * <p>The header is not a secret. It prevents clients other than the Web UI and bundled tooling
   * from calling internal APIs by accident; a client that sends it knowingly bypasses that guard.
   */
  public static final String ORIGIN_HEADER = "X-Gravitino-Internal-Origin";

  private InternalApiPaths() {}
}
