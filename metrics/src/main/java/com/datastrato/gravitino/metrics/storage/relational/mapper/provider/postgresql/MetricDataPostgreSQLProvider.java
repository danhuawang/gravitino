/*
 * Copyright 2024 Datastrato Pvt Ltd.
 * This software is licensed under the Apache License version 2.
 */
package com.datastrato.gravitino.metrics.storage.relational.mapper.provider.postgresql;

import static com.datastrato.gravitino.metrics.storage.relational.mapper.MetricDataMapper.DIRTY_METRICS_TABLE_NAME;

import com.datastrato.gravitino.metrics.storage.relational.mapper.provider.base.MetricDataBaseSQLProvider;
import java.sql.Timestamp;
import org.apache.ibatis.annotations.Param;

public class MetricDataPostgreSQLProvider extends MetricDataBaseSQLProvider {
  @Override
  public String markMetalakeDirty(
      @Param("metalakeId") long metalakeId, @Param("eventTime") Timestamp eventTime) {
    return "INSERT INTO "
        + DIRTY_METRICS_TABLE_NAME
        + " (metalake_id, revision, first_dirty_at, last_event_at, retry_count, retry_after, last_error)"
        + " VALUES (#{metalakeId}, 1, #{eventTime, jdbcType=TIMESTAMP},"
        + " #{eventTime, jdbcType=TIMESTAMP}, 0, NULL, NULL)"
        + " ON CONFLICT (metalake_id) DO UPDATE SET"
        + " revision = "
        + DIRTY_METRICS_TABLE_NAME
        + ".revision + 1, first_dirty_at = LEAST("
        + DIRTY_METRICS_TABLE_NAME
        + ".first_dirty_at, EXCLUDED.first_dirty_at), last_event_at = GREATEST("
        + DIRTY_METRICS_TABLE_NAME
        + ".last_event_at, EXCLUDED.last_event_at),"
        + " retry_count = 0, retry_after = NULL, last_error = NULL";
  }
}
