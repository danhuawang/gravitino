/*
 * Copyright 2024 Datastrato Inc.
 */
package com.datastrato.gravitino.metrics.dto;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonValue;

/** Describes whether a dashboard metric data point was computed from all of its dependencies. */
public enum MetricState {
  /** Every dependency was collected successfully and the metric has a value. */
  COMPLETE,

  /** Some dependencies failed, but the successful subset still produced a value. */
  PARTIAL,

  /** No valid dependency input was available, so the metric has no value. */
  UNAVAILABLE;

  /**
   * Returns the uppercase value used by the dashboard metrics REST contract.
   *
   * @return the uppercase metric state
   */
  @JsonValue
  public String value() {
    return name();
  }

  /**
   * Parses an uppercase dashboard metric state.
   *
   * @param value the uppercase metric state
   * @return the parsed metric state
   */
  @JsonCreator
  public static MetricState fromValue(String value) {
    return valueOf(value);
  }
}
