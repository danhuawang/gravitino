/*
 * Copyright 2026 Datastrato Inc.
 */

package com.datastrato.gravitino.scim.service;

import com.datastrato.gravitino.scim.ScimErrorHistoryManager;
import com.datastrato.gravitino.scim.service.classloader.ScimAuxClassLoaders;
import com.datastrato.gravitino.scim.service.rest.GravitinoScimApplication;
import com.datastrato.gravitino.scim.service.web.ScimHealthAliasServlet;
import com.datastrato.gravitino.scim.service.web.ScimHttpAuditFilter;
import com.datastrato.gravitino.scim.service.web.ScimMetrics;
import com.datastrato.gravitino.scim.service.web.ScimRequestPaths;
import java.util.Map;
import org.apache.gravitino.GravitinoEnv;
import org.apache.gravitino.listener.api.event.EventSource;
import org.apache.gravitino.metrics.MetricsSystem;
import org.apache.gravitino.server.web.JettyServerConfig;
import org.glassfish.jersey.server.ResourceConfig;
import org.glassfish.jersey.servlet.ServletContainer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * SCIM HTTP listener implementation (Jetty 11 / Jersey 3 / Jakarta).
 *
 * <p>Loaded by {@link ScimRESTService} through {@link ScimAuxClassLoaders} so servlet / Jetty types
 * are not taken from the main Gravitino classpath.
 */
public final class ScimRESTServiceImpl {

  private static final Logger LOG = LoggerFactory.getLogger(ScimRESTServiceImpl.class);

  private ScimJettyServer jettyServer;

  /**
   * Initializes the embedded SCIM listener.
   *
   * @param properties short-key aux service config from {@code AuxiliaryServiceManager}
   * @param auxMode whether running under aux-service mode
   */
  public void serviceInit(Map<String, String> properties, boolean auxMode) {
    ScimConfig scimConfig = new ScimConfig(properties, GravitinoEnv.getInstance().config());
    JettyServerConfig serverConfig = JettyServerConfig.fromConfig(scimConfig);

    ResourceConfig resourceConfig =
        GravitinoScimApplication.create(GravitinoEnv.getInstance().config(), scimConfig);

    jettyServer = new ScimJettyServer();
    jettyServer.initialize(serverConfig);

    MetricsSystem metricsSystem = GravitinoEnv.getInstance().metricsSystem();
    if (metricsSystem != null) {
      ScimHttpServerMetricsSource metricsSource =
          new ScimHttpServerMetricsSource(
              ScimMetrics.SCIM_REST_SERVER_METRIC_NAME, resourceConfig, jettyServer);
      metricsSystem.register(metricsSource);
    }

    // Same audit path as Iceberg REST: EventBus → AuditLogManager → gravitino.audit.
    // EventSource stays GRAVITINO_SERVER (SCIM runs as an auxiliary listener on this process).
    jettyServer.addFilter(
        new ScimHttpAuditFilter(
            GravitinoEnv.getInstance().eventBus(),
            EventSource.GRAVITINO_SERVER,
            new ScimHealthCheckPathMatcher(),
            ScimErrorHistoryManager.getInstance()),
        ScimRequestPaths.SCIM_SPEC);
    jettyServer.addFilter(new ScimBearerAuthFilter(), ScimRequestPaths.SCIM_SPEC);
    jettyServer.addFilter(new ScimURLScopeResolver(), ScimRequestPaths.SCIM_SPEC);
    jettyServer.addServlet(new ServletContainer(resourceConfig), ScimRequestPaths.SCIM_SPEC);
    jettyServer.addServlet(new ScimHealthAliasServlet("/scim"), "/health/*");
    jettyServer.addServlet(new ScimHealthAliasServlet("/scim"), "/health.html");

    LOG.info(
        "SCIM auxiliary service initialized on {}:{} (auxMode={})",
        serverConfig.getHost(),
        serverConfig.getHttpPort(),
        auxMode);
  }

  /**
   * Starts the embedded SCIM listener.
   *
   * @throws RuntimeException if Jetty fails to start
   */
  public void serviceStart() {
    if (jettyServer == null) {
      return;
    }
    try {
      jettyServer.start();
      LOG.info("SCIM auxiliary service started");
    } catch (Exception e) {
      throw new RuntimeException("Failed to start SCIM auxiliary service", e);
    }
  }

  /**
   * Stops the embedded SCIM listener.
   *
   * @throws Exception if Jetty fails to stop
   */
  public void serviceStop() throws Exception {
    if (jettyServer != null) {
      jettyServer.stop();
      jettyServer = null;
      LOG.info("SCIM auxiliary service stopped");
    }
  }
}
