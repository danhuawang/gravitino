/*
 * Copyright 2026 Datastrato Pvt Ltd.
 */
package com.datastrato.gravitino.catalog.connection;

import static org.apache.gravitino.Configs.ENTITY_RELATIONAL_JDBC_BACKEND_MAX_CONNECTIONS;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.datastrato.gravitino.catalog.connection.mapper.ConnectionTestResultMapper;
import com.datastrato.gravitino.catalog.connection.mapper.po.ConnectionTestResultPO;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.Statement;
import java.util.List;
import org.apache.gravitino.Config;
import org.apache.gravitino.Configs;
import org.apache.gravitino.NameIdentifier;
import org.apache.gravitino.storage.relational.session.SqlSessionFactoryHelper;
import org.apache.gravitino.storage.relational.utils.SessionUtils;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

class TestCatalogConnectionTestMetaService {
  private static final String JDBC_URL =
      "jdbc:h2:mem:catalog_connection_test;DB_CLOSE_DELAY=-1;MODE=MySQL";
  private static final String DRIVER = "org.h2.Driver";
  private static final NameIdentifier CATALOG = NameIdentifier.of("metalake", "catalog");

  private final CatalogConnectionTestMetaService service =
      CatalogConnectionTestMetaService.getInstance();

  @BeforeAll
  static void setUp() throws Exception {
    Config config = Mockito.mock(Config.class);
    Mockito.when(config.get(Configs.ENTITY_RELATIONAL_JDBC_BACKEND_URL)).thenReturn(JDBC_URL);
    Mockito.when(config.get(Configs.ENTITY_RELATIONAL_JDBC_BACKEND_DRIVER)).thenReturn(DRIVER);
    Mockito.when(config.get(Configs.ENTITY_RELATIONAL_JDBC_BACKEND_USER)).thenReturn("sa");
    Mockito.when(config.get(Configs.ENTITY_RELATIONAL_JDBC_BACKEND_PASSWORD)).thenReturn("");
    Mockito.when(config.get(ENTITY_RELATIONAL_JDBC_BACKEND_MAX_CONNECTIONS)).thenReturn(10);
    Mockito.when(config.get(Configs.ENTITY_RELATIONAL_JDBC_BACKEND_WAIT_MILLISECONDS))
        .thenReturn(1000L);

    try (Connection connection = DriverManager.getConnection(JDBC_URL, "sa", "");
        Statement statement = connection.createStatement()) {
      statement.execute(
          "CREATE TABLE metalake_meta ("
              + "metalake_id BIGINT NOT NULL PRIMARY KEY,"
              + "metalake_name VARCHAR(256) NOT NULL,"
              + "deleted_at BIGINT NOT NULL DEFAULT 0)");
      statement.execute(
          "CREATE TABLE catalog_meta ("
              + "catalog_id BIGINT NOT NULL PRIMARY KEY,"
              + "catalog_name VARCHAR(256) NOT NULL,"
              + "metalake_id BIGINT NOT NULL,"
              + "type VARCHAR(64) NOT NULL,"
              + "provider VARCHAR(256) NOT NULL,"
              + "catalog_comment VARCHAR(256),"
              + "properties VARCHAR(4096),"
              + "audit_info VARCHAR(4096) NOT NULL,"
              + "current_version BIGINT NOT NULL,"
              + "last_version BIGINT NOT NULL,"
              + "deleted_at BIGINT NOT NULL DEFAULT 0)");
      statement.execute(
          "CREATE TABLE catalog_connection_test_meta ("
              + "catalog_id BIGINT NOT NULL,"
              + "type VARCHAR(256) NOT NULL,"
              + "catalog_version INT NOT NULL,"
              + "test_status VARCHAR(16) NOT NULL,"
              + "last_tested_at BIGINT NOT NULL,"
              + "error_message VARCHAR(4096),"
              + "PRIMARY KEY (catalog_id, type))");
    }
    SqlSessionFactoryHelper.getInstance().init(config);
  }

  @AfterAll
  static void tearDown() {
    SqlSessionFactoryHelper.getInstance().close();
  }

  @BeforeEach
  void resetTables() throws Exception {
    try (Connection connection = DriverManager.getConnection(JDBC_URL, "sa", "");
        Statement statement = connection.createStatement()) {
      statement.execute("DELETE FROM catalog_connection_test_meta");
      statement.execute("DELETE FROM catalog_meta");
      statement.execute("DELETE FROM metalake_meta");
      statement.execute(
          "INSERT INTO metalake_meta (metalake_id, metalake_name, deleted_at)"
              + " VALUES (7, 'metalake', 0)");
      statement.execute(
          "INSERT INTO catalog_meta "
              + "(catalog_id, catalog_name, metalake_id, type, provider, catalog_comment,"
              + " properties, audit_info, current_version, last_version, deleted_at) VALUES "
              + "(11, 'catalog', 7, 'RELATIONAL', 'jdbc-mysql', NULL,"
              + " '{\"jdbc-url\":\"jdbc:mysql://host/db\"}', '{}', 1, 1, 0)");
    }
  }

