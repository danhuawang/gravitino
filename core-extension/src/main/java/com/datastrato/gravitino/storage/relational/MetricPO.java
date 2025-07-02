/*
 * Copyright 2024 Datastrato Pvt Ltd.
 * This software is licensed under the Apache License version 2.
 */
package com.datastrato.gravitino.storage.relational;

import com.google.common.base.Preconditions;
import java.sql.Timestamp;
import java.util.Objects;

public class MetricPO {
  private Long id;
  private Long metalakeId;
  private Long userId;
  private String metricName;
  private Double metricValue;
  private Timestamp createdTime;

  public static Builder builder() {
    return new Builder();
  }

  public Long getId() {
    return id;
  }

  public Long getMetalakeId() {
    return metalakeId;
  }

  public Long getUserId() {
    return userId;
  }

  public String getMetricName() {
    return metricName;
  }

  public Double getMetricValue() {
    return metricValue;
  }

  public Timestamp getCreatedTime() {
    return createdTime;
  }

  @Override
  public boolean equals(Object o) {
    if (!(o instanceof MetricPO)) return false;
    MetricPO metricPO = (MetricPO) o;
    return Objects.equals(id, metricPO.id)
        && Objects.equals(metalakeId, metricPO.metalakeId)
        && Objects.equals(userId, metricPO.userId)
        && Objects.equals(metricName, metricPO.metricName)
        && Objects.equals(metricValue, metricPO.metricValue)
        && Objects.equals(createdTime, metricPO.createdTime);
  }

  @Override
  public int hashCode() {
    return Objects.hash(
        getId(), getMetalakeId(), getUserId(), getMetricName(), getMetricValue(), getCreatedTime());
  }

  public static class Builder {
    private Long id;
    private Long metalakeId;
    private Long userId;
    private String metricName;
    private Double metricValue;
    private Timestamp createdTime;

    private Builder() {}

    public Builder withId(Long id) {
      this.id = id;
      return this;
    }

    public Builder withMetalakeId(Long metalakeId) {
      this.metalakeId = metalakeId;
      return this;
    }

    public Builder withUserId(Long userId) {
      this.userId = userId;
      return this;
    }

    public Builder withMetricName(String metricName) {
      this.metricName = metricName;
      return this;
    }

    public Builder withMetricValue(Double metricValue) {
      this.metricValue = metricValue;
      return this;
    }

    public Builder withCreatedTime(Timestamp createdTime) {
      this.createdTime = createdTime;
      return this;
    }

    private void validate() {
      Preconditions.checkArgument(metricName != null, "Metric name is required");
      Preconditions.checkArgument(metricValue != null, "Metric value is required");
    }

    public MetricPO build() {
      validate();
      MetricPO metricPO = new MetricPO();
      metricPO.id = this.id;
      metricPO.metalakeId = this.metalakeId;
      metricPO.userId = this.userId;
      metricPO.metricName = this.metricName;
      metricPO.metricValue = this.metricValue;
      metricPO.createdTime = this.createdTime;
      return metricPO;
    }
  }
}
