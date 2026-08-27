/*
 * Copyright 2026 Datastrato Pvt Ltd.
 */
package com.datastrato.gravitino.catalog.connection;

import static org.apache.gravitino.Configs.ENTITY_RELATIONAL_JDBC_BACKEND_MAX_CONNECTIONS;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.datastrato.gravitino.catalog.connection.mapper.ConnectionTestResultMapper;
import com.datastrato.gravitino.catalog.connection.mapper.po.ConnectionTestResultPO;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.Statement;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import org.apache.commons.lang3.StringUtils;
import org.apache.gravitino.Config;
import org.apache.gravitino.Configs;
import org.apache.gravitino.NameIdentifier;
import org.apache.gravitino.config.ConfigConstants;
import org.apache.gravitino.storage.relational.JDBCBackend.JDBCBackendType;
import org.apache.gravitino.storage.relational.session.SqlSessionFactoryHelper;
import org.apache.gravitino.storage.relational.utils.SessionUtils;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.testcontainers.containers.MySQLContainer;
import org.testcontainers.containers.PostgreSQLContainer;

class TestCatalogConnectionTestMetaServiceBackends {
  private static final String H2_URL =
      "jdbc:h2:mem:catalog_connection_backend_test;DB_CLOSE_DELAY=-1;MODE=MySQL";
  private static final String METALAKE = "connection_test";
  private static final String CATALOG = "catalog";

  private final CatalogConnectionTestMetaService service =
      CatalogConnectionTestMetaService.getInstance();

  @Test
  void testH2Backend() throws Exception {
    verifyBackend(H2_URL, "sa", "", "org.h2.Driver");
  }

  @Test
  @Tag("gravitino-docker-test")
  void testMySQLBackend() throws Exception {
    try (MySQLContainer<?> container =
        new MySQLContainer<>("mysql:8.0")
            .withDatabaseName("gravitino")
            .withUsername("root")
            .withPassword("root")) {
      container.start();
      verifyBackend(
          container.getJdbcUrl(),
          container.getUsername(),
          container.getPassword(),
          container.getDriverClassName());
    }
  }

  @Test
  @Tag("gravitino-docker-test")
  void testPostgreSQLBackend() throws Exception {
    try (PostgreSQLContainer<?> container =
        new PostgreSQLContainer<>("postgres:13")
            .withDatabaseName("gravitino")
            .withUsername("root")
            .withPassword("root")) {
      container.start();
      verifyBackend(
          container.getJdbcUrl(),
          container.getUsername(),
          container.getPassword(),
          container.getDriverClassName());
    }
  }

  private void verifyBackend(String url, String user, String password, String driver)
      throws Exception {
    SqlSessionFactoryHelper.getInstance().close();
    initializeSchemaAndCatalog(url, user, password);
    SqlSessionFactoryHelper.getInstance().init(config(url, user, password, driver));
    try {
      NameIdentifier identifier = NameIdentifier.of(METALAKE, CATALOG);
      CatalogConnectionSnapshot snapshot = service.loadCatalogConnectionSnapshot(identifier);

      assertEquals(11L, snapshot.catalogId());
      assertTrue(
          service.recordTestResult(
              snapshot,
              ConnectionTestType.CATALOG,
              ConnectionTestResult.Status.PASSED,
              1000L,
              null));
      assertTrue(
          service.recordTestResult(
              snapshot,
              ConnectionTestType.credential("s3-token"),
              ConnectionTestResult.Status.FAILED,
              1001L,
              "Safe credential failure"));
      assertTrue(
          service.recordTestResult(
              snapshot,
              ConnectionTestType.credential("gcs-token"),
              ConnectionTestResult.Status.PASSED,
              1002L,
              null));

      assertEquals(
          ConnectionTestResult.Status.PASSED,
          service
              .getValidTestResult(identifier, ConnectionTestType.CATALOG)
              .orElseThrow()
              .status());
      assertEquals(
          ConnectionTestResult.Status.FAILED,
          service
              .getValidTestResult(identifier, ConnectionTestType.credential("s3-token"))
              .orElseThrow()
              .status());

      assertTrue(
          service.recordTestResult(
              snapshot,
              ConnectionTestType.CATALOG,
              ConnectionTestResult.Status.FAILED,
              1003L,
              "Safe catalog failure"));
      ConnectionTestResult replaced =
          service.getValidTestResult(identifier, ConnectionTestType.CATALOG).orElseThrow();
      assertEquals(ConnectionTestResult.Status.FAILED, replaced.status());
      assertEquals(1003L, replaced.lastTestedAt());
      assertEquals("Safe catalog failure", replaced.errorMessage());

      setCatalogVersion(url, user, password, 2L);
      CatalogConnectionSnapshot updatedSnapshot = service.loadCatalogConnectionSnapshot(identifier);
      assertEquals(2L, updatedSnapshot.catalogVersion());

      service.reconcileTestResultAfterCatalogChange(
          snapshot, updatedSnapshot, ConnectionTestType.CATALOG, true);
      service.reconcileTestResultAfterCatalogChange(
          snapshot, updatedSnapshot, ConnectionTestType.credential("s3-token"), false);

      assertEquals(
          ConnectionTestResult.Status.FAILED,
          service
              .getValidTestResult(identifier, ConnectionTestType.CATALOG)
              .orElseThrow()
              .status());
      assertTrue(
          service
              .getValidTestResult(identifier, ConnectionTestType.credential("s3-token"))
              .isEmpty());

      List<ConnectionTestResultPO> stored =
          SessionUtils.getWithoutCommit(
              ConnectionTestResultMapper.class, mapper -> mapper.list(snapshot.catalogId()));
      assertEquals(2, stored.size());
      assertEquals(ConnectionTestType.CATALOG, stored.get(0).getType());
      assertEquals(2L, stored.get(0).getCatalogVersion());
      assertEquals(ConnectionTestType.credential("gcs-token"), stored.get(1).getType());
      assertEquals(1L, stored.get(1).getCatalogVersion());

      hardDeleteCatalog(url, user, password);
      assertEquals(2, service.deleteOrphanedTestResults(100));
      assertTrue(
          SessionUtils.getWithoutCommit(
                  ConnectionTestResultMapper.class, mapper -> mapper.list(snapshot.catalogId()))
              .isEmpty());
    } finally {
      SqlSessionFactoryHelper.getInstance().close();
    }
  }

