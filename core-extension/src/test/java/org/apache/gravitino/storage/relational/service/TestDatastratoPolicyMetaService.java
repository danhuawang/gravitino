/*
 * Copyright 2026 Datastrato Inc.
 */
package org.apache.gravitino.storage.relational.service;

import static org.apache.gravitino.Configs.ENTITY_RELATIONAL_JDBC_BACKEND_MAX_CONNECTIONS;
import static org.junit.jupiter.api.Assertions.assertEquals;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.Statement;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;
import org.apache.gravitino.Config;
import org.apache.gravitino.Configs;
import org.apache.gravitino.MetadataObject;
import org.apache.gravitino.storage.relational.session.SqlSessionFactoryHelper;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

public class TestDatastratoPolicyMetaService {

  private static final String JDBC_URL = "jdbc:h2:mem:policytest;DB_CLOSE_DELAY=-1;MODE=MySQL";
  private static final String DRIVER = "org.h2.Driver";

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

    try (Connection conn = DriverManager.getConnection(JDBC_URL, "sa", "");
        Statement stmt = conn.createStatement()) {
      stmt.execute(
          "CREATE TABLE IF NOT EXISTS metalake_meta ("
              + "metalake_id BIGINT NOT NULL PRIMARY KEY,"
              + "metalake_name VARCHAR(64) NOT NULL,"
              + "deleted_at BIGINT NOT NULL DEFAULT 0)");

      stmt.execute(
          "CREATE TABLE IF NOT EXISTS policy_meta ("
              + "policy_id BIGINT NOT NULL PRIMARY KEY,"
              + "policy_name VARCHAR(64) NOT NULL,"
              + "policy_type VARCHAR(64) NOT NULL DEFAULT 'custom',"
              + "metalake_id BIGINT NOT NULL,"
              + "audit_info VARCHAR(1024) NOT NULL DEFAULT '{}',"
              + "current_version BIGINT NOT NULL DEFAULT 1,"
              + "last_version BIGINT NOT NULL DEFAULT 1,"
              + "deleted_at BIGINT NOT NULL DEFAULT 0)");

      stmt.execute(
          "CREATE TABLE IF NOT EXISTS policy_relation_meta ("
              + "id BIGINT AUTO_INCREMENT PRIMARY KEY,"
              + "policy_id BIGINT NOT NULL,"
              + "metadata_object_id BIGINT NOT NULL,"
              + "metadata_object_type VARCHAR(64) NOT NULL,"
              + "deleted_at BIGINT NOT NULL DEFAULT 0)");
    }
    SqlSessionFactoryHelper.getInstance().init(config);
  }

  @AfterAll
  static void tearDown() {
    SqlSessionFactoryHelper.getInstance().close();
  }

  @BeforeEach
  void cleanTables() throws Exception {
    try (Connection conn = DriverManager.getConnection(JDBC_URL, "sa", "");
        Statement stmt = conn.createStatement()) {
      stmt.execute("DELETE FROM policy_relation_meta");
      stmt.execute("DELETE FROM policy_meta");
      stmt.execute("DELETE FROM metalake_meta");
    }
  }

  @Test
  public void testListAssociatedMetadataObjectsForPolicies() throws Exception {
    try (Connection conn = DriverManager.getConnection(JDBC_URL, "sa", "");
        Statement stmt = conn.createStatement()) {
      // metalake 1
      stmt.execute(
          "INSERT INTO metalake_meta (metalake_id, metalake_name, deleted_at) VALUES (1, 'metalake1', 0)");
      // policies for metalake 1
      stmt.execute(
          "INSERT INTO policy_meta (policy_id, policy_name, metalake_id, deleted_at) VALUES (10, 'p1', 1, 0)");
      stmt.execute(
          "INSERT INTO policy_meta (policy_id, policy_name, metalake_id, deleted_at) VALUES (20, 'p2', 1, 0)");
      stmt.execute(
          "INSERT INTO policy_meta (policy_id, policy_name, metalake_id, deleted_at) VALUES (30, 'p3_deleted', 1, 100)");

      // Policy 10 has two active objects, one deleted object, and one deleted relation.
      stmt.execute(
          "INSERT INTO policy_relation_meta (policy_id, metadata_object_id, metadata_object_type, deleted_at) VALUES (10, 10, 'POLICY', 0)");
      stmt.execute(
          "INSERT INTO policy_relation_meta (policy_id, metadata_object_id, metadata_object_type, deleted_at) VALUES (10, 20, 'POLICY', 0)");
      stmt.execute(
          "INSERT INTO policy_relation_meta (policy_id, metadata_object_id, metadata_object_type, deleted_at) VALUES (10, 30, 'POLICY', 0)");
      stmt.execute(
          "INSERT INTO policy_relation_meta (policy_id, metadata_object_id, metadata_object_type, deleted_at) VALUES (10, 20, 'POLICY', 50)");

      // policy 20 has 1 active association
      stmt.execute(
          "INSERT INTO policy_relation_meta (policy_id, metadata_object_id, metadata_object_type, deleted_at) VALUES (20, 10, 'POLICY', 0)");

      // policy 30 (deleted) has associations
      stmt.execute(
          "INSERT INTO policy_relation_meta (policy_id, metadata_object_id, metadata_object_type, deleted_at) VALUES (30, 10, 'POLICY', 0)");
    }

    DatastratoPolicyMetaService service = DatastratoPolicyMetaService.getInstance();
    Map<Long, List<MetadataObject>> objects =
        service.listAssociatedMetadataObjectsForPolicies("metalake1", List.of(10L, 20L, 30L));

    assertEquals(2, objects.size());
    assertEquals(Set.of("p1", "p2"), objectNames(objects.get(10L)));
    assertEquals(Set.of("p1"), objectNames(objects.get(20L)));

    Map<Long, List<MetadataObject>> selected =
        service.listAssociatedMetadataObjectsForPolicies("metalake1", List.of(20L));
    assertEquals(Set.of(20L), selected.keySet());
    assertEquals(Set.of("p1"), objectNames(selected.get(20L)));

    // metalake with no policies
    Map<Long, List<MetadataObject>> nonExistent =
        service.listAssociatedMetadataObjectsForPolicies("non_existent", List.of(10L));
    assertEquals(0, nonExistent.size());

    assertEquals(
        0, service.listAssociatedMetadataObjectsForPolicies("metalake1", List.of()).size());
  }

  private Set<String> objectNames(List<MetadataObject> objects) {
    return objects.stream().map(MetadataObject::name).collect(Collectors.toSet());
  }
}
