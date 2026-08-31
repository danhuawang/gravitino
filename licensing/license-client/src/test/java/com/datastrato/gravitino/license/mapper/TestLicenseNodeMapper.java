/*
 * Copyright 2024 Datastrato Inc.
 */
package com.datastrato.gravitino.license.mapper;

import static org.apache.gravitino.Configs.ENTITY_RELATIONAL_JDBC_BACKEND_MAX_CONNECTIONS;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.Statement;
import org.apache.gravitino.Config;
import org.apache.gravitino.Configs;
import org.apache.gravitino.storage.relational.session.SqlSessionFactoryHelper;
import org.apache.gravitino.storage.relational.utils.SessionUtils;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

class TestLicenseNodeMapper {
  private static final String JDBC_URL = "jdbc:h2:mem:licensetest;DB_CLOSE_DELAY=-1;MODE=MySQL";
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
          "CREATE TABLE IF NOT EXISTS license_nodes ("
              + "node_id VARCHAR(64) NOT NULL PRIMARY KEY,"
              + "registered_at BIGINT NOT NULL,"
              + "last_heartbeat BIGINT NOT NULL)");
    }
    SqlSessionFactoryHelper.getInstance().init(config);
  }

  @AfterAll
  static void tearDown() {
    SqlSessionFactoryHelper.getInstance().close();
  }

  @Test
  void testUpsertCountDelete() {
    // 1 ms interval: only rows with heartbeat older than 1 ms are pruned — fresh inserts survive.
    long staleIntervalMs = 1L;

    SessionUtils.doMultipleWithCommit(
        () ->
            SessionUtils.doWithoutCommit(
                LicenseNodeMapper.class, m -> m.deleteStaleNodes(staleIntervalMs)),
        () -> SessionUtils.doWithoutCommit(LicenseNodeMapper.class, m -> m.upsertNode("node-1")),
        () -> {
          int count =
              SessionUtils.getWithoutCommit(
                  LicenseNodeMapper.class, LicenseNodeMapper::countActiveNodes);
          Assertions.assertEquals(1, count);
        });

    SessionUtils.doWithCommit(LicenseNodeMapper.class, m -> m.deleteNode("node-1"));

    int countAfter =
        SessionUtils.doWithCommitAndFetchResult(
            LicenseNodeMapper.class, LicenseNodeMapper::countActiveNodes);
    Assertions.assertEquals(0, countAfter);
  }

  @Test
  void testRankNode() {
    // Insert three nodes — ranks determined by node_id tie-breaker (rank-a < rank-b < rank-c)
    SessionUtils.doWithCommit(LicenseNodeMapper.class, m -> m.upsertNode("rank-a"));
    SessionUtils.doWithCommit(LicenseNodeMapper.class, m -> m.upsertNode("rank-b"));
    SessionUtils.doWithCommit(LicenseNodeMapper.class, m -> m.upsertNode("rank-c"));

    int rankA =
        SessionUtils.doWithCommitAndFetchResult(LicenseNodeMapper.class, m -> m.rankNode("rank-a"));
    int rankB =
        SessionUtils.doWithCommitAndFetchResult(LicenseNodeMapper.class, m -> m.rankNode("rank-b"));
    int rankC =
        SessionUtils.doWithCommitAndFetchResult(LicenseNodeMapper.class, m -> m.rankNode("rank-c"));

    Assertions.assertEquals(1, rankA);
    Assertions.assertEquals(2, rankB);
    Assertions.assertEquals(3, rankC);

    // Cleanup
    SessionUtils.doWithCommit(LicenseNodeMapper.class, m -> m.deleteNode("rank-a"));
    SessionUtils.doWithCommit(LicenseNodeMapper.class, m -> m.deleteNode("rank-b"));
    SessionUtils.doWithCommit(LicenseNodeMapper.class, m -> m.deleteNode("rank-c"));
  }

  /** ON DUPLICATE KEY UPDATE path: upserting the same nodeId twice must not create a second row. */
  @Test
  void testUpsertOnDuplicateKeyDoesNotCreateDuplicateRow() {
    int before =
        SessionUtils.doWithCommitAndFetchResult(
            LicenseNodeMapper.class, LicenseNodeMapper::countActiveNodes);

    SessionUtils.doWithCommit(LicenseNodeMapper.class, m -> m.upsertNode("dup-node"));
    int afterFirst =
        SessionUtils.doWithCommitAndFetchResult(
            LicenseNodeMapper.class, LicenseNodeMapper::countActiveNodes);
    Assertions.assertEquals(before + 1, afterFirst);

    // Second upsert with the same nodeId — count must not increase
    SessionUtils.doWithCommit(LicenseNodeMapper.class, m -> m.upsertNode("dup-node"));
    int afterSecond =
        SessionUtils.doWithCommitAndFetchResult(
            LicenseNodeMapper.class, LicenseNodeMapper::countActiveNodes);
    Assertions.assertEquals(before + 1, afterSecond);

    // Cleanup
    SessionUtils.doWithCommit(LicenseNodeMapper.class, m -> m.deleteNode("dup-node"));
  }

  /** rankNode must return 1 when the node is the only one in the table. */
  @Test
  void testRankNodeSingleNode() {
    // Use a nodeId that sorts after any node left by other tests, so rank = total count
    SessionUtils.doWithCommit(LicenseNodeMapper.class, m -> m.upsertNode("zzz-solo"));

    int total =
        SessionUtils.doWithCommitAndFetchResult(
            LicenseNodeMapper.class, LicenseNodeMapper::countActiveNodes);
    int rank =
        SessionUtils.doWithCommitAndFetchResult(
            LicenseNodeMapper.class, m -> m.rankNode("zzz-solo"));
    Assertions.assertEquals(total, rank);

    // Cleanup
    SessionUtils.doWithCommit(LicenseNodeMapper.class, m -> m.deleteNode("zzz-solo"));
  }

  /** rankNode tie-breaker: nodes with the same registered_at are ordered by node_id. */
  @Test
  void testRankNodeTieBreaker() {
    // Insert two nodes in quick succession — they may share the same DB-side second timestamp.
    // The tie-breaker on node_id must still produce distinct, correct ranks.
    SessionUtils.doWithCommit(LicenseNodeMapper.class, m -> m.upsertNode("tie-aaa"));
    SessionUtils.doWithCommit(LicenseNodeMapper.class, m -> m.upsertNode("tie-zzz"));

    int rankAaa =
        SessionUtils.doWithCommitAndFetchResult(
            LicenseNodeMapper.class, m -> m.rankNode("tie-aaa"));
    int rankZzz =
        SessionUtils.doWithCommitAndFetchResult(
            LicenseNodeMapper.class, m -> m.rankNode("tie-zzz"));

    // tie-aaa sorts before tie-zzz, so its rank must be lower
    Assertions.assertTrue(rankAaa < rankZzz);
    // ranks must be distinct
    Assertions.assertNotEquals(rankAaa, rankZzz);

    // Cleanup
    SessionUtils.doWithCommit(LicenseNodeMapper.class, m -> m.deleteNode("tie-aaa"));
    SessionUtils.doWithCommit(LicenseNodeMapper.class, m -> m.deleteNode("tie-zzz"));
  }

  @Test
  void testRankNodeReturnsZeroWhenNodeAbsent() {
    int rank =
        SessionUtils.doWithCommitAndFetchResult(
            LicenseNodeMapper.class, m -> m.rankNode("nonexistent-node-xyz-12345"));
    Assertions.assertEquals(0, rank);
  }
}
