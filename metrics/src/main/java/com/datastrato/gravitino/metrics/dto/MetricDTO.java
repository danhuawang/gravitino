/*
 * Copyright 2024 Datastrato Pvt Ltd.
 * This software is licensed under the Apache License version 2.
 */
package com.datastrato.gravitino.metrics.dto;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.google.common.base.Preconditions;
import lombok.EqualsAndHashCode;
import lombok.ToString;
import org.apache.commons.lang3.StringUtils;

/** Represents a Data Transfer Object (DTO) for metrics. */
@ToString
@EqualsAndHashCode
public class MetricDTO {

  @JsonProperty("name")
  private String name;

  @JsonProperty("values")
  private double[] values;

  @JsonProperty("timestamps")
  private long[] timestamps;

  /**
   * Creates a new MetricDTOBuilder instance to build a MetricDTO.
   *
   * @return A new instance of MetricDTOBuilder.
   */
  public static MetricDTOBuilder builder() {
    return new MetricDTOBuilder();
  }

  private MetricDTO() {}

  private MetricDTO(String name, double[] values, long[] timestamps) {
    this.name = name;
    this.values = values;
    this.timestamps = timestamps;
  }

  public String name() {
    return name;
  }

  public double[] values() {
    return values;
  }

  public long[] timestamps() {
    return timestamps;
  }

  /** Builder class for creating instances of MetricDTO. */
  public static class MetricDTOBuilder {

    private String name;
    private double[] values;
    private long[] timestamps;

    /**
     * Sets the name of the metric.
     *
     * @param name The name of the metric.
     * @return The builder instance.
     */
    public MetricDTOBuilder withName(String name) {
      this.name = name;
      return this;
    }

    /**
     * Sets the values of the metric.
     *
     * @param values The values of the metric.
     * @return The builder instance.
     */
    public MetricDTOBuilder withValues(double[] values) {
      this.values = values;
      return this;
    }

    /**
     * Sets the timestamps for the metric values.
     *
     * @param timestamps The timestamps corresponding to the metric values.
     * @return The builder instance.
     */
    public MetricDTOBuilder withTimestamps(long[] timestamps) {
      this.timestamps = timestamps;
      return this;
    }

    /**
     * Builds the MetricDTO instance.
     *
     * @return A new MetricDTO instance.
     */
    public MetricDTO build() {
      Preconditions.checkArgument(StringUtils.isNotBlank(name), "Metric name is required");
      Preconditions.checkArgument(values != null, "Metric values cannot be null");
      Preconditions.checkArgument(timestamps != null, "Metric timestamps cannot be null");
      Preconditions.checkArgument(
          values.length == timestamps.length, "Values and timestamps must have the same length");

      return new MetricDTO(name, values, timestamps);
    }
  }
}
