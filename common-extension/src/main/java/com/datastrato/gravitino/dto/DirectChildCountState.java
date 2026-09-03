/*
 * Copyright 2026 Datastrato Inc.
 */
package com.datastrato.gravitino.dto;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonValue;

/** Describes the completeness of an entity direct-child count. */
public enum DirectChildCountState {
  /** The count was computed from every dependency. */
  COMPLETE,

  /** Some dependencies were unavailable. */
  PARTIAL,

  /** No reliable count is currently available. */
  UNAVAILABLE;

  /**
   * @return uppercase JSON value
   */
  @JsonValue
  public String value() {
    return name();
  }

  /**
   * Parses the uppercase JSON value.
   *
   * @param value JSON value
   * @return parsed state
   */
  @JsonCreator
  public static DirectChildCountState fromValue(String value) {
    return valueOf(value);
  }
}
