/*
 * Copyright 2024 Datastrato Pvt Ltd.
 * This software is licensed under the Apache License version 2.
 */
package com.datastrato.gravitino.dto.responses;

import com.datastrato.gravitino.dto.metrics.MetricDTO;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.google.common.base.Preconditions;
import java.util.Map;
import lombok.EqualsAndHashCode;
import lombok.Getter;
import lombok.ToString;
import org.apache.commons.lang3.StringUtils;
import org.apache.gravitino.dto.responses.BaseResponse;

/** Represents a response for metrics. */
@Getter
@ToString
@EqualsAndHashCode(callSuper = true)
public class MetricsResponse extends BaseResponse {

  @JsonProperty("metrics")
  private final Map<String, MetricDTO> metrics;

  /**
   * Creates a new MetricsResponse with the given metrics.
   *
   * @param metrics A map of metric names to their corresponding MetricDTOs.
   */
  public MetricsResponse(Map<String, MetricDTO> metrics) {
    super(0);
    this.metrics = metrics;
  }

  /**
   * Default constructor for Jackson deserialization. This constructor is used by Jackson to create
   * an instance of MetricsResponse.
   */
  public MetricsResponse() {
    super();
    this.metrics = null;
  }

  @Override
  public void validate() throws IllegalArgumentException {
    super.validate();

    Preconditions.checkArgument(metrics != null, "\"metrics\" cannot be null");
    metrics.forEach(
        (key, value) -> {
          Preconditions.checkArgument(StringUtils.isNotBlank(key), "Metric key cannot be blank");
          Preconditions.checkArgument(
              value != null, "Metric value for key \"" + key + "\" cannot be null");
        });
  }
}
