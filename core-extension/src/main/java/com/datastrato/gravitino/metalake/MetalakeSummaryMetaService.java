/*
 * Copyright 2026 Datastrato Inc.
 */
package com.datastrato.gravitino.metalake;

import static org.apache.gravitino.metrics.source.MetricsSource.GRAVITINO_RELATIONAL_STORE_METRIC_NAME;

import com.datastrato.gravitino.metalake.mapper.MetalakeSummaryMapper;
import com.google.common.base.Preconditions;
import javax.annotation.Nullable;
import org.apache.gravitino.exceptions.NoSuchMetalakeException;
import org.apache.gravitino.metrics.Monitored;
import org.apache.gravitino.storage.relational.utils.SessionUtils;

/** Enterprise persistence service for loading metalake summary counts. */
public final class MetalakeSummaryMetaService {
  private static final MetalakeSummaryMetaService INSTANCE = new MetalakeSummaryMetaService();

  private MetalakeSummaryMetaService() {}

  /**
   * Gets the singleton service instance.
   *
   * @return The singleton service instance.
   */
  public static MetalakeSummaryMetaService getInstance() {
    return INSTANCE;
  }

  /**
   * Loads all active child-entity counts for a persisted metalake in one query.
   *
   * @param metalake The metalake name.
   * @return Counts of active catalogs, users, and roles.
   * @throws NoSuchMetalakeException If the metalake does not exist.
   */
  @Monitored(
      metricsSource = GRAVITINO_RELATIONAL_STORE_METRIC_NAME,
      baseMetricName = "loadMetalakeSummaryCounts")
  public MetalakeSummaryCounts loadCounts(String metalake) {
    Preconditions.checkArgument(
        metalake != null && !metalake.isBlank(), "metalake cannot be blank");
    @Nullable
    MetalakeSummaryCounts counts =
        SessionUtils.getWithoutCommit(
            MetalakeSummaryMapper.class, mapper -> mapper.loadCounts(metalake));
    if (counts == null) {
      throw new NoSuchMetalakeException("Metalake %s does not exist", metalake);
    }
    return counts;
  }
}
