/*
 * Copyright 2024 Datastrato Inc.
 */
package com.datastrato.gravitino.license.mapper.provider.base;

public class LicenseNodeBaseSQLProvider {

  private static final String NOW_MS =
      "(UNIX_TIMESTAMP() * 1000.0) + EXTRACT(MICROSECOND FROM CURRENT_TIMESTAMP(3)) / 1000";

  public String upsertNode() {
    return "INSERT INTO license_nodes (node_id, registered_at, last_heartbeat) "
        + "VALUES (#{nodeId}, "
        + NOW_MS
        + ", "
        + NOW_MS
        + ") "
        + "ON DUPLICATE KEY UPDATE last_heartbeat = "
        + NOW_MS;
  }

  public String deleteNode() {
    return "DELETE FROM license_nodes WHERE node_id = #{nodeId}";
  }

  public String deleteStaleNodes() {
    return "DELETE FROM license_nodes WHERE last_heartbeat < (" + NOW_MS + " - #{staleIntervalMs})";
  }

  public String countActiveNodes() {
    return "SELECT COUNT(*) FROM license_nodes";
  }

  public String rankNode() {
    // Returns 0 when the node is not present (e.g. concurrently deleted).
    return "SELECT CASE WHEN EXISTS (SELECT 1 FROM license_nodes WHERE node_id = #{nodeId}) "
        + "THEN (SELECT COUNT(*) + 1 FROM license_nodes "
        + "WHERE registered_at < (SELECT registered_at FROM license_nodes WHERE node_id = #{nodeId}) "
        + "OR (registered_at = (SELECT registered_at FROM license_nodes WHERE node_id = #{nodeId}) "
        + "AND node_id < #{nodeId})) "
        + "ELSE 0 END";
  }
}
