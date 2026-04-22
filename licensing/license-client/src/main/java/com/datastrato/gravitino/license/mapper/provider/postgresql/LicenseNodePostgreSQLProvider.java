/*
 * Copyright 2024 Datastrato Pvt Ltd.
 * This software is licensed under the Apache License version 2.
 */
package com.datastrato.gravitino.license.mapper.provider.postgresql;

import com.datastrato.gravitino.license.mapper.provider.base.LicenseNodeBaseSQLProvider;

public class LicenseNodePostgreSQLProvider extends LicenseNodeBaseSQLProvider {

  @Override
  public String upsertNode() {
    return "INSERT INTO license_nodes (node_id, registered_at, last_heartbeat) "
        + "VALUES (#{nodeId}, #{registeredAt}, #{now}) "
        + "ON CONFLICT (node_id) DO UPDATE SET last_heartbeat = EXCLUDED.last_heartbeat";
  }
}
