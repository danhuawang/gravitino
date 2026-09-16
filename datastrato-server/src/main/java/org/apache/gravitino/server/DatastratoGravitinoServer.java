/*
 * Copyright 2024 Datastrato Inc.
 */
package org.apache.gravitino.server;

import com.datastrato.gravitino.ExtendedDatastratoGravitinoEnv;
import com.datastrato.gravitino.catalog.connection.CatalogConnectionTestGarbageCollector;
import com.datastrato.gravitino.catalog.connection.CatalogConnectionTestMetaService;
import com.datastrato.gravitino.catalog.connection.ConnectionTestStore;
import com.datastrato.gravitino.license.rest.LicenseStatusResource;
import com.datastrato.gravitino.metrics.storage.relational.service.MetricDataService;
import com.datastrato.gravitino.preview.DataPreviewConfig;
import com.datastrato.gravitino.server.web.InternalApiPaths;
import com.datastrato.gravitino.server.web.internal.InternalApiConfigs;
import com.datastrato.gravitino.server.web.internal.InternalApiOriginFilter;
import java.io.IOException;
import javax.annotation.Nullable;
import org.apache.gravitino.Config;
import org.apache.gravitino.Configs;
import org.apache.gravitino.GravitinoEnv;
import org.apache.gravitino.catalog.CatalogManager;
import org.glassfish.hk2.utilities.binding.AbstractBinder;
import org.glassfish.jersey.server.ResourceConfig;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class DatastratoGravitinoServer extends GravitinoServer {
  private static final Logger LOG = LoggerFactory.getLogger(DatastratoGravitinoServer.class);

  private final GravitinoEnv gravitinoEnv;

  @Nullable private CatalogConnectionTestGarbageCollector connectionTestGarbageCollector;

  public DatastratoGravitinoServer(ServerConfig config, GravitinoEnv gravitinoEnv) {
    super(config, gravitinoEnv);
    this.gravitinoEnv = gravitinoEnv;
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
    CatalogManager catalogManager = gravitinoEnv.catalogManager();
    ConfigServletConfigEntryRegistrar.register(this, DataPreviewConfig.ENABLED_CONFIG);

    boolean enableAuthorization = serverConfig().get(Configs.ENABLE_AUTHORIZATION);
    MetricDataService metricDataService = MetricDataService.getInstance();
    metricDataService.initialize(enableAuthorization);
    ConnectionTestStore connectionTestStore = CatalogConnectionTestMetaService.getInstance();
    if (Configs.RELATIONAL_ENTITY_STORE.equalsIgnoreCase(serverConfig().get(Configs.ENTITY_STORE))
        && Configs.DEFAULT_ENTITY_RELATIONAL_STORE.equalsIgnoreCase(
            serverConfig().get(Configs.ENTITY_RELATIONAL_STORE))) {
      connectionTestGarbageCollector =
          new CatalogConnectionTestGarbageCollector(connectionTestStore, serverConfig());
    }

    // initialize extra rest api resources
    register(
        new AbstractBinder() {
          @Override
          protected void configure() {
            bind(metricDataService).to(MetricDataService.class).ranked(1);
            bind(connectionTestStore).to(ConnectionTestStore.class).ranked(1);
            bind(catalogManager).to(CatalogManager.class).ranked(1);
          }
        });
    register(LicenseStatusResource.class);
    registerInternalApiOriginFilter(this, serverConfig());
  }

  @Override
  public void start() throws Exception {
    super.start();
    if (connectionTestGarbageCollector != null) {
      connectionTestGarbageCollector.start();
    }
  }

  /** {@inheritDoc} */
  @Override
  public void stop() throws IOException {
    if (connectionTestGarbageCollector != null) {
      connectionTestGarbageCollector.close();
    }
    super.stop();
  }

  /**
   * Registers the filter that requires the internal origin header on internal REST APIs, unless it
   * is disabled by configuration.
   *
   * @param resourceConfig the Jersey resource config of the main REST API
   * @param config the server configuration
   */
  static void registerInternalApiOriginFilter(ResourceConfig resourceConfig, Config config) {
    if (config.get(InternalApiConfigs.REQUIRE_ORIGIN_HEADER)) {
      resourceConfig.register(InternalApiOriginFilter.class);
    } else {
      LOG.warn(
          "{} is false: internal REST APIs under /api/internal accept requests without the {}"
              + " header.",
          InternalApiConfigs.REQUIRE_ORIGIN_HEADER.getKey(),
          InternalApiPaths.ORIGIN_HEADER);
    }
  }
}
