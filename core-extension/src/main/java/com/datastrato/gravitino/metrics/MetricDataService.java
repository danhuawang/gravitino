/*
 * Copyright 2024 Datastrato Pvt Ltd.
 * This software is licensed under the Apache License version 2.
 */
package com.datastrato.gravitino.metrics;

import com.datastrato.gravitino.dto.metrics.MetricDTO;
import org.apache.gravitino.Config;

public class MetricDataService {

  public static final long DUMMY_TIMESTAMP = 0;

  public enum Metric {
    ASSET_COUNT("asset_count"),
    TAG_COUNT("tag_count"),
    CATALOG_COUNT("catalog_count"),
    SCHEMA_COUNT("schema_count"),
    TABLE_COUNT("table_count"),
    FILESET_COUNT("fileset_count"),
    TOPIC_COUNT("topic_count"),
    MODEL_COUNT("model_count"),
    ASSET_WITH_TAG_COUNT("asset_with_tag_count"),
    ASSET_WITHOUT_TAG_COUNT("asset_without_tag_count"),
    ASSET_WITH_PII_TAG_COUNT("asset_with_pii_tag_count"),
    ASSET_WITH_PUBLIC_TAG_COUNT("asset_with_public_tag_count"),
    ASSET_WITH_CONFIDENTIAL_TAG_COUNT("asset_with_confidential_tag_count"),
    ASSET_WITH_PRIVATE_TAG_COUNT("asset_with_private_tag_count"),
    ASSET_WITH_OWNER_COUNT("asset_with_owner_count");

    private final String name;

    Metric(String name) {
      this.name = name;
    }

    public String getName() {
      return name;
    }

    public static Metric fromName(String name) {
      for (Metric metric : values()) {
        if (metric.name.equalsIgnoreCase(name)) {
          return metric;
        }
      }
      throw new IllegalArgumentException("Unknown metric name: " + name);
    }
  }

  public MetricDataService(Config config) {
    // todo: implement this constructor to initialize the data source
  }

  public MetricDTO[] getMetricsByNameAndTimestamp(
      String metalakeName,
      String userName,
      String[] metricNames,
      long startTimestamp,
      long endTimestamp) {
    // todo: implement this method
    return new MetricDTO[0];
  }

  public void cleanMetricsByTimestamp(long oldestTimestamp) {
    // todo: implement this method to clean up metrics older than the specified timestamp
  }

  public void cleanInvalidMetrics() {
    // todo: implement this method to clean up invalid metrics
  }

  public void insertMetrics(String metalakeName, String userName, MetricDTO[] metrics) {
    // todo: implement this method to insert metrics into the data source
  }

  public long getAssetWithOwnerCount(String metalakeName) {
    // todo: implement this method to return the count of assets with owners
    return 0;
  }
}
