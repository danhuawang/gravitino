/*
 * Copyright 2026 Datastrato Inc.
 */
package com.datastrato.gravitino.metrics.storage.relational.service;

import com.datastrato.gravitino.metrics.storage.relational.MetricDirtyPO;
import com.datastrato.gravitino.metrics.storage.relational.MetricPO;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import javax.annotation.Nullable;

/** Current dashboard metric rows and dirty state read for one metalake and effective user. */
public final class CurrentMetricsSnapshot {
  private final long metalakeId;
  private final List<MetricPO> metrics;
  @Nullable private final MetricDirtyPO dirty;

  /**
   * Creates a current metric snapshot.
   *
   * @param metalakeId metalake ID
   * @param metrics current metric rows
   * @param dirty current dirty marker, or {@code null}
   */
  public CurrentMetricsSnapshot(
      long metalakeId, List<MetricPO> metrics, @Nullable MetricDirtyPO dirty) {
    this.metalakeId = metalakeId;
    this.metrics = Collections.unmodifiableList(new ArrayList<>(metrics));
    this.dirty = dirty;
  }

  /**
   * @return metalake ID
   */
  public long getMetalakeId() {
    return metalakeId;
  }

  /**
   * @return immutable current metric rows
   */
  public List<MetricPO> getMetrics() {
    return metrics;
  }

  /**
   * @return current dirty marker, or {@code null}
   */
  @Nullable
  public MetricDirtyPO getDirty() {
    return dirty;
  }
}
