/*
 * Copyright 2024 Datastrato Inc.
 */
package com.datastrato.gravitino.metrics.storage.relational.service;

import static com.datastrato.gravitino.metrics.storage.relational.service.MetricDataService.Metric.ASSET_COUNT;
import static org.apache.gravitino.Configs.ENTITY_RELATIONAL_JDBC_BACKEND_MAX_CONNECTIONS;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.datastrato.gravitino.metrics.AssetNode;
import com.datastrato.gravitino.metrics.MetalakeSnapshot;
import com.datastrato.gravitino.metrics.MetricsCollector;
import com.datastrato.gravitino.metrics.dto.MetricDTO;
import com.datastrato.gravitino.metrics.dto.MetricState;
import com.datastrato.gravitino.metrics.storage.relational.MetricDirtyPO;
import com.datastrato.gravitino.metrics.storage.relational.MetricPO;
import com.google.common.collect.Lists;
import java.io.File;
import java.nio.charset.StandardCharsets;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.sql.Timestamp;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.apache.commons.io.FileUtils;
import org.apache.gravitino.Config;
import org.apache.gravitino.Configs;
import org.apache.gravitino.config.ConfigConstants;
import org.apache.gravitino.storage.relational.mapper.MetalakeMetaMapper;
import org.apache.gravitino.storage.relational.session.SqlSessionFactoryHelper;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

class TestMetricDataService {
  private static final Config config = Mockito.mock(Config.class);
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
    Mockito.when(config.get(Configs.ENTITY_RELATIONAL_JDBC_BACKEND_URL)).thenReturn(jdbcUrl);
    Mockito.when(config.get(Configs.ENTITY_RELATIONAL_JDBC_BACKEND_DRIVER)).thenReturn(driver);
    Mockito.when(config.get(Configs.ENTITY_RELATIONAL_JDBC_BACKEND_USER)).thenReturn(jdbcUser);
    Mockito.when(config.get(Configs.ENTITY_RELATIONAL_JDBC_BACKEND_PASSWORD))
        .thenReturn(jdbcPassword);
    Mockito.when(config.get(ENTITY_RELATIONAL_JDBC_BACKEND_MAX_CONNECTIONS)).thenReturn(100);
    Mockito.when(config.get(Configs.ENTITY_RELATIONAL_JDBC_BACKEND_WAIT_MILLISECONDS))
        .thenReturn(1000L);

    initTables();
    SqlSessionFactoryHelper.getInstance().init(config);
    service = MetricDataService.getInstance();
    service.initialize(false);

