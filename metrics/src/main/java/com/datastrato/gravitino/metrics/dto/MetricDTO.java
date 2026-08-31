/*
 * Copyright 2024 Datastrato Pvt Ltd.
 * This software is licensed under the Apache License version 2.
 */
package com.datastrato.gravitino.metrics.dto;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.google.common.base.Preconditions;
import java.util.Arrays;
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
  private Double[] values;

  @JsonProperty("timestamps")
  private long[] timestamps;

  @JsonProperty("states")
  private MetricState[] states;

  @JsonProperty("messages")
  private String[] messages;

  /**
   * Creates a new MetricDTOBuilder instance to build a MetricDTO.
   *
   * @return A new instance of MetricDTOBuilder.
   */
  public static MetricDTOBuilder builder() {
    return new MetricDTOBuilder();
  }

  private MetricDTO() {}

  private MetricDTO(
      String name, Double[] values, long[] timestamps, MetricState[] states, String[] messages) {
    this.name = name;
    this.values = values;
    this.timestamps = timestamps;
    this.states = states;
    this.messages = messages;
  }

  /**
   * Returns the metric name.
   *
   * @return metric name
   */
  public String name() {
    return name;
  }

  /**
   * Returns aligned metric values; unavailable entries are {@code null}.
   *
   * @return metric values
   */
  public Double[] values() {
    return values;
  }

  /**
   * Returns timestamps aligned with values, states, and messages.
   *
   * @return metric timestamps
   */
  public long[] timestamps() {
    return timestamps;
  }

  /**
   * Returns collection states aligned with values and timestamps.
   *
   * @return metric states
   */
  public MetricState[] states() {
    return states;
  }

  /**
   * Returns safe messages aligned with values and timestamps.
   *
   * @return metric messages, whose entries may be {@code null}
   */
  public String[] messages() {
    return messages;
  }

  /** Builder class for creating instances of MetricDTO. */
  public static class MetricDTOBuilder {

    private String name;
    private Double[] values;
    private long[] timestamps;
    private MetricState[] states;
    private String[] messages;

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
    public MetricDTOBuilder withValues(Double[] values) {
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
     * Sets the collection states corresponding to the metric values.
     *
     * @param states The collection states for the metric values.
     * @return The builder instance.
     */
    public MetricDTOBuilder withStates(MetricState[] states) {
      this.states = states;
      return this;
    }

    /**
     * Sets safe, human-readable messages corresponding to the metric values.
     *
     * @param messages The messages for the metric values; entries may be {@code null}.
     * @return The builder instance.
     */
    public MetricDTOBuilder withMessages(String[] messages) {
      this.messages = messages;
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
      Preconditions.checkArgument(states != null, "Metric states cannot be null");
      Preconditions.checkArgument(messages != null, "Metric messages cannot be null");
      Preconditions.checkArgument(
          values.length == timestamps.length, "Values and timestamps must have the same length");
      Preconditions.checkArgument(
          values.length == states.length && values.length == messages.length,
          "Values, timestamps, states, and messages must have the same length");
      Preconditions.checkArgument(
          Arrays.stream(states).noneMatch(state -> state == null),
          "Metric states cannot contain null");
      for (int i = 0; i < values.length; i++) {
        Preconditions.checkArgument(
            states[i] == MetricState.UNAVAILABLE ? values[i] == null : values[i] != null,
            "Metric value must be null only when the metric is unavailable");
        Preconditions.checkArgument(
            states[i] == MetricState.COMPLETE || StringUtils.isNotBlank(messages[i]),
            "Partial and unavailable metrics require a message");
      }

      return new MetricDTO(name, values, timestamps, states, messages);
    }
  }
}
