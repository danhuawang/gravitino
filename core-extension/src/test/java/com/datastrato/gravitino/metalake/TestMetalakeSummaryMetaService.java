/*
 * Copyright 2026 Datastrato Inc.
 */
package com.datastrato.gravitino.metalake;

import static org.apache.gravitino.Configs.ENTITY_RELATIONAL_JDBC_BACKEND_MAX_CONNECTIONS;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.Statement;
import org.apache.gravitino.Config;
import org.apache.gravitino.Configs;
import org.apache.gravitino.exceptions.NoSuchMetalakeException;
import org.apache.gravitino.storage.relational.session.SqlSessionFactoryHelper;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

class TestMetalakeSummaryMetaService {
  private static final String JDBC_URL =
      "jdbc:h2:mem:metalake_summary_test;DB_CLOSE_DELAY=-1;MODE=MySQL";
  private static final String DRIVER = "org.h2.Driver";

  private final MetalakeSummaryMetaService service = MetalakeSummaryMetaService.getInstance();

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
              + "properties VARCHAR(1024),"
              + "deleted_at BIGINT NOT NULL DEFAULT 0)");
      statement.execute(
          "CREATE TABLE catalog_meta ("
              + "catalog_id BIGINT NOT NULL PRIMARY KEY,"
              + "metalake_id BIGINT NOT NULL,"
              + "deleted_at BIGINT NOT NULL DEFAULT 0)");
      statement.execute(
          "CREATE TABLE user_meta ("
              + "user_id BIGINT NOT NULL PRIMARY KEY,"
              + "metalake_id BIGINT NOT NULL,"
              + "deleted_at BIGINT NOT NULL DEFAULT 0)");
      statement.execute(
          "CREATE TABLE role_meta ("
              + "role_id BIGINT NOT NULL PRIMARY KEY,"
              + "metalake_id BIGINT NOT NULL,"
              + "deleted_at BIGINT NOT NULL DEFAULT 0)");
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
      statement.execute("DELETE FROM catalog_meta");
      statement.execute("DELETE FROM user_meta");
      statement.execute("DELETE FROM role_meta");
      statement.execute("DELETE FROM metalake_meta");
      statement.execute(
          "INSERT INTO metalake_meta"
              + " (metalake_id, metalake_name, properties, deleted_at) VALUES"
              + " (1, 'active', '{\"in-use\":\"true\"}', 0),"
              + " (2, 'disabled', '{\"in-use\":\"false\"}', 0),"
              + " (3, 'empty', '{\"in-use\":\"false\"}', 0),"
              + " (4, 'deleted', '{\"in-use\":\"false\"}', 1),"
              + " (5, 'disabled', '{\"in-use\":\"false\"}', 2)");
      statement.execute(
          "INSERT INTO catalog_meta (catalog_id, metalake_id, deleted_at) VALUES"
              + " (11, 1, 0), (12, 1, 0), (13, 1, 1),"
              + " (14, 2, 0), (15, 2, 1), (16, 4, 0), (17, 5, 0)");
      statement.execute(
          "INSERT INTO user_meta (user_id, metalake_id, deleted_at) VALUES"
              + " (21, 1, 0), (22, 1, 0), (23, 1, 0), (24, 1, 1),"
              + " (25, 2, 0), (26, 2, 0), (27, 2, 1), (28, 4, 0), (29, 5, 0)");
      statement.execute(
          "INSERT INTO role_meta (role_id, metalake_id, deleted_at) VALUES"
              + " (31, 1, 0), (32, 1, 1), (33, 2, 0), (34, 2, 0),"
              + " (35, 2, 1), (36, 4, 0), (37, 5, 0)");
    }
  }

  @Test
  void testLoadsActiveEntityCounts() {
    MetalakeSummaryCounts counts = service.loadCounts("active");

    assertEquals(2L, counts.catalogCount());
    assertEquals(3L, counts.userCount());
    assertEquals(1L, counts.roleCount());
  }

  @Test
  void testLoadsDisabledMetalakeCountsWithoutIncludingDeletedRows() {
    MetalakeSummaryCounts counts = service.loadCounts("disabled");

    assertEquals(1L, counts.catalogCount());
    assertEquals(2L, counts.userCount());
    assertEquals(2L, counts.roleCount());
  }

  @Test
  void testLoadsZeroCountsForEmptyMetalake() {
    MetalakeSummaryCounts counts = service.loadCounts("empty");

    assertEquals(0L, counts.catalogCount());
    assertEquals(0L, counts.userCount());
    assertEquals(0L, counts.roleCount());
  }

  @Test
  void testRejectsMissingOrDeletedMetalake() {
    assertThrows(NoSuchMetalakeException.class, () -> service.loadCounts("missing"));
    assertThrows(NoSuchMetalakeException.class, () -> service.loadCounts("deleted"));
  }

  @Test
  void testRejectsBlankMetalakeName() {
    assertThrows(IllegalArgumentException.class, () -> service.loadCounts(null));
    assertThrows(IllegalArgumentException.class, () -> service.loadCounts(" "));
  }
}
