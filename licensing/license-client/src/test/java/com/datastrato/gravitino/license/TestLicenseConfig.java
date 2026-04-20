/*
 * Copyright 2024 Datastrato Pvt Ltd.
 * This software is licensed under the Apache License version 2.
 */
package com.datastrato.gravitino.license;

import java.util.Map;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

class TestLicenseConfig {
  @Test
  void testDefaults() {
    LicenseConfig config = new LicenseConfig(Map.of());
    Assertions.assertEquals(24, config.getCheckIntervalHours());
    Assertions.assertEquals(48, config.getNodeStaleHours());
    Assertions.assertEquals(30, config.getWarnDaysBeforeExpiry());
    Assertions.assertEquals("https://datastrato.ai/renew", config.getRenewalContactUrl());
  }

  @Test
  void testOverrides() {
    LicenseConfig config =
        new LicenseConfig(
            Map.of(
                "gravitino.datastrato.license.checkIntervalHours", "12",
                "gravitino.datastrato.license.nodeStaleHours", "24",
                "gravitino.datastrato.license.nodeId", "node-1"));
    Assertions.assertEquals(12, config.getCheckIntervalHours());
    Assertions.assertEquals(24, config.getNodeStaleHours());
    Assertions.assertEquals("node-1", config.getNodeId());
  }

  @Test
  void testLicenseKeyRead() {
    LicenseConfig config =
        new LicenseConfig(Map.of("gravitino.datastrato.license.key", "GRAV-testkey"));
    Assertions.assertEquals("GRAV-testkey", config.getLicenseKey());
  }
}