    MetricsCollector metricsCollector = Mockito.mock(MetricsCollector.class);
    Mockito.when(metricsCollector.getMetalakeSnapshots())
        .thenAnswer(
            i -> {
              Map<String, MetalakeSnapshot> snapshots = new HashMap<>();
              MetalakeSnapshot snapshot1 = Mockito.mock(MetalakeSnapshot.class);
              Mockito.when(snapshot1.getAssetTreeRoot()).thenReturn(Mockito.mock(AssetNode.class));
              Mockito.when(snapshot1.getAssetTreeRoot().getId()).thenReturn(1L);
              snapshots.put(metalakeName1, snapshot1);

              MetalakeSnapshot snapshot2 = Mockito.mock(MetalakeSnapshot.class);
              Mockito.when(snapshot2.getAssetTreeRoot()).thenReturn(Mockito.mock(AssetNode.class));
              Mockito.when(snapshot2.getAssetTreeRoot().getId()).thenReturn(2L);
              snapshots.put(metalakeName2, snapshot2);

              MetalakeSnapshot snapshot3 = Mockito.mock(MetalakeSnapshot.class);
              Mockito.when(snapshot3.getAssetTreeRoot()).thenReturn(Mockito.mock(AssetNode.class));
              Mockito.when(snapshot3.getAssetTreeRoot().getId()).thenReturn(3L);
              snapshots.put(metalakeName3, snapshot3);
              return snapshots;
            });
    service.setMetricsCollector(metricsCollector);
  }

  @BeforeEach
  void cleanDashboardMetricTables() throws SQLException {
    try (Connection conn = DriverManager.getConnection(jdbcUrl, jdbcUser, jdbcPassword);
        Statement statement = conn.createStatement()) {
      statement.executeUpdate("DELETE FROM dashboard_metric_dirty");
      statement.executeUpdate("DELETE FROM dashboard_metric_current");
      statement.executeUpdate("DELETE FROM dashboard_metrics");
    }
  }

  @Test
  void testInsertAndQueryMetrics() {
    String user = "u1";
    MetricPO assetCount =
        MetricPO.builder().withMetricName(ASSET_COUNT.getName()).withMetricValue(1.0).build();
    List<MetricPO> metrics = Lists.newArrayList(assetCount);

    service.insertMetrics(1L, MetricsCollector.MOCK_USER_ID_FOR_DISABLE_AUTHZ, metrics);
    MetricDTO[] result =
        service.getMetricsByNameAndTimestamp(
            metalakeName1, user, new String[0], 1, System.currentTimeMillis() + 2_000);

    Assertions.assertNotNull(result);
    Assertions.assertEquals(metrics.size(), result.length);
    assertEquals(assetCount.getMetricName(), result[0].name());
    assertEquals(assetCount.getMetricValue(), result[0].values()[0]);
    Assertions.assertTrue(result[0].timestamps()[0] > 0);
    assertEquals(MetricState.COMPLETE, result[0].states()[0]);
    assertNull(result[0].messages()[0]);
  }

  @Test
  void testUnavailableMetricRoundTripAndAtomicDirtyMarker() {
    long timestamp = System.currentTimeMillis() + 5_000;
    MetricPO unavailable =
        MetricPO.builder()
            .withMetricName("by_catalog::failed::asset_count")
            .withMetricValue(null)
            .withMetricState(MetricState.UNAVAILABLE)
            .withMetricMessage("Metric data is temporarily unavailable.")
            .build();

    service.replaceCurrentMetricsAndMarkDirty(
        1L,
        Collections.singletonMap(
            MetricsCollector.MOCK_USER_ID_FOR_DISABLE_AUTHZ,
            Collections.singletonList(unavailable)),
        timestamp,
        timestamp);

    MetricDTO[] result =
        service.getMetricsByNameAndTimestamp(
            metalakeName1,
            "u1",
            new String[] {"by_catalog::failed::asset_count"},
            timestamp,
            timestamp);
    assertEquals(1, result.length);
    assertNull(result[0].values()[0]);
    assertEquals(MetricState.UNAVAILABLE, result[0].states()[0]);
    assertEquals("Metric data is temporarily unavailable.", result[0].messages()[0]);
    assertEquals(result[0].values().length, result[0].timestamps().length);
    assertEquals(result[0].values().length, result[0].states().length);
    assertEquals(result[0].values().length, result[0].messages().length);
    assertTrue(service.getDirtyMetalake(1L) != null);
  }

  @Test
  void testEnabledPolicyRelationsUseOnlyTheCurrentPolicyVersion() throws SQLException {
    try (Connection conn = DriverManager.getConnection(jdbcUrl, jdbcUser, jdbcPassword);
        Statement statement = conn.createStatement()) {
      statement.executeUpdate(
          "INSERT INTO policy_meta"
              + " (policy_id, policy_name, policy_type, metalake_id, audit_info,"
              + " current_version, last_version, deleted_at) VALUES"
              + " (1001, 'enabled_policy', 'custom', 1, '{}', 2, 2, 0),"
              + " (1002, 'disabled_policy', 'custom', 1, '{}', 2, 2, 0)");
      statement.executeUpdate(
          "INSERT INTO policy_version_info"
              + " (metalake_id, policy_id, version, enabled, deleted_at) VALUES"
              + " (1, 1001, 1, FALSE, 0), (1, 1001, 2, TRUE, 0),"
              + " (1, 1002, 1, TRUE, 0), (1, 1002, 2, FALSE, 0)");
      statement.executeUpdate(
          "INSERT INTO policy_relation_meta"
              + " (policy_id, metadata_object_id, metadata_object_type, audit_info, deleted_at)"
              + " VALUES (1001, 7001, 'SCHEMA', '{}', 0), (1002, 7002, 'TABLE', '{}', 0)");

      assertEquals(Set.of(7001L), service.listEnabledPolicyMetadataObjectIdsByMetalakeId(1L));

      statement.executeUpdate("DELETE FROM policy_relation_meta WHERE policy_id IN (1001, 1002)");
      statement.executeUpdate("DELETE FROM policy_version_info WHERE policy_id IN (1001, 1002)");
      statement.executeUpdate("DELETE FROM policy_meta WHERE policy_id IN (1001, 1002)");
    }
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
    service.insertMetrics(
        2L, MetricsCollector.MOCK_USER_ID_FOR_DISABLE_AUTHZ, Lists.newArrayList(assetCount));
    MetricDTO[] result =
        service.getMetricsByNameAndTimestamp(metalakeName2, user, new String[0], 0, now);
    Assertions.assertNotNull(result);
    Assertions.assertEquals(1, result.length);

    service.cleanMetricsByTimestamp(now - 5_000);
    MetricDTO[] cleanedResult =
        service.getMetricsByNameAndTimestamp(metalakeName2, user, new String[0], 0, now);
    Assertions.assertNotNull(cleanedResult);
    Assertions.assertEquals(0, cleanedResult.length, "Metrics should be cleaned up");
  }

  @Test
  void testDirtyMarkerRevisionDebounceAndRetry() {
    long firstEvent = System.currentTimeMillis() - 2_000;
    service.markMetalakeDirty(1L, firstEvent);

    MetricDirtyPO firstDirty = service.getDirtyMetalake(1L);
    assertEquals(1L, firstDirty.getRevision());
    assertEquals(firstEvent, firstDirty.getFirstDirtyAt().getTime());
    assertEquals(firstEvent, firstDirty.getLastEventAt().getTime());
    assertEquals(0, firstDirty.getRetryCount());
    assertNull(firstDirty.getRetryAfter());

    assertTrue(
        service.markRetryIfRevision(
            1L, firstDirty.getRevision(), 1, System.currentTimeMillis() - 1, "failed"));
    assertEquals(
        1,
        service
            .listDueDirtyMetalakes(
                System.currentTimeMillis(), System.currentTimeMillis(), System.currentTimeMillis())
            .stream()
            .filter(dirty -> dirty.getMetalakeId() == 1L)
            .count());

    long secondEvent = System.currentTimeMillis();
    service.markMetalakeDirty(1L, secondEvent);
    MetricDirtyPO secondDirty = service.getDirtyMetalake(1L);
    assertEquals(2L, secondDirty.getRevision());
    assertEquals(firstEvent, secondDirty.getFirstDirtyAt().getTime());
    assertEquals(secondEvent, secondDirty.getLastEventAt().getTime());
    assertEquals(0, secondDirty.getRetryCount());
    assertNull(secondDirty.getRetryAfter());
    assertNull(secondDirty.getLastError());

    service.markMetalakeDirty(1L, firstEvent);
    MetricDirtyPO outOfOrderDirty = service.getDirtyMetalake(1L);
    assertEquals(3L, outOfOrderDirty.getRevision());
    assertEquals(secondEvent, outOfOrderDirty.getLastEventAt().getTime());

    assertFalse(service.deleteDirtyIfRevision(1L, firstDirty.getRevision()));
    assertFalse(service.deleteDirtyIfRevision(1L, secondDirty.getRevision()));
    assertTrue(service.deleteDirtyIfRevision(1L, outOfOrderDirty.getRevision()));
    assertNull(service.getDirtyMetalake(1L));
  }

  @Test
  void testOutOfOrderEventsPreserveEarliestAndLatestTimes() {
    long latestEvent = System.currentTimeMillis();
    long earliestEvent = latestEvent - 5_000L;
    service.markMetalakeDirty(2L, latestEvent);
    service.markMetalakeDirty(2L, earliestEvent);

    MetricDirtyPO dirty = service.getDirtyMetalake(2L);
    assertEquals(2L, dirty.getRevision());
    assertEquals(earliestEvent, dirty.getFirstDirtyAt().getTime());
    assertEquals(latestEvent, dirty.getLastEventAt().getTime());
  }

  @Test
  void testDueQueryHonorsQuietPeriodMaximumDebounceAndRetryTime() {
    long now = System.currentTimeMillis();
    service.markMetalakeDirty(1L, now - 6_000L);
    service.markMetalakeDirty(1L, now);
    service.markMetalakeDirty(2L, now);

    List<MetricDirtyPO> due = service.listDueDirtyMetalakes(now - 1_000L, now - 5_000L, now);
    assertEquals(1, due.size());
    assertEquals(1L, due.get(0).getMetalakeId());

    MetricDirtyPO secondDirty = service.getDirtyMetalake(2L);
    assertTrue(
        service.markRetryIfRevision(2L, secondDirty.getRevision(), 1, now + 2_000L, "retry"));
    assertTrue(
        service.listDueDirtyMetalakes(now + 1_000L, now + 1_000L, now + 1_000L).stream()
            .noneMatch(dirty -> dirty.getMetalakeId() == 2L));
    assertTrue(
        service.listDueDirtyMetalakes(now + 2_000L, now + 2_000L, now + 2_000L).stream()
            .anyMatch(dirty -> dirty.getMetalakeId() == 2L));
  }

  @Test
  void testReplaceCurrentMetricsRemovesStaleRows() {
    long firstTimestamp = System.currentTimeMillis() + 10_000;
    MetricPO assetCount =
        MetricPO.builder().withMetricName(ASSET_COUNT.getName()).withMetricValue(1.0).build();
    MetricPO catalogCount =
        MetricPO.builder().withMetricName("catalog_count").withMetricValue(2.0).build();
    service.replaceCurrentMetrics(
        2L,
        Collections.singletonMap(
            MetricsCollector.MOCK_USER_ID_FOR_DISABLE_AUTHZ,
            Lists.newArrayList(assetCount, catalogCount)),
        firstTimestamp);

    MetricDTO[] firstResult =
        service.getMetricsByNameAndTimestamp(
            metalakeName2, "u1", new String[0], firstTimestamp, firstTimestamp);
    assertEquals(2, firstResult.length);

    long secondTimestamp = firstTimestamp + 1;
    MetricPO updatedAssetCount =
        MetricPO.builder().withMetricName(ASSET_COUNT.getName()).withMetricValue(3.0).build();
    service.replaceCurrentMetrics(
        2L,
        Collections.singletonMap(
            MetricsCollector.MOCK_USER_ID_FOR_DISABLE_AUTHZ, Lists.newArrayList(updatedAssetCount)),
        secondTimestamp);

    MetricDTO[] staleResult =
        service.getMetricsByNameAndTimestamp(
            metalakeName2, "u1", new String[] {"catalog_count"}, firstTimestamp, secondTimestamp);
    assertEquals(0, staleResult.length);
    MetricDTO[] currentResult =
        service.getMetricsByNameAndTimestamp(
            metalakeName2,
            "u1",
            new String[] {ASSET_COUNT.getName()},
            secondTimestamp,
            secondTimestamp);
    assertEquals(1, currentResult.length);
    assertEquals(3.0, currentResult[0].values()[0]);
    assertEquals(secondTimestamp, currentResult[0].timestamps()[0]);
  }

  @Test
  void testCurrentOnlyQueryReturnsExactRowsAndDirtyState() {
    long timestamp = System.currentTimeMillis() + 15_000;
    MetricPO requested =
        MetricPO.builder().withMetricName("requested").withMetricValue(7.0).build();
    MetricPO ignored = MetricPO.builder().withMetricName("ignored").withMetricValue(8.0).build();
    service.replaceCurrentMetrics(
        1L,
        Collections.singletonMap(
            MetricsCollector.MOCK_USER_ID_FOR_DISABLE_AUTHZ,
            Lists.newArrayList(requested, ignored)),
        timestamp);
    service.insertMetrics(
        1L,
        MetricsCollector.MOCK_USER_ID_FOR_DISABLE_AUTHZ,
        Lists.newArrayList(
            MetricPO.builder()
                .withMetricName("requested")
                .withMetricValue(99.0)
                .withCreatedTime(new Timestamp(timestamp - 1))
                .build()));
    service.markMetalakeDirty(1L, timestamp);

    CurrentMetricsSnapshot result =
        service.getCurrentMetrics(metalakeName1, "u1", new String[] {"requested"});

    assertEquals(1L, result.getMetalakeId());
    assertEquals(1, result.getMetrics().size());
    assertEquals("requested", result.getMetrics().get(0).getMetricName());
    assertEquals(7.0, result.getMetrics().get(0).getMetricValue());
    assertEquals(timestamp, result.getMetrics().get(0).getCreatedTime().getTime());
    assertEquals(1L, result.getDirty().getRevision());

    assertAllCurrentMetrics(service.getCurrentMetrics(metalakeName1, "u1", null), timestamp);
    assertAllCurrentMetrics(
        service.getCurrentMetrics(metalakeName1, "u1", new String[0]), timestamp);
  }

  @Test
  void testReplaceCurrentAndAppendHistoryUsesOneTimestamp() throws SQLException {
    long runTimestamp = System.currentTimeMillis() + 20_000;
    MetricPO metric = MetricPO.builder().withMetricName("table_count").withMetricValue(4.0).build();
    service.replaceCurrentAndAppendHistory(
        1L,
        Collections.singletonMap(
            MetricsCollector.MOCK_USER_ID_FOR_DISABLE_AUTHZ, Lists.newArrayList(metric)),
        runTimestamp);

    try (Connection conn = DriverManager.getConnection(jdbcUrl, jdbcUser, jdbcPassword);
        Statement statement = conn.createStatement()) {
      ResultSet current =
          statement.executeQuery(
              "SELECT updated_time FROM dashboard_metric_current"
                  + " WHERE metalake_id = 1 AND metric_name = 'table_count'");
      assertTrue(current.next());
      assertEquals(runTimestamp, current.getTimestamp(1).getTime());

      ResultSet history =
          statement.executeQuery(
              "SELECT created_time FROM dashboard_metrics"
                  + " WHERE metalake_id = 1 AND metric_name = 'table_count'"
                  + " AND created_time = TIMESTAMP '"
                  + new Timestamp(runTimestamp)
                  + "'");
      assertTrue(history.next());
      assertEquals(runTimestamp, history.getTimestamp(1).getTime());
    }

    MetricDTO[] merged =
        service.getMetricsByNameAndTimestamp(
            metalakeName1, "u1", new String[] {"table_count"}, runTimestamp, runTimestamp);
    assertEquals(1, merged.length);
    assertEquals(1, merged[0].values().length, "current should win duplicate timestamp");
  }

  @Test
  void testCurrentWinsHistoryAtTheSameTimestamp() {
    long timestamp = System.currentTimeMillis() + 25_000;
    MetricPO history =
        MetricPO.builder()
            .withMetricName(ASSET_COUNT.getName())
            .withMetricValue(1.0)
            .withCreatedTime(new Timestamp(timestamp))
            .build();
    service.insertMetrics(
        1L, MetricsCollector.MOCK_USER_ID_FOR_DISABLE_AUTHZ, Lists.newArrayList(history));

    MetricPO current =
        MetricPO.builder().withMetricName(ASSET_COUNT.getName()).withMetricValue(2.0).build();
    service.replaceCurrentMetrics(
        1L,
        Collections.singletonMap(
            MetricsCollector.MOCK_USER_ID_FOR_DISABLE_AUTHZ, Lists.newArrayList(current)),
        timestamp);

    MetricDTO[] result =
        service.getMetricsByNameAndTimestamp(
            metalakeName1, "u1", new String[] {ASSET_COUNT.getName()}, timestamp, timestamp);
    assertEquals(1, result.length);
    assertEquals(1, result[0].values().length);
    assertEquals(2.0, result[0].values()[0]);
  }

  @Test
  void testHistoryAndCurrentAreSortedByTimestamp() {
    long firstTimestamp = System.currentTimeMillis() + 40_000;
    long secondTimestamp = firstTimestamp + 1;
    long currentTimestamp = secondTimestamp + 1;
    MetricPO second =
        MetricPO.builder()
            .withMetricName(ASSET_COUNT.getName())
            .withMetricValue(2.0)
            .withCreatedTime(new Timestamp(secondTimestamp))
            .build();
    MetricPO first =
        MetricPO.builder()
            .withMetricName(ASSET_COUNT.getName())
            .withMetricValue(1.0)
            .withCreatedTime(new Timestamp(firstTimestamp))
            .build();
    service.insertMetrics(
        1L, MetricsCollector.MOCK_USER_ID_FOR_DISABLE_AUTHZ, Lists.newArrayList(second, first));
    MetricPO current =
        MetricPO.builder().withMetricName(ASSET_COUNT.getName()).withMetricValue(3.0).build();
    service.replaceCurrentMetrics(
        1L,
        Collections.singletonMap(
            MetricsCollector.MOCK_USER_ID_FOR_DISABLE_AUTHZ, Lists.newArrayList(current)),
        currentTimestamp);

    MetricDTO[] result =
        service.getMetricsByNameAndTimestamp(
            metalakeName1,
            "u1",
            new String[] {ASSET_COUNT.getName()},
            firstTimestamp,
            currentTimestamp);
    assertEquals(1, result.length);
    Assertions.assertArrayEquals(
        new long[] {firstTimestamp, secondTimestamp, currentTimestamp}, result[0].timestamps());
    Assertions.assertArrayEquals(new Double[] {1.0, 2.0, 3.0}, result[0].values());
  }

  @Test
  void testCurrentReplacementRollsBackOnInsertFailure() {
    long originalTimestamp = System.currentTimeMillis() + 30_000;
    MetricPO original =
        MetricPO.builder().withMetricName(ASSET_COUNT.getName()).withMetricValue(5.0).build();
    service.replaceCurrentMetrics(
        1L,
        Collections.singletonMap(
            MetricsCollector.MOCK_USER_ID_FOR_DISABLE_AUTHZ, Lists.newArrayList(original)),
        originalTimestamp);

    MetricPO duplicate =
        MetricPO.builder().withMetricName(ASSET_COUNT.getName()).withMetricValue(6.0).build();
    assertThrows(
        RuntimeException.class,
        () ->
            service.replaceCurrentAndAppendHistory(
                1L,
                Collections.singletonMap(
                    MetricsCollector.MOCK_USER_ID_FOR_DISABLE_AUTHZ,
                    Lists.newArrayList(duplicate, duplicate)),
                originalTimestamp + 1));

    MetricDTO[] result =
        service.getMetricsByNameAndTimestamp(
            metalakeName1,
            "u1",
            new String[] {ASSET_COUNT.getName()},
            originalTimestamp,
            originalTimestamp);
    assertEquals(1, result.length);
    assertEquals(5.0, result[0].values()[0]);
    MetricDTO[] failedRun =
        service.getMetricsByNameAndTimestamp(
            metalakeName1,
            "u1",
            new String[] {ASSET_COUNT.getName()},
            originalTimestamp + 1,
            originalTimestamp + 1);
    assertEquals(0, failedRun.length);
  }

  @Test
  void testCleanInvalidMetrics() throws SQLException {
    String user = "u1";
    MetricPO assetCount =
        MetricPO.builder().withMetricName(ASSET_COUNT.getName()).withMetricValue(1.0).build();
    List<MetricPO> metrics = Lists.newArrayList(assetCount);

    service.insertMetrics(3L, MetricsCollector.MOCK_USER_ID_FOR_DISABLE_AUTHZ, metrics);
    service.replaceCurrentMetrics(
        3L,
        Collections.singletonMap(MetricsCollector.MOCK_USER_ID_FOR_DISABLE_AUTHZ, metrics),
        System.currentTimeMillis());
    service.markMetalakeDirty(3L, System.currentTimeMillis());
    MetricDTO[] result =
        service.getMetricsByNameAndTimestamp(
            metalakeName3, user, new String[0], 0, System.currentTimeMillis() + 2_000);
    Assertions.assertNotNull(result);
    Assertions.assertEquals(metrics.size(), result.length);

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
    Assertions.assertDoesNotThrow(() -> service.cleanInvalidMetrics());
    // Query again to ensure the metrics are cleaned
    MetricDTO[] cleanedResult =
        service.getMetricsByNameAndTimestamp(
            metalakeName3, user, new String[0], 0, System.currentTimeMillis() + 2_000);
    Assertions.assertNotNull(cleanedResult);
    Assertions.assertEquals(0, cleanedResult.length, "Metrics should be cleaned up");
    assertNull(service.getDirtyMetalake(3L));
  }

  private static void assertAllCurrentMetrics(
      CurrentMetricsSnapshot snapshot, long expectedTimestamp) {
    assertEquals(2, snapshot.getMetrics().size());
    assertEquals("ignored", snapshot.getMetrics().get(0).getMetricName());
    assertEquals(8.0, snapshot.getMetrics().get(0).getMetricValue());
    assertEquals(expectedTimestamp, snapshot.getMetrics().get(0).getCreatedTime().getTime());
    assertEquals("requested", snapshot.getMetrics().get(1).getMetricName());
    assertEquals(7.0, snapshot.getMetrics().get(1).getMetricValue());
    assertEquals(expectedTimestamp, snapshot.getMetrics().get(1).getCreatedTime().getTime());
    assertEquals(1L, snapshot.getDirty().getRevision());
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
