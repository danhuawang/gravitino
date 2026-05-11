/*
 * Copyright 2024 Datastrato Pvt Ltd.
 * This software is licensed under the Apache License version 2.
 */
package com.datastrato.gravitino.license;

import static org.apache.gravitino.Configs.ENTITY_RELATIONAL_JDBC_BACKEND_MAX_CONNECTIONS;

import com.datastrato.gravitino.license.mapper.LicenseNodeMapper;
import java.nio.charset.StandardCharsets;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.Statement;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;
import org.apache.gravitino.Config;
import org.apache.gravitino.Configs;
import org.apache.gravitino.storage.relational.session.SqlSessionFactoryHelper;
import org.apache.gravitino.storage.relational.utils.SessionUtils;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

class TestLicenseManager {

  private static final String JDBC_URL = "jdbc:h2:mem:licensetest;DB_CLOSE_DELAY=-1;MODE=MySQL";
  private static final String DRIVER = "org.h2.Driver";

  // Fixed clock so tests that compute expiry relative to "today" are never flaky at midnight.
  private static final LocalDate FIXED_TODAY = LocalDate.of(2025, 1, 15);
  private static final Clock FIXED_CLOCK =
      Clock.fixed(FIXED_TODAY.atStartOfDay(ZoneOffset.UTC).toInstant(), ZoneOffset.UTC);

