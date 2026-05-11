/*
 * Copyright 2024 Datastrato Pvt Ltd.
 * This software is licensed under the Apache License version 2.
 */
package com.datastrato.gravitino.license.mapper.provider.postgresql;

import com.datastrato.gravitino.license.mapper.provider.base.LicenseNodeBaseSQLProvider;

public class LicenseNodePostgreSQLProvider extends LicenseNodeBaseSQLProvider {

  private static final String PG_NOW_MS =
      "CAST(EXTRACT(EPOCH FROM CURRENT_TIMESTAMP) * 1000 AS BIGINT)";

  @Override
  public String upsertNode() {
    return "INSERT INTO license_nodes (node_id, registered_at, last_heartbeat) "
        + "VALUES (#{nodeId}, "
        + PG_NOW_MS
        + ", "
        + PG_NOW_MS
        + ") ON CONFLICT (node_id) DO UPDATE SET "
        + "last_heartbeat = "
        + PG_NOW_MS;
  }

  @Override
  public String deleteStaleNodes() {
    return "DELETE FROM license_nodes WHERE last_heartbeat < ("
        + PG_NOW_MS
        + " - #{staleIntervalMs})";
  }
}
