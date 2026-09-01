/*
 * Copyright 2026 Datastrato Pvt Ltd.
 */

package com.datastrato.gravitino.scim.v2.service.web;

/** Metric source names for the SCIM auxiliary HTTP listener. */
public final class ScimMetrics {

  /** Metrics source name for the SCIM REST server on port 9201. */
  public static final String SCIM_REST_SERVER_METRIC_NAME = "scim-rest-server";

  private ScimMetrics() {}
}
