/*
 * Copyright 2024 Datastrato Pvt Ltd.
 * This software is licensed under the Apache License version 2.
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
  void testUpsertCountHeartbeatDelete() {
    long now = System.currentTimeMillis();
    long staleThreshold = now - 1000L;

    SessionUtils.doMultipleWithCommit(
        () ->
            SessionUtils.doWithoutCommit(
                LicenseNodeMapper.class, m -> m.deleteStaleNodes(staleThreshold)),
        () ->
            SessionUtils.doWithoutCommit(
                LicenseNodeMapper.class, m -> m.upsertNode("node-1", now, now)),
        () -> {
          int count =
              SessionUtils.getWithoutCommit(
                  LicenseNodeMapper.class, LicenseNodeMapper::countActiveNodes);
          Assertions.assertEquals(1, count);
        });

    SessionUtils.doWithCommit(
        LicenseNodeMapper.class, m -> m.updateHeartbeat("node-1", now + 1000));

    SessionUtils.doWithCommit(LicenseNodeMapper.class, m -> m.deleteNode("node-1"));

    int countAfter =
        SessionUtils.doWithCommitAndFetchResult(
            LicenseNodeMapper.class, LicenseNodeMapper::countActiveNodes);
    Assertions.assertEquals(0, countAfter);
  }

  @Test
  void testStaleNodePurge() {
    long now = System.currentTimeMillis();
    SessionUtils.doWithCommit(
        LicenseNodeMapper.class, m -> m.upsertNode("stale-node", now - 100_000, now - 100_000));

    long staleThreshold = now - 50_000;
    SessionUtils.doWithCommit(LicenseNodeMapper.class, m -> m.deleteStaleNodes(staleThreshold));

    int count =
        SessionUtils.doWithCommitAndFetchResult(
            LicenseNodeMapper.class, LicenseNodeMapper::countActiveNodes);
    Assertions.assertEquals(0, count);
  }
}
