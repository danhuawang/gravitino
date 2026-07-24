/*
 * Copyright 2026 Datastrato Pvt Ltd.
 * This software is licensed under the Apache License version 2.
 */

package com.datastrato.gravitino.scim.integration.test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertSame;

import com.datastrato.gravitino.scim.service.ScimRESTService;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.net.URLClassLoader;
import java.time.Duration;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import org.apache.commons.lang3.StringUtils;
import org.apache.gravitino.Config;
import org.apache.gravitino.auxiliary.AuxiliaryServiceManager;
import org.apache.gravitino.server.web.JettyServerConfig;
import org.awaitility.Awaitility;

/**
 * Boots production {@link ScimRESTService} (child-first {@code scim-server/libs}) against
 * MiniGravitino.
 */
final class ScimEmbeddedAuxServer implements AutoCloseable {

  private static final String SCIM_CONFIG_PREFIX = "gravitino.scim.";
  private static final String CHILD_FIRST_LOADER =
      "com.datastrato.gravitino.scim.service.classloader.ScimAuxClassLoaders$ScimChildFirstClassLoader";

  private final ScimRESTService scimService;

  private ScimEmbeddedAuxServer(ScimRESTService scimService) {
    this.scimService = scimService;
  }

  /**
   * Starts the SCIM auxiliary listener on the configured port.
   *
   * @param serverConfig active Gravitino server configuration
   * @return running server handle
   */
  static ScimEmbeddedAuxServer start(Config serverConfig) throws Exception {
    Map<String, String> serviceInit = new HashMap<>();
    serverConfig
        .getAllConfig()
        .forEach(
            (key, value) -> {
              if (key.startsWith(SCIM_CONFIG_PREFIX)) {
                serviceInit.put(key.substring(SCIM_CONFIG_PREFIX.length()), value);
              }
            });
    if (StringUtils.isBlank(serviceInit.get(AuxiliaryServiceManager.AUX_SERVICE_CLASSPATH))) {
      throw new IllegalStateException(
          "Missing gravitino.scim.classpath; set it to distribution/package/scim-server/libs");
    }

    ScimRESTService service = new ScimRESTService();
    service.serviceInit(serviceInit, true);
    service.serviceStart();

    JettyServerConfig scimJetty = JettyServerConfig.fromConfig(serverConfig, SCIM_CONFIG_PREFIX);
    awaitPort(scimJetty.getHost(), scimJetty.getHttpPort());

    return new ScimEmbeddedAuxServer(service);
  }

  /**
   * Confirms Impl / Jetty came from the SCIM child-first loader, not MiniGravitino's Jetty 9
   * classpath.
   *
   * @throws ClassNotFoundException if Jetty cannot be resolved for comparison
   */
  void assertChildFirstHttpStack() throws ClassNotFoundException {
    URLClassLoader scimLoader = scimService.scimClassLoader();
    Object impl = scimService.scimImplementation();
    assertNotNull(scimLoader, "SCIM child-first classloader");
    assertNotNull(impl, "SCIM REST service implementation");
    assertEquals(CHILD_FIRST_LOADER, scimLoader.getClass().getName());
    assertSame(scimLoader, impl.getClass().getClassLoader());

    Class<?> scimJetty = scimLoader.loadClass("org.eclipse.jetty.server.Server");
    assertSame(scimLoader, scimJetty.getClassLoader());
    assertNotSame(
        Class.forName("org.eclipse.jetty.server.Server"),
        scimJetty,
        "SCIM must use Jetty from scim-server/libs, not the main server Jetty");
  }

  @Override
  public void close() throws Exception {
    scimService.serviceStop();
  }

  private static void awaitPort(String host, int port) {
    Awaitility.await()
        .atMost(60, TimeUnit.SECONDS)
        .pollInterval(Duration.ofMillis(500))
        .until(
            () -> {
              try (Socket socket = new Socket()) {
                socket.connect(new InetSocketAddress(host, port), 1000);
                return true;
              } catch (IOException e) {
                return false;
              }
            });
  }
}
