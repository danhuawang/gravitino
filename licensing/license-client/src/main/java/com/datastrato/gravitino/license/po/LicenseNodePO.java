/*
 * Copyright 2024 Datastrato Pvt Ltd.
 * This software is licensed under the Apache License version 2.
 */
package com.datastrato.gravitino.license.po;

import lombok.Data;

@Data
public class LicenseNodePO {
  private String nodeId;
  private long registeredAt;
  private long lastHeartbeat;
}
