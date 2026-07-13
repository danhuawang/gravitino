/*
 * Copyright 2026 Datastrato Pvt Ltd.
 * This software is licensed under the Apache License version 2.
 */

package com.datastrato.gravitino.scim.service;

import com.datastrato.gravitino.scim.service.rest.GravitinoScimApplication;
import com.datastrato.gravitino.scim.service.web.ScimHealthAliasServlet;
import com.datastrato.gravitino.scim.service.web.ScimHttpAuditFilter;
import com.datastrato.gravitino.scim.service.web.ScimMetrics;
import com.datastrato.gravitino.scim.service.web.ScimRequestPaths;
import java.util.Map;
import org.apache.gravitino.GravitinoEnv;
import org.apache.gravitino.auxiliary.GravitinoAuxiliaryService;
import org.apache.gravitino.metrics.MetricsSystem;
import org.apache.gravitino.server.web.JettyServerConfig;
import org.glassfish.jersey.server.ResourceConfig;
import org.glassfish.jersey.servlet.ServletContainer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/** SCIM 2.0 auxiliary HTTP service on port 9201 using Apache SCIMple. */
public class ScimRESTService implements GravitinoAuxiliaryService {

  private static final Logger LOG = LoggerFactory.getLogger(ScimRESTService.class);

  /** Auxiliary service short name registered in {@code gravitino.auxService.names}. */
  public static final String SERVICE_NAME = "scim";

  private ScimJettyServer jettyServer;

  /** {@inheritDoc} */
  @Override
  public String shortName() {
    return SERVICE_NAME;
  }

  /** {@inheritDoc} */
  @Override
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

    jettyServer.addFilter(
        new ScimHttpAuditFilter(new ScimHealthCheckPathMatcher()), ScimRequestPaths.SCIM_SPEC);
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

  /** {@inheritDoc} */
  @Override
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

  /** {@inheritDoc} */
  @Override
  public void serviceStop() throws Exception {
    if (jettyServer != null) {
      jettyServer.stop();
      jettyServer = null;
      LOG.info("SCIM auxiliary service stopped");
    }
  }
}
