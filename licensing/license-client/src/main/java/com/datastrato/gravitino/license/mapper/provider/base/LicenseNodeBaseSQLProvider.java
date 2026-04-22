/*
 * Copyright 2024 Datastrato Pvt Ltd.
 * This software is licensed under the Apache License version 2.
 */
package com.datastrato.gravitino.license.mapper.provider.base;

public class LicenseNodeBaseSQLProvider {

  public String upsertNode() {
    return "INSERT INTO license_nodes (node_id, registered_at, last_heartbeat) "
        + "VALUES (#{nodeId}, #{registeredAt}, #{now}) "
        + "ON DUPLICATE KEY UPDATE last_heartbeat = #{now}";
  }

  public String deleteNode() {
    return "DELETE FROM license_nodes WHERE node_id = #{nodeId}";
  }

  public String deleteStaleNodes() {
    return "DELETE FROM license_nodes WHERE last_heartbeat < #{staleThresholdMs}";
  }

  public String updateHeartbeat() {
    return "UPDATE license_nodes SET last_heartbeat = #{now} WHERE node_id = #{nodeId}";
  }

  public String countActiveNodes() {
    return "SELECT COUNT(*) FROM license_nodes";
  }
}
