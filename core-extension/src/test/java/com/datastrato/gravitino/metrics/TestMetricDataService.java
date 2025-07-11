/*
 * Copyright 2024 Datastrato Pvt Ltd.
 * This software is licensed under the Apache License version 2.
 */
package com.datastrato.gravitino.metrics;

import static com.datastrato.gravitino.storage.relational.service.MetricDataService.Metric.ASSET_COUNT;
import static org.apache.gravitino.Configs.ENTITY_RELATIONAL_JDBC_BACKEND_MAX_CONNECTIONS;
import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.datastrato.gravitino.dto.metrics.MetricDTO;
import com.datastrato.gravitino.storage.relational.MetricPO;
import com.datastrato.gravitino.storage.relational.service.MetricDataService;
import com.google.common.collect.Lists;
import java.io.File;
import java.nio.charset.StandardCharsets;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.sql.Statement;
import java.sql.Timestamp;
import java.util.List;
import org.apache.commons.io.FileUtils;
import org.apache.gravitino.Config;
import org.apache.gravitino.Configs;
import org.apache.gravitino.config.ConfigConstants;
import org.apache.gravitino.storage.relational.mapper.MetalakeMetaMapper;
import org.apache.gravitino.storage.relational.mapper.OwnerMetaMapper;
import org.apache.gravitino.storage.relational.session.SqlSessionFactoryHelper;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

class TestMetricDataService {
  private static final Config config = mock(Config.class);
  private static final String driver = "org.h2.Driver";
  private static final String jdbcUrl = "jdbc:h2:mem:testdb;DB_CLOSE_DELAY=-1;MODE=MySQL";
  private static final String jdbcUser = "sa";
  private static final String jdbcPassword = "";
  private static final String metalakeName1 = "test_metalake1";
  private static final String metalakeName2 = "test_metalake2";
  private static final String metalakeName3 = "test_metalake3";

  private static MetricDataService service;

  @BeforeAll
  public static void setUp() throws Exception {
    when(config.get(Configs.ENTITY_RELATIONAL_JDBC_BACKEND_URL)).thenReturn(jdbcUrl);
    when(config.get(Configs.ENTITY_RELATIONAL_JDBC_BACKEND_DRIVER)).thenReturn(driver);
    when(config.get(Configs.ENTITY_RELATIONAL_JDBC_BACKEND_USER)).thenReturn(jdbcUser);
    when(config.get(Configs.ENTITY_RELATIONAL_JDBC_BACKEND_PASSWORD)).thenReturn(jdbcPassword);
    when(config.get(ENTITY_RELATIONAL_JDBC_BACKEND_MAX_CONNECTIONS)).thenReturn(100);
    when(config.get(Configs.ENTITY_RELATIONAL_JDBC_BACKEND_WAIT_MILLISECONDS)).thenReturn(1000L);

    initTables();
    SqlSessionFactoryHelper.getInstance().init(config);
    service = new MetricDataService(false);
  }

  @Test
  void testInsertAndQueryMetrics() {
    String user = "u1";
    MetricPO assetCount =
        MetricPO.builder().withMetricName(ASSET_COUNT.getName()).withMetricValue(1.0).build();
    List<MetricPO> metrics = Lists.newArrayList(assetCount);

    service.insertMetrics(metalakeName1, user, metrics);
    MetricDTO[] result =
        service.getMetricsByNameAndTimestamp(
            metalakeName1, user, new String[0], 1, System.currentTimeMillis() + 2_000);

    assertNotNull(result);
    assertEquals(metrics.size(), result.length);
    assertEquals(assetCount.getMetricName(), result[0].name());
    assertEquals(assetCount.getMetricValue(), result[0].values()[0]);
    assertTrue(result[0].timestamps()[0] > 0);
  }

  @Test
  void testCleanMetricsByTimestamp() {
    String user = "u1";
    long now = System.currentTimeMillis();
    MetricPO assetCount =
        MetricPO.builder()
            .withMetricName(ASSET_COUNT.getName())
            .withMetricValue(1.0)
            .withCreatedTime(new Timestamp(now - 10_000))
            .build();
    service.insertMetrics(metalakeName2, user, Lists.newArrayList(assetCount));
    MetricDTO[] result =
        service.getMetricsByNameAndTimestamp(metalakeName2, user, new String[0], 0, now);
    assertNotNull(result);
    assertEquals(1, result.length);

    service.cleanMetricsByTimestamp(now - 5_000);
    MetricDTO[] cleanedResult =
        service.getMetricsByNameAndTimestamp(metalakeName2, user, new String[0], 0, now);
    assertNotNull(cleanedResult);
    assertEquals(0, cleanedResult.length, "Metrics should be cleaned up");
  }

