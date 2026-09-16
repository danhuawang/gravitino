/*
 * Copyright 2026 Datastrato Inc.
 */
package org.apache.gravitino.server;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

import com.datastrato.gravitino.server.web.internal.InternalApiConfigs;
import com.datastrato.gravitino.server.web.internal.InternalApiOriginFilter;
import org.apache.gravitino.GravitinoEnv;
import org.glassfish.jersey.server.ResourceConfig;
import org.junit.jupiter.api.Test;

class TestDatastratoGravitinoServer {

  @Test
  void testConstructorDoesNotAccessUninitializedEnvironmentComponents() {
    GravitinoEnv gravitinoEnv = mock(GravitinoEnv.class);

    assertDoesNotThrow(() -> new DatastratoGravitinoServer(new ServerConfig(false), gravitinoEnv));

    verify(gravitinoEnv, never()).catalogManager();
  }

  @Test
  void testInternalApiOriginFilterIsRegisteredByDefault() {
    ResourceConfig resourceConfig = new ResourceConfig();

    DatastratoGravitinoServer.registerInternalApiOriginFilter(
        resourceConfig, new ServerConfig(false));

    assertTrue(resourceConfig.getClasses().contains(InternalApiOriginFilter.class));
  }

  @Test
  void testInternalApiOriginFilterCanBeDisabled() {
    ServerConfig config = new ServerConfig(false);
    config.set(InternalApiConfigs.REQUIRE_ORIGIN_HEADER, false);
    ResourceConfig resourceConfig = new ResourceConfig();

    DatastratoGravitinoServer.registerInternalApiOriginFilter(resourceConfig, config);

    assertFalse(resourceConfig.getClasses().contains(InternalApiOriginFilter.class));
  }
}
