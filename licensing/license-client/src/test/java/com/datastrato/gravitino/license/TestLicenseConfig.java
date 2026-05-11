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
    Assertions.assertEquals(5, config.getNodeHeartbeatIntervalMinutes());
    Assertions.assertEquals(15, config.getNodeStaleMinutes());
    Assertions.assertEquals(30, config.getWarnDaysBeforeExpiry());
    Assertions.assertEquals("https://datastrato.ai/renew", config.getRenewalContactUrl());
  }

  @Test
  void testOverrides() {
    LicenseConfig config =
        new LicenseConfig(
            Map.of(
                "gravitino.datastrato.license.checkIntervalHours", "12",
                "gravitino.datastrato.license.nodeHeartbeatIntervalMinutes", "10",
                "gravitino.datastrato.license.nodeStaleMinutes", "30"));
    Assertions.assertEquals(12, config.getCheckIntervalHours());
    Assertions.assertEquals(10, config.getNodeHeartbeatIntervalMinutes());
    Assertions.assertEquals(30, config.getNodeStaleMinutes());
  }

  @Test
  void testLicenseKeyRead() {
    LicenseConfig config =
        new LicenseConfig(Map.of("gravitino.datastrato.license.key", "GRAV-testkey"));
    Assertions.assertEquals("GRAV-testkey", config.getLicenseKey());
  }
}
