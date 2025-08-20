/*
 * Copyright 2024 Datastrato Pvt Ltd.
 * This software is licensed under the Apache License version 2.
 */
package com.datastrato.gravitino.metrics.storage.relational.service;

import com.datastrato.gravitino.metrics.dto.MetricDTO;
import com.datastrato.gravitino.metrics.storage.relational.MetricPO;
import com.datastrato.gravitino.metrics.storage.relational.mapper.MetricDataMapper;
import com.datastrato.gravitino.metrics.storage.relational.utils.DatastratoPOConverters;
import com.google.common.base.Preconditions;
import java.sql.Timestamp;
import java.util.List;
import org.apache.gravitino.MetadataObject;
import org.apache.gravitino.MetadataObjects;
import org.apache.gravitino.authorization.Owner;
import org.apache.gravitino.authorization.OwnerDispatcher;
import org.apache.gravitino.exceptions.NoSuchEntityException;
import org.apache.gravitino.exceptions.NoSuchMetalakeException;
import org.apache.gravitino.exceptions.NoSuchUserException;
import org.apache.gravitino.storage.relational.service.MetalakeMetaService;
import org.apache.gravitino.storage.relational.service.UserMetaService;
import org.apache.gravitino.storage.relational.utils.SessionUtils;
import org.apache.gravitino.utils.NameIdentifierUtil;

public class MetricDataService {

  public static MetricDataService getInstance() {
    return INSTANCE;
  }

  private static final MetricDataService INSTANCE = new MetricDataService();

  private OwnerDispatcher ownerDispatcher;
  private boolean enableAuthorization;

  private MetricDataService() {}

  public void initialize(OwnerDispatcher ownerDispatcher, boolean enableAuthorization) {
    this.ownerDispatcher = ownerDispatcher;
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

    boolean isMetalakeOwner = isMetalakeOwner(metalakeName, userName);
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
                    enableAuthorization,
                    enableAuthorization && isMetalakeOwner));
    return DatastratoPOConverters.fromMetricPOs(metricPOs);
  }

  public long getAssetWithOwnerCount(String metalakeName) {
    return SessionUtils.getWithoutCommit(
        MetricDataMapper.class, mapper -> mapper.getAssetWithOwnerCount(metalakeName));
  }

  public void insertMetrics(
      String metalakeName, String userName, List<MetricPO> metrics, boolean forMetalakeOwner) {
    if (metrics == null || metrics.isEmpty()) {
      return;
    }

    Preconditions.checkArgument(
        enableAuthorization || !forMetalakeOwner,
        "enableAuthorization cannot be false when forMetalakeOwner is true");

    SessionUtils.doWithCommit(
        MetricDataMapper.class,
        mapper ->
            mapper.insertMetricsData(
                metalakeName, userName, metrics, enableAuthorization, forMetalakeOwner));
  }

  public void cleanMetricsByTimestamp(long oldestTimestamp) {
    SessionUtils.doWithCommit(
        MetricDataMapper.class,
        mapper -> mapper.cleanMetricsByTimestamp(new Timestamp(oldestTimestamp)));
  }

  public void cleanInvalidMetrics() {
    SessionUtils.doWithCommit(MetricDataMapper.class, MetricDataMapper::cleanInvalidMetrics);
  }

  public boolean isMetalakeOwner(String metalakeName, String userName) {
    if (!enableAuthorization) {
      return false;
    }

    Owner metalakeOwner = getMetalakeOwner(metalakeName);
    if (metalakeOwner != null && metalakeOwner.type() == Owner.Type.GROUP) {
      throw new UnsupportedOperationException(
          "Metalake owned by a group is not supported yet: " + metalakeName);
    }
    return metalakeOwner != null
        && metalakeOwner.type() == Owner.Type.USER
        && metalakeOwner.name().equals(userName);
  }

  public Owner getMetalakeOwner(String metalakeName) {
    MetadataObject metalakeObject =
        MetadataObjects.of(null, metalakeName, MetadataObject.Type.METALAKE);
    return ownerDispatcher
        .getOwner(metalakeName, metalakeObject)
        .orElseThrow(
            () ->
                new IllegalStateException(
                    "No owner found for metalake: " + metalakeName + ". This should not happen."));
  }
}
