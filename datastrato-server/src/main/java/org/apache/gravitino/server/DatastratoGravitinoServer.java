/*
 * Copyright 2024 Datastrato Pvt Ltd.
 * This software is licensed under the Apache License version 2.
 */
package org.apache.gravitino.server;

import com.datastrato.gravitino.ExtendedDatastratoGravitinoEnv;
import org.apache.gravitino.GravitinoEnv;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class DatastratoGravitinoServer extends GravitinoServer {
  private static final Logger LOG = LoggerFactory.getLogger(DatastratoGravitinoServer.class);

  public DatastratoGravitinoServer(ServerConfig config, GravitinoEnv gravitinoEnv) {
    super(config, gravitinoEnv);
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
}