  private Config config(String url, String user, String password, String driver) {
    Config config = Mockito.mock(Config.class);
    Mockito.when(config.get(Configs.ENTITY_RELATIONAL_JDBC_BACKEND_URL)).thenReturn(url);
    Mockito.when(config.get(Configs.ENTITY_RELATIONAL_JDBC_BACKEND_DRIVER)).thenReturn(driver);
    Mockito.when(config.get(Configs.ENTITY_RELATIONAL_JDBC_BACKEND_USER)).thenReturn(user);
    Mockito.when(config.get(Configs.ENTITY_RELATIONAL_JDBC_BACKEND_PASSWORD)).thenReturn(password);
    Mockito.when(config.get(ENTITY_RELATIONAL_JDBC_BACKEND_MAX_CONNECTIONS)).thenReturn(10);
    Mockito.when(config.get(Configs.ENTITY_RELATIONAL_JDBC_BACKEND_WAIT_MILLISECONDS))
        .thenReturn(1000L);
    return config;
  }

  private void setCatalogVersion(String url, String user, String password, long version)
      throws Exception {
    try (Connection connection = DriverManager.getConnection(url, user, password);
        Statement statement = connection.createStatement()) {
      assertEquals(
          1,
          statement.executeUpdate(
              "UPDATE catalog_meta SET current_version = "
                  + version
                  + ", last_version = "
                  + version
                  + " WHERE catalog_id = 11"));
    }
  }

  private void hardDeleteCatalog(String url, String user, String password) throws Exception {
    try (Connection connection = DriverManager.getConnection(url, user, password);
        Statement statement = connection.createStatement()) {
      assertEquals(1, statement.executeUpdate("DELETE FROM catalog_meta WHERE catalog_id = 11"));
    }
  }

  private void initializeSchemaAndCatalog(String url, String user, String password)
      throws Exception {
    try (Connection connection = DriverManager.getConnection(url, user, password);
        Statement statement = connection.createStatement()) {
      executeSchemaScripts(statement, JDBCBackendType.fromURI(url));
      statement.execute(
          "INSERT INTO metalake_meta "
              + "(metalake_id, metalake_name, audit_info, schema_version, deleted_at)"
              + " VALUES (7, 'connection_test', '{}', '{}', 0)");
      statement.execute(
          "INSERT INTO catalog_meta "
              + "(catalog_id, catalog_name, metalake_id, type, provider, catalog_comment,"
              + " properties, audit_info, current_version, last_version, deleted_at) VALUES "
              + "(11, 'catalog', 7, 'RELATIONAL', 'jdbc-mysql', NULL,"
              + " '{\"jdbc-url\":\"jdbc:mysql://host/db\"}', '{}', 1, 1, 0)");
    }
  }

  private void executeSchemaScripts(Statement statement, JDBCBackendType backendType)
      throws Exception {
    String databaseType = backendType.name().toLowerCase(Locale.ROOT);
    String version = ConfigConstants.CURRENT_SCRIPT_VERSION;
    Path projectRoot = resolveProjectRoot();
    List<Path> schemaPaths =
        List.of(
            projectRoot
                .resolve("scripts")
                .resolve(databaseType)
                .resolve(String.format("schema-%s-%s.sql", version, databaseType)),
            projectRoot
                .resolve("scripts")
                .resolve("enterprise")
                .resolve(databaseType)
                .resolve(String.format("enterprise-schema-%s-%s.sql", version, databaseType)));

    for (Path schemaPath : schemaPaths) {
      for (String sql : loadStatements(schemaPath)) {
        statement.execute(sql);
      }
    }
  }

  private List<String> loadStatements(Path schemaPath) throws IOException {
    String executableSql =
        Files.readAllLines(schemaPath, StandardCharsets.UTF_8).stream()
            .map(String::trim)
            .filter(line -> !line.isEmpty())
            .filter(line -> !line.startsWith("--"))
            .reduce((left, right) -> left + "\n" + right)
            .orElse("");

    return Arrays.stream(executableSql.split(";"))
        .map(String::trim)
        .filter(sql -> !sql.isEmpty())
        .toList();
  }

  private Path resolveProjectRoot() {
    String rootDir = System.getenv("GRAVITINO_ROOT_DIR");
    if (StringUtils.isBlank(rootDir)) {
      rootDir = System.getenv("GRAVITINO_HOME");
    }
    if (StringUtils.isBlank(rootDir)) {
      throw new IllegalStateException("GRAVITINO_ROOT_DIR or GRAVITINO_HOME must be set");
    }
    return Path.of(rootDir);
  }
}