  @Test
  void testCleanInvalidMetrics() throws SQLException {
    String user = "u1";
    MetricPO assetCount =
        MetricPO.builder().withMetricName(ASSET_COUNT.getName()).withMetricValue(1.0).build();
    List<MetricPO> metrics = Lists.newArrayList(assetCount);

    service.insertMetrics(metalakeName3, user, metrics);
    MetricDTO[] result =
        service.getMetricsByNameAndTimestamp(
            metalakeName3, user, new String[0], 0, System.currentTimeMillis() + 2_000);
    assertNotNull(result);
    assertEquals(metrics.size(), result.length);

    // update the metalake id to an invalid one
    try (Connection conn = DriverManager.getConnection(jdbcUrl, jdbcUser, jdbcPassword)) {
      Statement stmt = conn.createStatement();
      stmt.executeUpdate(
          "UPDATE "
              + MetalakeMetaMapper.TABLE_NAME
              + " SET metalake_id = 9999 WHERE metalake_name = '"
              + metalakeName3
              + "';");
    }
    // Now clean invalid metrics
    assertDoesNotThrow(() -> service.cleanInvalidMetrics());
    // Query again to ensure the metrics are cleaned
    MetricDTO[] cleanedResult =
        service.getMetricsByNameAndTimestamp(
            metalakeName3, user, new String[0], 0, System.currentTimeMillis() + 2_000);
    assertNotNull(cleanedResult);
    assertEquals(0, cleanedResult.length, "Metrics should be cleaned up");
  }

  @Test
  void testGetAssetWithOwnerCount() throws SQLException {
    long assetWithOwnerCount = service.getAssetWithOwnerCount(metalakeName1);
    assertEquals(0, assetWithOwnerCount);

    String ownerRecord1 =
        String.format("(%d, %d, '%s', %d, '%s', '%s')", 1, 1, "", 1, "CATALOG", "");
    String insertOwnerSql =
        "INSERT INTO "
            + OwnerMetaMapper.OWNER_TABLE_NAME
            + " (metalake_id, owner_id, owner_type, metadata_object_id,"
            + " metadata_object_type, audit_info) VALUES "
            + ownerRecord1
            + ";";
    try (Connection conn = DriverManager.getConnection(jdbcUrl, jdbcUser, jdbcPassword)) {
      Statement stmt = conn.createStatement();
      stmt.executeUpdate(insertOwnerSql);
    }

    assetWithOwnerCount = service.getAssetWithOwnerCount(metalakeName1);
    assertEquals(1, assetWithOwnerCount);
  }

  private static void initTables() throws Exception {
    String gravitinoHome = System.getenv("GRAVITINO_HOME");
    String createTableSqls =
        FileUtils.readFileToString(
                new File(
                    gravitinoHome
                        + "/scripts/h2/schema-"
                        + ConfigConstants.CURRENT_SCRIPT_VERSION
                        + "-h2.sql"),
                StandardCharsets.UTF_8)
            + "\n"
            + FileUtils.readFileToString(
                new File(
                    gravitinoHome
                        + "/gravitino-oss"
                        + "/scripts/h2/schema-"
                        + ConfigConstants.CURRENT_SCRIPT_VERSION
                        + "-h2.sql"),
                StandardCharsets.UTF_8);

    String auditInfo = "{\"creator\":\"test\",\"createTime\":\"2024-01-01T00:00:00Z\"}";
    String schemaVersion = "{\"majorVersion\":\"0\",\"minorVersion\":\"1\"}";
    String record1 =
        String.format("(%d, '%s', '%s', '%s', 0)", 1, metalakeName1, auditInfo, schemaVersion);
    String record2 =
        String.format("(%d, '%s', '%s', '%s', 0)", 2, metalakeName2, auditInfo, schemaVersion);
    String record3 =
        String.format("(%d, '%s', '%s', '%s', 0)", 3, metalakeName3, auditInfo, schemaVersion);
    String insertMetalakeSql =
        "INSERT INTO "
            + MetalakeMetaMapper.TABLE_NAME
            + " (metalake_id, metalake_name, audit_info, schema_version, deleted_at) "
            + "VALUES "
            + String.join(", ", record1, record2, record3)
            + ";";

    try (Connection conn = DriverManager.getConnection(jdbcUrl, jdbcUser, jdbcPassword)) {
      Statement stmt = conn.createStatement();
      for (String ddl : createTableSqls.split(";")) {
        if (ddl.trim().isEmpty()) continue;
        stmt.execute(ddl);
      }

      stmt.executeUpdate(insertMetalakeSql);
    }
  }
}
