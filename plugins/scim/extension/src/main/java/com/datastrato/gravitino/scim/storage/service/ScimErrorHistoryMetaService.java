/*
 * Copyright 2026 Datastrato Inc.
 */

package com.datastrato.gravitino.scim.storage.service;

import static org.apache.gravitino.metrics.source.MetricsSource.GRAVITINO_RELATIONAL_STORE_METRIC_NAME;

import com.datastrato.gravitino.scim.storage.mapper.ScimErrorHistoryMapper;
import com.datastrato.gravitino.scim.storage.po.ScimErrorHistoryPO;
import com.datastrato.gravitino.scim.storage.relational.utils.ScimExceptionUtils;
import java.io.IOException;
import org.apache.gravitino.metrics.Monitored;
import org.apache.gravitino.storage.relational.utils.SessionUtils;

/** Service class for SCIM protocol error history database operations. */
public class ScimErrorHistoryMetaService {
  private static final ScimErrorHistoryMetaService INSTANCE = new ScimErrorHistoryMetaService();

  private ScimErrorHistoryMetaService() {}

  /** Returns the singleton SCIM error history service instance. */
  public static ScimErrorHistoryMetaService getInstance() {
    return INSTANCE;
  }

  /**
   * Inserts an error history row.
   *
   * @param errorHistory error history to insert
   * @throws IOException if persistence fails
   */
  @Monitored(
      metricsSource = GRAVITINO_RELATIONAL_STORE_METRIC_NAME,
      baseMetricName = "insertScimErrorHistory")
  public void insertScimErrorHistory(ScimErrorHistoryPO errorHistory) throws IOException {
    try {
      SessionUtils.doWithCommit(
          ScimErrorHistoryMapper.class, mapper -> mapper.insert(errorHistory));
    } catch (RuntimeException re) {
      ScimExceptionUtils.checkSQLException(
          re, "errorHistory", String.valueOf(errorHistory.getErrorId()));
      throw re;
    }
  }

  /**
   * Counts error history rows for the given metalake.
   *
   * @param metalakeName target metalake name
   * @return row count, or {@code 0} when the metalake is unknown
   */
  @Monitored(
      metricsSource = GRAVITINO_RELATIONAL_STORE_METRIC_NAME,
      baseMetricName = "countScimErrorHistory")
  public long countScimErrorHistory(String metalakeName) {
    Long count =
        SessionUtils.getWithoutCommit(
            ScimErrorHistoryMapper.class, mapper -> mapper.countByMetalake(metalakeName));
    return count == null ? 0L : count;
  }

  /**
   * Physically deletes error history rows older than the legacy timeline.
   *
   * @param legacyTimeline delete rows with {@code created_at} before this timestamp
   * @param limit maximum rows to delete in one batch
   * @return number of rows physically deleted
   */
  @Monitored(
      metricsSource = GRAVITINO_RELATIONAL_STORE_METRIC_NAME,
      baseMetricName = "deleteScimErrorHistoryByCreatedAtBefore")
  public int deleteScimErrorHistoryByCreatedAtBefore(long legacyTimeline, int limit) {
    Integer deleted =
        SessionUtils.doWithCommitAndFetchResult(
            ScimErrorHistoryMapper.class,
            mapper -> mapper.deleteByCreatedAtBefore(legacyTimeline, limit));
    return deleted == null ? 0 : deleted;
  }
}
