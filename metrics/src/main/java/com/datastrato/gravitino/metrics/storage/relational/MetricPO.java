/*
 * Copyright 2024 Datastrato Inc.
 */
package com.datastrato.gravitino.metrics.storage.relational;

import com.datastrato.gravitino.metrics.dto.MetricState;
import com.google.common.base.Preconditions;
import java.sql.Timestamp;
import java.util.Objects;
import javax.annotation.Nullable;
import org.apache.commons.lang3.StringUtils;

public class MetricPO {
  private Long id;
  private Long metalakeId;
  private Long userId;
  private String metricName;
  @Nullable private Double metricValue;
  private MetricState metricState;
  @Nullable private String metricMessage;
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

  /**
   * Returns the metric value, or {@code null} when the metric is unavailable.
   *
   * @return nullable metric value
   */
  @Nullable
  public Double getMetricValue() {
    return metricValue;
  }

  /**
   * Returns the collection state for this metric row.
   *
   * @return metric collection state
   */
  public MetricState getMetricState() {
    return metricState;
  }

  /**
   * Returns a safe collection message, if present.
   *
   * @return nullable metric message
   */
  @Nullable
  public String getMetricMessage() {
    return metricMessage;
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
        && metricState == metricPO.metricState
        && Objects.equals(metricMessage, metricPO.metricMessage)
        && Objects.equals(createdTime, metricPO.createdTime);
  }

  @Override
  public int hashCode() {
    return Objects.hash(
        getId(),
        getMetalakeId(),
        getUserId(),
        getMetricName(),
        getMetricValue(),
        getMetricState(),
        getMetricMessage(),
        getCreatedTime());
  }

  public static class Builder {
    private Long id;
    private Long metalakeId;
    private Long userId;
    private String metricName;
    @Nullable private Double metricValue;
    private MetricState metricState = MetricState.COMPLETE;
    @Nullable private String metricMessage;
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

    /**
     * Sets the nullable metric value.
     *
     * @param metricValue value, or {@code null} for an unavailable metric
     * @return this builder
     */
    public Builder withMetricValue(@Nullable Double metricValue) {
      this.metricValue = metricValue;
      return this;
    }

    /**
     * Sets the metric collection state.
     *
     * @param metricState collection state
     * @return this builder
     */
    public Builder withMetricState(MetricState metricState) {
      this.metricState = metricState;
      return this;
    }

    /**
     * Sets a safe, human-readable collection message.
     *
     * @param metricMessage nullable message
     * @return this builder
     */
    public Builder withMetricMessage(@Nullable String metricMessage) {
      this.metricMessage = metricMessage;
      return this;
    }

    public Builder withCreatedTime(Timestamp createdTime) {
      this.createdTime = createdTime;
      return this;
    }

    private void validate() {
      Preconditions.checkArgument(metricName != null, "Metric name is required");
      Preconditions.checkArgument(metricState != null, "Metric state is required");
      Preconditions.checkArgument(
          metricState == MetricState.UNAVAILABLE ? metricValue == null : metricValue != null,
          "Metric value must be null only when the metric is unavailable");
      Preconditions.checkArgument(
          metricState == MetricState.COMPLETE || StringUtils.isNotBlank(metricMessage),
          "Partial and unavailable metrics require a message");
    }

    public MetricPO build() {
      validate();
      MetricPO metricPO = new MetricPO();
      metricPO.id = id;
      metricPO.metalakeId = metalakeId;
      metricPO.userId = userId;
      metricPO.metricName = metricName;
      metricPO.metricValue = metricValue;
      metricPO.metricState = metricState;
      metricPO.metricMessage = metricMessage;
      metricPO.createdTime =
          createdTime == null ? new Timestamp(System.currentTimeMillis()) : createdTime;
      return metricPO;
    }
  }
}
