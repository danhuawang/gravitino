/*
 * Copyright 2026 Datastrato Inc.
 */

package com.datastrato.gravitino.scim.integration.test;

import static org.apache.gravitino.Configs.ENTITY_RELATIONAL_JDBC_BACKEND_PATH;
import static org.apache.gravitino.server.GravitinoServer.WEBSERVER_CONF_PREFIX;

import com.datastrato.gravitino.scim.ScimErrorHistoryManager;
import com.datastrato.gravitino.scim.ScimTokenManager;
import com.datastrato.gravitino.scim.model.CreatedScimToken;
import com.datastrato.gravitino.scim.storage.po.ScimTokenMetaPO;
import com.datastrato.gravitino.scim.storage.service.ScimTokenMetaService;
import com.google.common.collect.Maps;
import java.io.File;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import org.apache.gravitino.Config;
import org.apache.gravitino.Configs;
import org.apache.gravitino.GravitinoEnv;
import org.apache.gravitino.UserPrincipal;
import org.apache.gravitino.auxiliary.AuxiliaryServiceManager;
import org.apache.gravitino.client.GravitinoAdminClient;
import org.apache.gravitino.integration.test.MiniGravitino;
import org.apache.gravitino.integration.test.MiniGravitinoContext;
import org.apache.gravitino.rest.RESTUtils;
import org.apache.gravitino.server.web.JettyServerConfig;
import org.apache.gravitino.utils.PrincipalUtils;

/**
 * Integration harness: MiniGravitino (Jersey 2) plus production {@code ScimRESTService} (Jersey 3
 * via child-first {@code scim-server/libs}).
 */
final class ScimServiceITEnvironment implements AutoCloseable {

  private static final String SCIM_CONFIG_PREFIX = "gravitino.scim.";
  private static final String SCIM_LIBS_RELATIVE_PATH = "distribution/package/scim-server/libs";

  private final Map<String, String> customConfigs = Maps.newHashMap();
  private MiniGravitino miniGravitino;
  private ScimEmbeddedAuxServer scimServer;
  private Config serverConfig;
  private GravitinoAdminClient adminClient;
  private String gravitinoBaseUri;
  private String scimBaseUri;

  private ScimServiceITEnvironment() {}

  /** Starts embedded Gravitino and the SCIM auxiliary listener. */
  static ScimServiceITEnvironment start() throws Exception {
    return start(Map.of());
  }

  /**
   * Starts embedded Gravitino and the SCIM auxiliary listener with extra server config overrides.
   */
  static ScimServiceITEnvironment start(Map<String, String> extraConfigs) throws Exception {
    ScimServiceITEnvironment environment = new ScimServiceITEnvironment();
    if (extraConfigs != null) {
      environment.customConfigs.putAll(extraConfigs);
    }
    environment.doStart();
    return environment;
  }

  Config serverConfig() {
    return serverConfig;
  }

  GravitinoAdminClient adminClient() {
    return adminClient;
  }

  String gravitinoBaseUri() {
    return gravitinoBaseUri;
  }

  /** Returns the SCIM auxiliary listener base URI (Jersey 3). */
  String scimBaseUri() {
    return scimBaseUri;
  }

  void assertChildFirstHttpStack() throws ClassNotFoundException {
    scimServer.assertChildFirstHttpStack();
  }

  /** Mints an opaque SCIM bearer token without using the token admin REST API. */
  String mintScimBearerToken(String tokenName, String creator) throws Exception {
    ensureTokenManagerInitialized();
    ScimTokenManager tokenManager = ScimTokenManager.getInstance();

    return PrincipalUtils.doAs(
        new UserPrincipal(creator),
        () -> {
          CreatedScimToken created = tokenManager.createScimToken(tokenName, null);
          return created.getTokenValue();
        });
  }

  /** Loads active SCIM token metadata for assertions (for example {@code last_used_at}). */
  ScimTokenMetaPO readScimTokenMeta(String tokenName) {
    ensureTokenManagerInitialized();
    return ScimTokenMetaService.getInstance().getScimTokenMetaByName(tokenName);
  }

