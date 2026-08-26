/*
 * Copyright 2026 Datastrato Pvt Ltd.
 */

package com.datastrato.gravitino.scim.service.rest;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.apache.directory.scim.server.configuration.ServerConfiguration;
import org.junit.jupiter.api.Test;

class TestGravitinoScimApplication {

  @Test
  void testServiceProviderConfigDisablesUnsupportedCapabilities() {
    ServerConfiguration configuration = ScimServerConfigurations.create();

    assertFalse(configuration.isSupportsBulk());
    assertFalse(configuration.isSupportsETag());
    assertTrue(configuration.isSupportsFilter());
    assertFalse(configuration.isSupportsSort());
    assertNotNull(configuration.getBulkConfiguration());
    assertNotNull(configuration.getEtagConfiguration());
    assertFalse(configuration.getBulkConfiguration().isSupported());
    assertFalse(configuration.getEtagConfiguration().isSupported());
  }
}
