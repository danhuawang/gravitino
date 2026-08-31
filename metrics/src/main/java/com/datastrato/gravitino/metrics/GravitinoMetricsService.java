/*
 * Copyright 2024 Datastrato Inc.
 */
package com.datastrato.gravitino.metrics;

import com.datastrato.gravitino.metrics.storage.relational.service.MetricDataService;
import org.apache.gravitino.GravitinoEnv;
import org.apache.gravitino.server.ServerConfig;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class GravitinoMetricsService {
  public static final String CONF_FILE = "gravitino-metrics-server.conf";

  private static final Logger LOG = LoggerFactory.getLogger(GravitinoMetricsService.class);

  private final ServerConfig serverConfig;
  private final GravitinoEnv gravitinoEnv;
  private final MetricsCollector metricsCollector;
  private final IncrementalMetricsWorker incrementalMetricsWorker;

  public static void main(String[] args) {
    LOG.info("Starting Gravitino Metrics Server");
    String confPath = System.getenv("GRAVITINO_TEST") == null ? "" : args[0];
    ServerConfig serverConfig = ServerConfig.loadConfig(confPath, CONF_FILE);
    GravitinoMetricsService metricsService = new GravitinoMetricsService(serverConfig);
    metricsService.initialize();

    Runtime.getRuntime()
        .addShutdownHook(
            new Thread(
                () -> {
                  try {
                    // Register some clean-up tasks that need to be done before shutting down
                    LOG.info("Shutting down Gravitino Metrics Service ... ");
                    metricsService.stop();
                    LOG.info("Gravitino Metrics Service has shut down.");
                  } catch (Exception e) {
                    LOG.error("Error while stopping Gravitino Metrics Service", e);
                  }
                }));

    try {
      metricsService.start();
      LOG.info("Done, Gravitino Metrics Service started.");

      metricsService.join();
    } catch (Exception e) {
      LOG.error("Error while running Gravitino Metrics Service", e);
      System.exit(-1);
    }
  }

  public GravitinoMetricsService(ServerConfig config) {
    this.serverConfig = config;
    this.gravitinoEnv = GravitinoEnv.getInstance();
    this.metricsCollector = MetricsCollector.getInstance();
    this.incrementalMetricsWorker =
        new IncrementalMetricsWorker(metricsCollector, MetricDataService.getInstance());
  }

  private void initialize() {
    gravitinoEnv.initializeFullComponents(serverConfig);
    metricsCollector.initialize(serverConfig, gravitinoEnv);
    incrementalMetricsWorker.initialize(serverConfig);

    // no REST API needed for metrics
  }

  private void start() throws Exception {
    gravitinoEnv.start();

    // start metrics collector after the server is started
    metricsCollector.start();
    incrementalMetricsWorker.start();
  }

  private void join() {
    try {
      Thread.currentThread().join();
    } catch (InterruptedException e) {
      LOG.info("Gravitino Metrics Service interrupted.");
      Thread.currentThread().interrupt();
    }
  }

  private void stop() {
    incrementalMetricsWorker.close();
    metricsCollector.close();
    gravitinoEnv.shutdown();
  }
}
