/*
 * Copyright 2024 Datastrato Pvt Ltd.
 * This software is licensed under the Apache License version 2.
 */
package org.apache.gravitino.server;

import com.datastrato.gravitino.DatastratoGravitinoEnv;
import com.datastrato.gravitino.ExtendedDatastratoGravitinoEnv;
import com.datastrato.gravitino.server.web.metric.MetricsCollector;
import com.datastrato.gravitino.storage.relational.service.MetricDataService;
import org.apache.gravitino.GravitinoEnv;
import org.glassfish.hk2.utilities.binding.AbstractBinder;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class DatastratoGravitinoServer extends GravitinoServer {
  private static final Logger LOG = LoggerFactory.getLogger(DatastratoGravitinoServer.class);

  private final DatastratoGravitinoEnv datastratoGravitinoEnv;
  private MetricsCollector metricsCollector;

  public DatastratoGravitinoServer(ServerConfig config, GravitinoEnv gravitinoEnv) {
    super(config, gravitinoEnv);
    this.datastratoGravitinoEnv = (DatastratoGravitinoEnv) gravitinoEnv;
  }

  public static void main(String[] args) {
    LOG.info("Starting Datastrato Gravitino Server");
    String confPath = System.getenv("GRAVITINO_TEST") == null ? "" : args[0];
    ServerConfig serverConfig = loadConfig(confPath);
    DatastratoGravitinoServer server =
        new DatastratoGravitinoServer(serverConfig, ExtendedDatastratoGravitinoEnv.getInstance());
    server.initialize();

    try {
      // Instantiates GravitinoServer
      server.start();
    } catch (Exception e) {
      LOG.error("Error while running jettyServer", e);
      System.exit(-1);
    }
    LOG.info("Done, Datastrato Gravitino server started.");

    Runtime.getRuntime()
        .addShutdownHook(
            new Thread(
                () -> {
                  try {
                    // Register some clean-up tasks that need to be done before shutting down
                    Thread.sleep(server.serverConfig().get(ServerConfig.SERVER_SHUTDOWN_TIMEOUT));
                  } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    LOG.error("Interrupted exception:", e);
                  } catch (Exception e) {
                    LOG.error("Error while running clean-up tasks in shutdown hook", e);
                  }
                }));

    server.join();

    LOG.info("Shutting down Enterprise Gravitino Server ... ");
    try {
      server.stop();
      LOG.info("Datastrato Gravitino Server has shut down.");
    } catch (Exception e) {
      LOG.error("Error while stopping Datastrato Gravitino Server", e);
    }
  }

  @Override
  public void initialize() {
    super.initialize();

    this.metricsCollector =
        new MetricsCollector(
            serverConfig(),
            datastratoGravitinoEnv.metalakeDispatcher(),
            datastratoGravitinoEnv.catalogDispatcher(),
            datastratoGravitinoEnv.schemaDispatcher(),
            datastratoGravitinoEnv.tableDispatcher(),
            datastratoGravitinoEnv.filesetDispatcher(),
            datastratoGravitinoEnv.topicDispatcher(),
            datastratoGravitinoEnv.modelDispatcher(),
            datastratoGravitinoEnv.tagDispatcher(),
            datastratoGravitinoEnv.accessControlDispatcher(),
            datastratoGravitinoEnv.metricDataService());

    // initialize extra rest api resources
    register(
        new AbstractBinder() {
          @Override
          protected void configure() {
            bind(datastratoGravitinoEnv.metricDataService()).to(MetricDataService.class).ranked(1);
            bind(metricsCollector).to(MetricsCollector.class).ranked(1);
          }
        });
  }

  @Override
  public void start() throws Exception {
    super.start();

    // start metrics collector after the server is started
    metricsCollector.start();
  }
}
