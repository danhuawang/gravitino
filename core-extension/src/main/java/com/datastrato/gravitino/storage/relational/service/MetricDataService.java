/*
 * Copyright 2024 Datastrato Pvt Ltd.
 * This software is licensed under the Apache License version 2.
 */
package com.datastrato.gravitino.storage.relational.service;

import com.datastrato.gravitino.dto.metrics.MetricDTO;
import com.datastrato.gravitino.storage.relational.MetricPO;
import com.datastrato.gravitino.storage.relational.mapper.MetricDataMapper;
import com.datastrato.gravitino.storage.relational.utils.DatastratoPOConverters;
import java.sql.Timestamp;
import java.util.List;
import org.apache.gravitino.exceptions.NoSuchEntityException;
import org.apache.gravitino.exceptions.NoSuchMetalakeException;
import org.apache.gravitino.exceptions.NoSuchUserException;
import org.apache.gravitino.storage.relational.service.MetalakeMetaService;
import org.apache.gravitino.storage.relational.service.UserMetaService;
import org.apache.gravitino.storage.relational.utils.SessionUtils;
import org.apache.gravitino.utils.NameIdentifierUtil;

public class MetricDataService {
  public static final long DUMMY_TIMESTAMP = 0;

  private final boolean enableAuthorization;

  public MetricDataService(boolean enableAuthorization) {
    this.enableAuthorization = enableAuthorization;
  }

  public enum Metric {
    ASSET_COUNT("asset_count"),
    TAG_COUNT("tag_count"),
    CATALOG_COUNT("catalog_count"),
    SCHEMA_COUNT("schema_count"),
    TABLE_COUNT("table_count"),
    FILESET_COUNT("fileset_count"),
    TOPIC_COUNT("topic_count"),
    MODEL_COUNT("model_count"),
    TAGGED_ASSET_COUNT("tagged_asset_count"),
    UNTAGGED_ASSET_COUNT("untagged_asset_count"),
    PII_TAGGED_ASSET_COUNT("pii_tagged_asset_count"),
    PUBLIC_TAGGED_ASSET_COUNT("public_tagged_asset_count"),
    CONFIDENTIAL_TAGGED_ASSET_COUNT("confidential_tagged_asset_count"),
    PRIVATE_TAGGED_ASSET_COUNT("private_tagged_asset_count"),
    OWNED_ASSET_COUNT("owned_asset_count");

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

  public MetricDTO[] getMetricsByNameAndTimestamp(
      String metalakeName,
      String userName,
      String[] metricNames,
      long startTimestamp,
      long endTimestamp) {
    try {
      MetalakeMetaService.getInstance().getMetalakeIdByName(metalakeName);
    } catch (NoSuchEntityException e) {
      throw new NoSuchMetalakeException("Metalake not found: " + metalakeName, e);
    }

    if (enableAuthorization) {
      try {
        UserMetaService.getInstance()
            .getUserByIdentifier(NameIdentifierUtil.ofUser(metalakeName, userName));
      } catch (NoSuchEntityException e) {
        throw new NoSuchUserException(
            "User not found: " + userName + " for metalake: " + metalakeName, e);
      }
    }

    List<MetricPO> metricPOs =
        SessionUtils.getWithoutCommit(
            MetricDataMapper.class,
            mapper ->
                mapper.getMetricPOsByNameAndTimestamp(
                    metalakeName,
                    userName,
                    metricNames,
                    new Timestamp(startTimestamp),
                    new Timestamp(endTimestamp),
                    enableAuthorization));
    return DatastratoPOConverters.fromMetricPOs(metricPOs);
  }

  public long getAssetWithOwnerCount(String metalakeName) {
    return SessionUtils.getWithoutCommit(
        MetricDataMapper.class, mapper -> mapper.getAssetWithOwnerCount(metalakeName));
  }

  public void insertMetrics(String metalakeName, String userName, List<MetricPO> metrics) {
    if (metrics == null || metrics.isEmpty()) {
      return;
    }

    SessionUtils.doWithCommit(
        MetricDataMapper.class,
        mapper -> mapper.insertMetricsData(metalakeName, userName, metrics, enableAuthorization));
  }

  public void cleanMetricsByTimestamp(long oldestTimestamp) {
    SessionUtils.doWithCommit(
        MetricDataMapper.class,
        mapper -> mapper.cleanMetricsByTimestamp(new Timestamp(oldestTimestamp)));
  }

  public void cleanInvalidMetrics() {
    SessionUtils.doWithCommit(MetricDataMapper.class, MetricDataMapper::cleanInvalidMetrics);
  }
}