  /** Counts persisted SCIM protocol error-history rows. */
  long countScimErrorHistory() {
    ensureTokenManagerInitialized();
    return ScimErrorHistoryManager.getInstance().countScimErrorHistory();
  }

  private void ensureTokenManagerInitialized() {
    GravitinoEnv env = GravitinoEnv.getInstance();
    ScimTokenManager tokenManager = ScimTokenManager.getInstance();
    try {
      tokenManager.initialize(serverConfig, env.idGenerator());
    } catch (IllegalStateException alreadyInitialized) {
      // Singleton may already be initialized in this JVM.
    }
  }

  @Override
  public void close() throws Exception {
    if (adminClient != null) {
      adminClient.close();
      adminClient = null;
    }
    if (scimServer != null) {
      scimServer.close();
      scimServer = null;
    }
    if (miniGravitino != null) {
      miniGravitino.stop();
      miniGravitino = null;
    }
    customConfigs.clear();
  }

  private void doStart() throws Exception {
    customConfigs.put("SimpleAuthUserName", "scimItOwner");
    customConfigs.put(Configs.ENABLE_AUTHORIZATION.getKey(), String.valueOf(true));
    customConfigs.put(Configs.CACHE_ENABLED.getKey(), String.valueOf(false));
    customConfigs.put(Configs.STORE_DELETE_AFTER_TIME.getKey(), String.valueOf(20 * 60 * 1000L));
    customConfigs.put(Configs.SERVICE_ADMINS.getKey(), "scimItOwner");
    customConfigs.put(Configs.AUTHENTICATORS.getKey(), "simple");

    ScimITJdbcSupport.configureJdbcBackend(customConfigs);

    File jdbcPathDir =
        Files.createTempDirectory(Path.of(System.getProperty("java.io.tmpdir")), "scim-it")
            .toFile();
    jdbcPathDir.deleteOnExit();
    customConfigs.put(ENTITY_RELATIONAL_JDBC_BACKEND_PATH.getKey(), jdbcPathDir.getAbsolutePath());

    String gravitinoHome = System.getenv("GRAVITINO_HOME");
    if (gravitinoHome == null || gravitinoHome.isBlank()) {
      throw new IllegalStateException(
          "GRAVITINO_HOME must be set for SCIM service integration tests");
    }

    int scimPort = RESTUtils.findAvailablePort(9000, 10000);
    customConfigs.put(
        SCIM_CONFIG_PREFIX + AuxiliaryServiceManager.AUX_SERVICE_CLASSPATH,
        SCIM_LIBS_RELATIVE_PATH);
    customConfigs.put(
        SCIM_CONFIG_PREFIX + JettyServerConfig.WEBSERVER_HTTP_PORT.getKey(),
        String.valueOf(scimPort));

    MiniGravitinoContext context = new MiniGravitinoContext(customConfigs, true, true);
    miniGravitino = new MiniGravitino(context);
    miniGravitino.start();
    serverConfig = miniGravitino.getServerConfig();

    ScimEnterpriseSchemaInitializer.initialize(serverConfig);
    scimServer = ScimEmbeddedAuxServer.start(serverConfig);

    JettyServerConfig mainJetty = JettyServerConfig.fromConfig(serverConfig, WEBSERVER_CONF_PREFIX);
    gravitinoBaseUri = String.format("http://%s:%d", mainJetty.getHost(), mainJetty.getHttpPort());

    JettyServerConfig scimJetty = JettyServerConfig.fromConfig(serverConfig, SCIM_CONFIG_PREFIX);
    scimBaseUri = String.format("http://%s:%d", scimJetty.getHost(), scimJetty.getHttpPort());

    adminClient =
        GravitinoAdminClient.builder(gravitinoBaseUri).withSimpleAuth("scimItOwner").build();
  }
}