  @BeforeAll
  static void setUpDb() throws Exception {
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
          "CREATE TABLE IF NOT EXISTS license_nodes ("
              + "node_id VARCHAR(64) NOT NULL PRIMARY KEY,"
              + "registered_at BIGINT NOT NULL,"
              + "last_heartbeat BIGINT NOT NULL)");
    }
    SqlSessionFactoryHelper.getInstance().init(config);
  }

  @AfterAll
  static void tearDownDb() {
    SqlSessionFactoryHelper.getInstance().close();
  }

  private static String buildKey(long expiresAtDays, int graceDays, int maxNodes) throws Exception {
    byte[] name = "Test Corp".getBytes(StandardCharsets.UTF_8);
    byte[] payload = new byte[29 + name.length];
    payload[0] = 1;
    UUID id = UUID.randomUUID();
    long msb = id.getMostSignificantBits(), lsb = id.getLeastSignificantBits();
    for (int i = 0; i < 8; i++) {
      payload[1 + i] = (byte) (msb >>> (56 - 8 * i));
    }
    for (int i = 0; i < 8; i++) {
      payload[9 + i] = (byte) (lsb >>> (56 - 8 * i));
    }
    long issuedAt = LocalDate.of(2024, 1, 1).toEpochDay();
    payload[17] = (byte) (issuedAt >> 24);
    payload[18] = (byte) (issuedAt >> 16);
    payload[19] = (byte) (issuedAt >> 8);
    payload[20] = (byte) issuedAt;
    payload[21] = (byte) (expiresAtDays >> 24);
    payload[22] = (byte) (expiresAtDays >> 16);
    payload[23] = (byte) (expiresAtDays >> 8);
    payload[24] = (byte) expiresAtDays;
    payload[25] = (byte) graceDays;
    payload[26] = (byte) (maxNodes >> 8);
    payload[27] = (byte) maxNodes;
    payload[28] = (byte) name.length;
    System.arraycopy(name, 0, payload, 29, name.length);
    return TestKeyPairUtil.buildKey(payload);
  }

  @Test
  void testValidLicense() throws Exception {
    long future = LocalDate.of(2030, 1, 1).toEpochDay();
    String key = buildKey(future, 30, -1);
    LicenseManager mgr = new LicenseManager();
    mgr.setVerifierForTest(TestKeyPairUtil.newVerifier());
    mgr.initForTest(key, Map.of());
    Assertions.assertEquals(LicenseManager.StatusCode.VALID, mgr.getStatus().getStatusCode());
    Assertions.assertEquals("Test Corp", mgr.getStatus().getIssuedTo());
  }

  @Test
  void testExpiredLicenseThrows() throws Exception {
    long expired = FIXED_TODAY.toEpochDay() - 5;
    String key = buildKey(expired, 0, -1);
    LicenseManager mgr = new LicenseManager();
    mgr.clock = FIXED_CLOCK;
    mgr.setVerifierForTest(TestKeyPairUtil.newVerifier());
    LicenseException ex =
        Assertions.assertThrows(LicenseException.class, () -> mgr.initForTest(key, Map.of()));
    Assertions.assertEquals(LicenseException.ErrorCode.EXPIRED, ex.getErrorCode());
  }

  @Test
  void testInGracePeriod() throws Exception {
    long expired = FIXED_TODAY.toEpochDay() - 2;
    String key = buildKey(expired, 30, -1);
    LicenseManager mgr = new LicenseManager();
    mgr.clock = FIXED_CLOCK;
    mgr.setVerifierForTest(TestKeyPairUtil.newVerifier());
    mgr.initForTest(key, Map.of());
    Assertions.assertEquals(
        LicenseManager.StatusCode.IN_GRACE_PERIOD, mgr.getStatus().getStatusCode());
  }

  @Test
  void testExpiringSoon() throws Exception {
    long soon = FIXED_TODAY.toEpochDay() + 10;
    String key = buildKey(soon, 30, -1);
    LicenseManager mgr = new LicenseManager();
    mgr.clock = FIXED_CLOCK;
    mgr.setVerifierForTest(TestKeyPairUtil.newVerifier());
    mgr.initForTest(key, Map.of("gravitino.datastrato.license.warnDaysBeforeExpiry", "30"));
    Assertions.assertEquals(
        LicenseManager.StatusCode.EXPIRING_SOON, mgr.getStatus().getStatusCode());
  }

  @Test
  void testMissingKeyThrows() {
    LicenseManager mgr = new LicenseManager();
    mgr.setVerifierForTest(TestKeyPairUtil.newVerifier());
    LicenseException ex =
        Assertions.assertThrows(LicenseException.class, () -> mgr.initForTest(null, Map.of()));
    Assertions.assertEquals(LicenseException.ErrorCode.MISSING, ex.getErrorCode());
  }

  /** Empty and whitespace-only keys must throw MISSING (StringUtils.isBlank coverage). */
  @Test
  void testBlankKeyThrows() {
    for (String blank : new String[] {"", "   ", "\t"}) {
      LicenseManager mgr = new LicenseManager();
      mgr.setVerifierForTest(TestKeyPairUtil.newVerifier());
      LicenseException ex =
          Assertions.assertThrows(LicenseException.class, () -> mgr.initForTest(blank, Map.of()));
      Assertions.assertEquals(LicenseException.ErrorCode.MISSING, ex.getErrorCode());
    }
  }

  /** Status object must carry the correct field values from the payload. */
  @Test
  void testStatusFieldsPopulated() throws Exception {
    long future = LocalDate.of(2030, 6, 15).toEpochDay();
    String key = buildKey(future, 14, 4);
    LicenseManager mgr = new LicenseManager();
    mgr.setVerifierForTest(TestKeyPairUtil.newVerifier());
    mgr.initForTest(key, Map.of());

    LicenseManager.LicenseStatus s = mgr.getStatus();
    Assertions.assertEquals("Test Corp", s.getIssuedTo());
    Assertions.assertNotNull(s.getLicenseId());
    Assertions.assertEquals(future, s.getExpiresAtDays());
    Assertions.assertEquals(14, s.getGracePeriodDays());
    Assertions.assertEquals(4, s.getMaxNodes());
  }

  /** daysRemaining must equal exactly (expiresAt - today) for a valid license. */
  @Test
  void testDaysRemainingForValidLicense() throws Exception {
    long future = FIXED_TODAY.toEpochDay() + 50;
    String key = buildKey(future, 30, -1);
    LicenseManager mgr = new LicenseManager();
    mgr.clock = FIXED_CLOCK;
    mgr.setVerifierForTest(TestKeyPairUtil.newVerifier());
    mgr.initForTest(key, Map.of("gravitino.datastrato.license.warnDaysBeforeExpiry", "30"));

    Assertions.assertEquals(50, mgr.getStatus().getDaysRemaining());
    Assertions.assertEquals(LicenseManager.StatusCode.VALID, mgr.getStatus().getStatusCode());
  }

  /** daysRemaining in grace period must equal (gracePeriodDays - daysIntoGrace). */
  @Test
  void testDaysRemainingInGracePeriod() throws Exception {
    String key = buildKey(FIXED_TODAY.toEpochDay() - 5, 30, -1);
    LicenseManager mgr = new LicenseManager();
    mgr.clock = FIXED_CLOCK;
    mgr.setVerifierForTest(TestKeyPairUtil.newVerifier());
    mgr.initForTest(key, Map.of());

    Assertions.assertEquals(
        LicenseManager.StatusCode.IN_GRACE_PERIOD, mgr.getStatus().getStatusCode());
    Assertions.assertEquals(25, mgr.getStatus().getDaysRemaining());
  }

  /** A license expired beyond its grace period must throw EXPIRED. */
  @Test
  void testExpiredBeyondGracePeriodThrows() throws Exception {
    String key = buildKey(FIXED_TODAY.toEpochDay() - 35, 30, -1);
    LicenseManager mgr = new LicenseManager();
    mgr.clock = FIXED_CLOCK;
    mgr.setVerifierForTest(TestKeyPairUtil.newVerifier());
    LicenseException ex =
        Assertions.assertThrows(LicenseException.class, () -> mgr.initForTest(key, Map.of()));
    Assertions.assertEquals(LicenseException.ErrorCode.EXPIRED, ex.getErrorCode());
  }

  /** A license expiring exactly on the warn threshold boundary must be EXPIRING_SOON. */
  @Test
  void testWarnThresholdBoundary() throws Exception {
    String key = buildKey(FIXED_TODAY.toEpochDay() + 30, 30, -1);
    LicenseManager mgr = new LicenseManager();
    mgr.clock = FIXED_CLOCK;
    mgr.setVerifierForTest(TestKeyPairUtil.newVerifier());
    mgr.initForTest(key, Map.of("gravitino.datastrato.license.warnDaysBeforeExpiry", "30"));
    Assertions.assertEquals(
        LicenseManager.StatusCode.EXPIRING_SOON, mgr.getStatus().getStatusCode());
  }

  /** One day past the warn threshold must stay VALID. */
  @Test
  void testJustPastWarnThresholdIsValid() throws Exception {
    String key = buildKey(FIXED_TODAY.toEpochDay() + 31, 30, -1);
    LicenseManager mgr = new LicenseManager();
    mgr.clock = FIXED_CLOCK;
    mgr.setVerifierForTest(TestKeyPairUtil.newVerifier());
    mgr.initForTest(key, Map.of("gravitino.datastrato.license.warnDaysBeforeExpiry", "30"));
    Assertions.assertEquals(LicenseManager.StatusCode.VALID, mgr.getStatus().getStatusCode());
  }

  /** Unlimited license (maxNodes = -1) must be reflected in the status. */
  @Test
  void testMaxNodesUnlimitedInStatus() throws Exception {
    long future = LocalDate.of(2030, 1, 1).toEpochDay();
    String key = buildKey(future, 30, -1);
    LicenseManager mgr = new LicenseManager();
    mgr.setVerifierForTest(TestKeyPairUtil.newVerifier());
    mgr.initForTest(key, Map.of());
    Assertions.assertEquals(-1, mgr.getStatus().getMaxNodes());
  }

  /** Limited license must expose the correct maxNodes in the status. */
  @Test
  void testMaxNodesLimitedInStatus() throws Exception {
    long future = LocalDate.of(2030, 1, 1).toEpochDay();
    String key = buildKey(future, 30, 8);
    LicenseManager mgr = new LicenseManager();
    mgr.setVerifierForTest(TestKeyPairUtil.newVerifier());
    mgr.initForTest(key, Map.of());
    Assertions.assertEquals(8, mgr.getStatus().getMaxNodes());
  }

  /** A license expiring today (expiresAt == today) must be EXPIRING_SOON with 0 days remaining. */
  @Test
  void testExpiryToday() throws Exception {
    String key = buildKey(FIXED_TODAY.toEpochDay(), 30, -1);
    LicenseManager mgr = new LicenseManager();
    mgr.clock = FIXED_CLOCK;
    mgr.setVerifierForTest(TestKeyPairUtil.newVerifier());
    mgr.initForTest(key, Map.of("gravitino.datastrato.license.warnDaysBeforeExpiry", "30"));
    Assertions.assertEquals(
        LicenseManager.StatusCode.EXPIRING_SOON, mgr.getStatus().getStatusCode());
    Assertions.assertEquals(0, mgr.getStatus().getDaysRemaining());
  }

  /**
   * When the node count is within the limit, heartbeatCheck must not trigger the exit handler. Two
   * peers (aaa, bbb) + the test node (zzz) = 3 total, maxNodes=3 → rank 3 ≤ 3, no exit.
   */
  @Test
  void testHeartbeatBelowLimitNoExit() throws Exception {
    String testNodeId = "mgr-zzz-below";
    SessionUtils.doWithCommit(LicenseNodeMapper.class, m -> m.upsertNode("mgr-aaa-below"));
    SessionUtils.doWithCommit(LicenseNodeMapper.class, m -> m.upsertNode("mgr-bbb-below"));
    SessionUtils.doWithCommit(LicenseNodeMapper.class, m -> m.upsertNode(testNodeId));

    long future = LocalDate.of(2030, 1, 1).toEpochDay();
    LicenseManager mgr = new LicenseManager();
    mgr.setVerifierForTest(TestKeyPairUtil.newVerifier());
    mgr.initForTest(buildKey(future, 30, 3), Map.of());
    mgr.nodeId = testNodeId;

    AtomicBoolean exitCalled = new AtomicBoolean(false);
    mgr.exitHandler = () -> exitCalled.set(true);
    mgr.heartbeatCheck();

    Assertions.assertFalse(exitCalled.get());

    // Cleanup
    SessionUtils.doWithCommit(LicenseNodeMapper.class, m -> m.deleteNode("mgr-aaa-below"));
    SessionUtils.doWithCommit(LicenseNodeMapper.class, m -> m.deleteNode("mgr-bbb-below"));
    SessionUtils.doWithCommit(LicenseNodeMapper.class, m -> m.deleteNode(testNodeId));
  }

  /**
   * When the node exceeds maxNodes, the first heartbeat starts a grace period (no exit). After the
   * grace period (nodeStaleMinutes) elapses without resolution, the second heartbeat triggers exit
   * and removes the node. Three peers (aaa, bbb, ccc) + the test node (zzz) = 4 total, maxNodes=3.
   */
  @Test
  void testHeartbeatExceedsMaxNodesTriggersExitAfterGracePeriod() throws Exception {
    String testNodeId = "mgr-zzz-exceed";
    SessionUtils.doWithCommit(LicenseNodeMapper.class, m -> m.upsertNode("mgr-aaa-exceed"));
    SessionUtils.doWithCommit(LicenseNodeMapper.class, m -> m.upsertNode("mgr-bbb-exceed"));
    SessionUtils.doWithCommit(LicenseNodeMapper.class, m -> m.upsertNode("mgr-ccc-exceed"));
    SessionUtils.doWithCommit(LicenseNodeMapper.class, m -> m.upsertNode(testNodeId));

    int countBefore =
        SessionUtils.doWithCommitAndFetchResult(
            LicenseNodeMapper.class, LicenseNodeMapper::countActiveNodes);

    long future = LocalDate.of(2030, 1, 1).toEpochDay();
    LicenseManager mgr = new LicenseManager();
    mgr.setVerifierForTest(TestKeyPairUtil.newVerifier());
    mgr.initForTest(buildKey(future, 30, 3), Map.of());
    mgr.nodeId = testNodeId;

    AtomicBoolean exitCalled = new AtomicBoolean(false);
    mgr.exitHandler = () -> exitCalled.set(true);

    // First heartbeat: starts grace period, no exit yet
    mgr.clock = Clock.fixed(Instant.ofEpochSecond(0), ZoneOffset.UTC);
    mgr.heartbeatCheck();
    Assertions.assertFalse(exitCalled.get());
    Assertions.assertNotNull(mgr.overLimitSince);

    // Second heartbeat after grace period (default 15 min) has elapsed: exit triggered
    mgr.clock = Clock.fixed(Instant.ofEpochSecond(16 * 60), ZoneOffset.UTC);
    mgr.heartbeatCheck();
    Assertions.assertTrue(exitCalled.get());

    // The exceeding node must have deleted itself — count drops by one
    int countAfter =
        SessionUtils.doWithCommitAndFetchResult(
            LicenseNodeMapper.class, LicenseNodeMapper::countActiveNodes);
    Assertions.assertEquals(countBefore - 1, countAfter);

    // Cleanup remaining peers
    SessionUtils.doWithCommit(LicenseNodeMapper.class, m -> m.deleteNode("mgr-aaa-exceed"));
    SessionUtils.doWithCommit(LicenseNodeMapper.class, m -> m.deleteNode("mgr-bbb-exceed"));
    SessionUtils.doWithCommit(LicenseNodeMapper.class, m -> m.deleteNode("mgr-ccc-exceed"));
  }

  /**
   * The first heartbeat that detects rank > maxNodes must not trigger exit — it only starts the
   * grace period. This covers the kill-9 restart and rolling-upgrade overlap window.
   */
  @Test
  void testHeartbeatGracePeriodFirstViolationNoExit() throws Exception {
    String testNodeId = "mgr-zzz-grace1";
    SessionUtils.doWithCommit(LicenseNodeMapper.class, m -> m.upsertNode("mgr-aaa-grace1"));
    SessionUtils.doWithCommit(LicenseNodeMapper.class, m -> m.upsertNode("mgr-bbb-grace1"));
    SessionUtils.doWithCommit(LicenseNodeMapper.class, m -> m.upsertNode("mgr-ccc-grace1"));
    SessionUtils.doWithCommit(LicenseNodeMapper.class, m -> m.upsertNode(testNodeId));

    long future = LocalDate.of(2030, 1, 1).toEpochDay();
    LicenseManager mgr = new LicenseManager();
    mgr.setVerifierForTest(TestKeyPairUtil.newVerifier());
    mgr.initForTest(buildKey(future, 30, 3), Map.of());
    mgr.nodeId = testNodeId;
    mgr.clock = Clock.fixed(Instant.ofEpochSecond(0), ZoneOffset.UTC);

    AtomicBoolean exitCalled = new AtomicBoolean(false);
    mgr.exitHandler = () -> exitCalled.set(true);
    mgr.heartbeatCheck();

    Assertions.assertFalse(exitCalled.get());
    Assertions.assertNotNull(mgr.overLimitSince);

    // Cleanup
    SessionUtils.doWithCommit(LicenseNodeMapper.class, m -> m.deleteNode("mgr-aaa-grace1"));
    SessionUtils.doWithCommit(LicenseNodeMapper.class, m -> m.deleteNode("mgr-bbb-grace1"));
    SessionUtils.doWithCommit(LicenseNodeMapper.class, m -> m.deleteNode("mgr-ccc-grace1"));
    SessionUtils.doWithCommit(LicenseNodeMapper.class, m -> m.deleteNode(testNodeId));
  }

  /**
   * When the over-limit violation resolves (ghost row expires) within the grace period,
   * overLimitSince is reset to null and no exit is triggered. This ensures a subsequent violation
   * starts a fresh grace period rather than accumulating toward the original deadline.
   */
  @Test
  void testHeartbeatGracePeriodResetsOnRecovery() throws Exception {
    String testNodeId = "mgr-zzz-grace3";
    SessionUtils.doWithCommit(LicenseNodeMapper.class, m -> m.upsertNode("mgr-aaa-grace3"));
    SessionUtils.doWithCommit(LicenseNodeMapper.class, m -> m.upsertNode("mgr-bbb-grace3"));
    SessionUtils.doWithCommit(LicenseNodeMapper.class, m -> m.upsertNode("mgr-ccc-grace3"));
    SessionUtils.doWithCommit(LicenseNodeMapper.class, m -> m.upsertNode(testNodeId));

    long future = LocalDate.of(2030, 1, 1).toEpochDay();
    LicenseManager mgr = new LicenseManager();
    mgr.setVerifierForTest(TestKeyPairUtil.newVerifier());
    mgr.initForTest(buildKey(future, 30, 3), Map.of());
    mgr.nodeId = testNodeId;

    AtomicBoolean exitCalled = new AtomicBoolean(false);
    mgr.exitHandler = () -> exitCalled.set(true);

    // First call: rank 4 > 3, grace period starts
    mgr.clock = Clock.fixed(Instant.ofEpochSecond(0), ZoneOffset.UTC);
    mgr.heartbeatCheck();
    Assertions.assertFalse(exitCalled.get());
    Assertions.assertNotNull(mgr.overLimitSince);

    // Ghost row removed (simulates stale cleanup by another node or manual intervention)
    SessionUtils.doWithCommit(LicenseNodeMapper.class, m -> m.deleteNode("mgr-aaa-grace3"));

    // Second call: rank 3 ≤ 3, violation resolved → overLimitSince must be reset
    mgr.clock = Clock.fixed(Instant.ofEpochSecond(5 * 60), ZoneOffset.UTC);
    mgr.heartbeatCheck();
    Assertions.assertFalse(exitCalled.get());
    Assertions.assertNull(mgr.overLimitSince);

    // Cleanup
    SessionUtils.doWithCommit(LicenseNodeMapper.class, m -> m.deleteNode("mgr-bbb-grace3"));
    SessionUtils.doWithCommit(LicenseNodeMapper.class, m -> m.deleteNode("mgr-ccc-grace3"));
    SessionUtils.doWithCommit(LicenseNodeMapper.class, m -> m.deleteNode(testNodeId));
  }

  /**
   * An unlimited license (maxNodes=-1) must never trigger the exit handler regardless of how many
   * nodes are active.
   */
  @Test
  void testHeartbeatUnlimitedLicenseNoEnforcement() throws Exception {
    String testNodeId = "mgr-zzz-unlimited";
    String[] peers = {
      "mgr-p0-unlimited",
      "mgr-p1-unlimited",
      "mgr-p2-unlimited",
      "mgr-p3-unlimited",
      "mgr-p4-unlimited"
    };
    for (String peer : peers) {
      SessionUtils.doWithCommit(LicenseNodeMapper.class, m -> m.upsertNode(peer));
    }
    SessionUtils.doWithCommit(LicenseNodeMapper.class, m -> m.upsertNode(testNodeId));

    long future = LocalDate.of(2030, 1, 1).toEpochDay();
    LicenseManager mgr = new LicenseManager();
    mgr.setVerifierForTest(TestKeyPairUtil.newVerifier());
    mgr.initForTest(buildKey(future, 30, -1), Map.of());
    mgr.nodeId = testNodeId;

    AtomicBoolean exitCalled = new AtomicBoolean(false);
    mgr.exitHandler = () -> exitCalled.set(true);
    mgr.heartbeatCheck();

    Assertions.assertFalse(exitCalled.get());

    // Cleanup
    for (String peer : peers) {
      SessionUtils.doWithCommit(LicenseNodeMapper.class, m -> m.deleteNode(peer));
    }
    SessionUtils.doWithCommit(LicenseNodeMapper.class, m -> m.deleteNode(testNodeId));
  }

  /** nodeStaleMinutes must be greater than nodeHeartbeatIntervalMinutes. */
  @Test
  void testStaleHeartbeatIntervalValidation() throws Exception {
    long future = LocalDate.of(2030, 1, 1).toEpochDay();
    String key = buildKey(future, 30, -1);
    LicenseManager mgr = new LicenseManager();
    mgr.setVerifierForTest(TestKeyPairUtil.newVerifier());
    Map<String, String> props =
        Map.of(
            "gravitino.datastrato.license.nodeStaleMinutes", "5",
            "gravitino.datastrato.license.nodeHeartbeatIntervalMinutes", "5");
    Assertions.assertThrows(IllegalArgumentException.class, () -> mgr.initForTest(key, props));
  }
}