  @Test
  void testMultipleTestTypesAndLatestResult() {
    CatalogConnectionSnapshot snapshot = service.loadCatalogConnectionSnapshot(CATALOG);
    assertEquals(11L, snapshot.catalogId());
    assertEquals(1L, snapshot.catalogVersion());

    assertTrue(
        service.recordTestResult(
            snapshot, ConnectionTestType.CATALOG, ConnectionTestResult.Status.PASSED, 1000L, null));
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

    List<ConnectionTestResultPO> stored =
        SessionUtils.getWithoutCommit(
            ConnectionTestResultMapper.class, mapper -> mapper.list(snapshot.catalogId()));
    assertEquals(3, stored.size());
    assertEquals("CATALOG", stored.get(0).getType());

    assertFalse(
        service.recordTestResult(
            snapshot,
            ConnectionTestType.CATALOG,
            ConnectionTestResult.Status.FAILED,
            999L,
            "Older failure"));
    ConnectionTestResult result =
        service.getValidTestResult(CATALOG, ConnectionTestType.CATALOG).orElseThrow();
    assertEquals(ConnectionTestResult.Status.PASSED, result.status());
    assertEquals(1000L, result.lastTestedAt());
    assertNull(result.errorMessage());

    assertTrue(
        service.recordTestResult(
            snapshot,
            ConnectionTestType.CATALOG,
            ConnectionTestResult.Status.FAILED,
            1000L,
            "Equal timestamp last commit wins"));
    result = service.getValidTestResult(CATALOG, ConnectionTestType.CATALOG).orElseThrow();
    assertEquals(ConnectionTestResult.Status.FAILED, result.status());
    assertEquals("Equal timestamp last commit wins", result.errorMessage());
  }

  @Test
  void testTruncatesLongErrorMessage() {
    CatalogConnectionSnapshot snapshot = service.loadCatalogConnectionSnapshot(CATALOG);
    String longErrorMessage = "x".repeat(4097);

    assertTrue(
        service.recordTestResult(
            snapshot,
            ConnectionTestType.CATALOG,
            ConnectionTestResult.Status.FAILED,
            1000L,
            longErrorMessage));

    ConnectionTestResult result =
        service.getValidTestResult(CATALOG, ConnectionTestType.CATALOG).orElseThrow();
    assertEquals(longErrorMessage.substring(0, 4096), result.errorMessage());
  }

  @Test
  void testVersionMismatchReconcileAndDeleteAll() throws Exception {
    CatalogConnectionSnapshot before = service.loadCatalogConnectionSnapshot(CATALOG);
    service.recordTestResult(
        before, ConnectionTestType.CATALOG, ConnectionTestResult.Status.PASSED, 1000L, null);
    service.recordTestResult(
        before,
        ConnectionTestType.credential("s3-token"),
        ConnectionTestResult.Status.PASSED,
        1000L,
        null);

    setCatalogVersion(2L);
    CatalogConnectionSnapshot after = service.loadCatalogConnectionSnapshot(CATALOG);
    assertTrue(service.getValidTestResult(CATALOG, ConnectionTestType.CATALOG).isEmpty());

    service.reconcileTestResultAfterCatalogChange(before, after, ConnectionTestType.CATALOG, true);
    assertTrue(service.getValidTestResult(CATALOG, ConnectionTestType.CATALOG).isPresent());
    assertTrue(
        service.getValidTestResult(CATALOG, ConnectionTestType.credential("s3-token")).isEmpty());

    service.reconcileTestResultAfterCatalogChange(after, after, ConnectionTestType.CATALOG, false);
    assertTrue(service.getValidTestResult(CATALOG, ConnectionTestType.CATALOG).isPresent());

    setCatalogVersion(3L);
    CatalogConnectionSnapshot third = service.loadCatalogConnectionSnapshot(CATALOG);
    service.reconcileTestResultAfterCatalogChange(after, third, ConnectionTestType.CATALOG, false);
    assertTrue(service.getValidTestResult(CATALOG, ConnectionTestType.CATALOG).isEmpty());

    List<ConnectionTestResultPO> stored =
        SessionUtils.getWithoutCommit(
            ConnectionTestResultMapper.class, mapper -> mapper.list(before.catalogId()));
    assertEquals(1, stored.size());
    assertEquals("CREDENTIAL:s3-token", stored.get(0).getType());
  }

  @Test
  void testStaleProbeAndInputConstraints() throws Exception {
    CatalogConnectionSnapshot before = service.loadCatalogConnectionSnapshot(CATALOG);
    setCatalogVersion(2L);
    assertFalse(
        service.recordTestResult(
            before, ConnectionTestType.CATALOG, ConnectionTestResult.Status.PASSED, 1000L, null));

    CatalogConnectionSnapshot current = service.loadCatalogConnectionSnapshot(CATALOG);
    assertThrows(
        IllegalArgumentException.class,
        () ->
            service.recordTestResult(
                current, "credential:S3", ConnectionTestResult.Status.PASSED, 1000L, null));
    assertThrows(
        IllegalArgumentException.class,
        () ->
            service.recordTestResult(
                current,
                ConnectionTestType.CATALOG,
                ConnectionTestResult.Status.FAILED,
                1000L,
                ""));
    assertThrows(
        IllegalArgumentException.class,
        () ->
            service.recordTestResult(
                current,
                ConnectionTestType.CATALOG,
                ConnectionTestResult.Status.PASSED,
                1000L,
                "Unexpected error"));
  }

  private void setCatalogVersion(long version) throws Exception {
    try (Connection connection = DriverManager.getConnection(JDBC_URL, "sa", "");
        Statement statement = connection.createStatement()) {
      statement.execute(
          "UPDATE catalog_meta SET current_version = "
              + version
              + ", last_version = "
              + version
              + " WHERE catalog_id = 11");
    }
  }
}
